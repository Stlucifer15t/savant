package dev.savant.parsers.json

import dev.savant.domain.model.FieldPath
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.Node
import dev.savant.domain.model.SaveFormat
import dev.savant.parsers.categorize.CategoryClassifier
import dev.savant.parsers.categorize.Humanizer

/**
 * Turns the faithful json document into the [FieldTree] the UI and the tools work with, and
 * records a [JsonRef] per leaf so an edit lands in the exact slot it came from.
 *
 * The tree is capped at `maxNodes`; the document itself is never truncated, so writing back a
 * capped tree cannot delete data the user never saw.
 */
internal object JsonTreeBuilder {

    class Result(
        val tree: FieldTree,
        val refs: Map<String, JsonRef>,
        val totalValues: Long,
        val crypticKeys: Int,
        val warnings: List<String>,
    )

    fun build(
        root: JsonNode,
        maxNodes: Int,
        extraWarnings: List<String> = emptyList(),
        readOnlyReason: String? = null,
    ): Result {
        val builder = Builder(maxNodes)
        val roots = builder.buildRoot(root).filterNotNull()
        val warnings = (builder.warnings + extraWarnings).distinct()
        val tree = FieldTree(
            format = SaveFormat.JSON,
            roots = roots,
            warnings = warnings,
            truncated = builder.truncated,
            readOnlyReason = readOnlyReason,
        )
        return Result(tree, builder.refs, builder.totalValues, builder.crypticKeys, warnings)
    }

    private class Builder(private val maxNodes: Int) {
        val refs = HashMap<String, JsonRef>()
        val warnings = mutableListOf<String>()
        var totalValues = 0L
        var crypticKeys = 0
        var truncated = false

        private var emitted = 0
        private val seen = HashSet<String>()

        fun buildRoot(root: JsonNode): List<Node?> = when (root) {
            is JObject -> root.members.mapIndexed { index, member -> memberNode(root, index, member, FieldPath.ROOT) }
            is JArray -> root.items.mapIndexed { index, item -> elementNode(root, index, item, FieldPath.ROOT) }
            else -> {
                warnings += "this file is a single json value with nothing to address by path"
                listOf(valueNode(FieldPath.ROOT, "value", root, ref = null, isKey = false))
            }
        }

        private fun memberNode(obj: JObject, index: Int, member: JMember, parent: FieldPath): Node? {
            val path = uniquePath(parent.child(member.key))
            val ref = JsonRef(
                read = { obj.members[index].value },
                write = { next -> obj.members[index].value = next },
            )
            return valueNode(path, member.key, member.value, ref, isKey = true)
        }

        private fun elementNode(array: JArray, index: Int, item: JsonNode, parent: FieldPath): Node? {
            val path = uniquePath(parent.child(index))
            val ref = JsonRef(
                read = { array.items[index] },
                write = { next -> array.items[index] = next },
            )
            return valueNode(path, index.toString(), item, ref, isKey = false)
        }

        private fun valueNode(path: FieldPath, key: String, node: JsonNode, ref: JsonRef?, isKey: Boolean): Node? {
            totalValues++
            val encoded = path.encode()
            val displayName = if (isKey) Humanizer.humanize(key) else key
            if (isKey && Humanizer.isCryptic(key)) crypticKeys++

            if (emitted >= maxNodes) {
                if (!truncated) {
                    truncated = true
                    warnings += "showing the first $maxNodes values; everything else is written back untouched"
                }
                totalValues += countSubtree(node) - 1
                return null
            }

            return when (node) {
                is JObject -> {
                    emitted++
                    Node(
                        path = encoded,
                        displayName = displayName,
                        type = FieldType.OBJECT,
                        value = null,
                        editable = false,
                        sourceKey = key,
                        children = node.members.mapIndexed { index, member -> memberNode(node, index, member, path) }
                            .filterNotNull(),
                    )
                }
                is JArray -> {
                    emitted++
                    Node(
                        path = encoded,
                        displayName = displayName,
                        type = FieldType.LIST,
                        value = null,
                        editable = false,
                        sourceKey = key,
                        children = node.items.mapIndexed { index, item -> elementNode(node, index, item, path) }
                            .filterNotNull(),
                    )
                }
                else -> {
                    emitted++
                    leafNode(encoded, key, displayName, node, ref)
                }
            }
        }

        private fun leafNode(encoded: String, key: String, displayName: String, node: JsonNode, ref: JsonRef?): Node {
            if (ref != null) refs[encoded] = ref
            val type: FieldType
            val value: FieldValue?
            var editable = ref != null
            when (node) {
                JNull -> {
                    type = FieldType.NULL
                    value = FieldValue.Null
                }
                is JBool -> {
                    type = FieldType.BOOL
                    value = FieldValue.Bool(node.value)
                }
                is JNumber -> {
                    val parts = numberParts(node)
                    type = parts.first
                    value = parts.second
                    editable = editable && parts.third
                }
                is JString -> {
                    type = FieldType.STRING
                    value = FieldValue.Text(node.value)
                }
                else -> {
                    type = FieldType.OBJECT
                    value = null
                    editable = false
                }
            }
            return Node(
                path = encoded,
                displayName = displayName,
                type = type,
                value = value,
                category = CategoryClassifier.classify(encoded, key, value, type),
                editable = editable,
                sourceKey = key,
            )
        }

        private fun numberParts(node: JNumber): Triple<FieldType, FieldValue?, Boolean> {
            val raw = node.raw
            if (raw == "NaN" || raw == "Infinity" || raw == "-Infinity") {
                warnings += "'$raw' is not valid json; the literal is kept and left alone"
                return Triple(FieldType.STRING, FieldValue.Text(raw), false)
            }
            val parsed = node.value
            if (parsed == null || parsed.isNaN()) {
                warnings += "number literal '$raw' could not be parsed; it is written back exactly as found"
                return Triple(FieldType.STRING, FieldValue.Text(raw), false)
            }
            if (node.integral) {
                raw.toLongOrNull()?.let { asLong ->
                    val type = if (asLong in Int.MIN_VALUE..Int.MAX_VALUE) FieldType.INT32 else FieldType.INT64
                    return Triple(type, FieldValue.Int64(asLong), true)
                }
                raw.toULongOrNull()?.let { asUlong ->
                    return Triple(FieldType.UINT64, FieldValue.Int64(asUlong.toLong(), unsigned = true), true)
                }
            }
            return Triple(FieldType.DOUBLE, FieldValue.Real(parsed), true)
        }

        /** Duplicate keys are legal json and must stay addressable: `name`, `name#2`, ... */
        private fun uniquePath(candidate: FieldPath): FieldPath {
            val encoded = candidate.encode()
            if (claim(encoded)) return candidate
            var suffix = 2
            while (true) {
                val segments = candidate.segments.toMutableList()
                val last = segments.removeAt(segments.size - 1)
                if (last is FieldPath.Segment.Key) {
                    segments += FieldPath.Segment.Key("${last.name}#$suffix")
                } else {
                    segments += last
                }
                val attempt = FieldPath(segments)
                if (claim(attempt.encode())) return attempt
                suffix++
            }
        }

        private fun claim(encoded: String): Boolean {
            if (seen.contains(encoded) || refs.containsKey(encoded)) return false
            seen += encoded
            return true
        }

        private fun countSubtree(node: JsonNode): Long = when (node) {
            is JObject -> 1 + node.members.sumOf { countSubtree(it.value) }
            is JArray -> 1 + node.items.sumOf { countSubtree(it) }
            else -> 1L
        }
    }
}
