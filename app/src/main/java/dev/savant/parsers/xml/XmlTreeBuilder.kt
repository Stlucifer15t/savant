package dev.savant.parsers.xml

import dev.savant.domain.model.FieldPath
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.Node
import dev.savant.domain.model.SaveFormat
import dev.savant.parsers.categorize.CategoryClassifier
import dev.savant.parsers.categorize.Humanizer

/**
 * Builds the field tree from an xml document.
 *
 * Mapping rules:
 * - an element with element children or attributes becomes an object; repeated sibling names are
 *   indexed (`item[0]`, `item[1]`) so every field has a stable path
 * - an element holding only text/cdata is a leaf whose type is inferred from the text
 * - attributes become leaves at `element.@name`
 * - an element with both attributes and text gets an extra `element.#text` leaf
 * - apple plists are mapped by dict semantics: `<key>gold</key><integer>500</integer>` becomes a
 *   field named "Gold" of type i64, which is what makes unity PlayerPrefs files readable
 *
 * The tree is capped at `maxNodes`; the document itself never is, so exporting a capped tree
 * cannot drop data the user never saw.
 */
internal object XmlTreeBuilder {

    const val TEXT_KEY = "#text"

    private val PLIST_VALUE_TAGS = setOf("integer", "real", "string", "date", "data", "true", "false")

    class Result(
        val tree: FieldTree,
        val refs: Map<String, XmlRef>,
        val totalValues: Long,
        val crypticKeys: Int,
        val warnings: List<String>,
    )

    fun build(
        model: XmlDocumentModel,
        maxNodes: Int,
        extraWarnings: List<String> = emptyList(),
        readOnlyReason: String? = null,
    ): Result {
        val builder = Builder(maxNodes, model.isPlist)
        val root = model.root
        val roots = root?.let { builder.nodeFor(it, FieldPath.ROOT.child(it.name), it.name, null) }
            ?.let { listOf(it) }
            ?: emptyList()
        val tree = FieldTree(
            format = SaveFormat.XML,
            roots = roots,
            warnings = (builder.warnings + extraWarnings).distinct(),
            truncated = builder.truncated,
            readOnlyReason = readOnlyReason,
        )
        return Result(tree, builder.refs, builder.totalValues, builder.crypticKeys, tree.warnings)
    }

    private class Builder(private val maxNodes: Int, private val plist: Boolean) {
        val refs = HashMap<String, XmlRef>()
        val warnings = mutableListOf<String>()
        var totalValues = 0L
        var crypticKeys = 0
        var truncated = false

        private var emitted = 0
        private val seen = HashSet<String>()

        fun nodeFor(element: XmlElement, path: FieldPath, key: String, parent: XmlElement?): Node? {
            if (plist && element.name.equals("key", true)) return null // consumed by plistDict
            val claimed = claim(path) ?: return null
            totalValues++
            if (emitted >= maxNodes) {
                if (!truncated) {
                    truncated = true
                    warnings += "showing the first $maxNodes values; everything else is written back untouched"
                }
                totalValues += countSubtree(element) - 1
                return null
            }
            if (Humanizer.isCryptic(key)) crypticKeys++

            if (plist) {
                val tag = element.name.lowercase()
                if (tag == "dict") return plistDict(element, claimed, key)
                if (tag == "array") return plistArray(element, claimed, key)
                if (tag in PLIST_VALUE_TAGS) return plistLeaf(element, claimed, key, tag, parent)
            }

            val attributes = element.attributes
            val childElements = element.elements()
            val text = element.textValue()
            val hasText = text.isNotBlank() || element.children.any { it is XmlCData && it.content.isNotEmpty() }

            if (childElements.isEmpty() && attributes.isEmpty()) {
                emitted++
                val (type, value) = XmlValues.infer(text)
                val encoded = claimed.encode()
                refs[encoded] = textRef(element)
                if (element.children.count { it is XmlText && it.raw.isNotBlank() } > 1) {
                    warnings += "<$key> mixes several text runs; export keeps the first one"
                }
                return Node(
                    path = encoded,
                    displayName = Humanizer.humanize(key),
                    type = type,
                    value = value,
                    category = CategoryClassifier.classify(encoded, key, value, type),
                    editable = true,
                    sourceKey = key,
                )
            }

            emitted++
            val children = mutableListOf<Node>()
            attributes.forEachIndexed { index, attribute ->
                attributeNode(element, index, attribute, claimed)?.let { children += it }
            }
            if (hasText) textNode(element, claimed)?.let { children += it }
            children += elementChildren(element, claimed, element)
            return Node(
                path = claimed.encode(),
                displayName = Humanizer.humanize(key),
                type = FieldType.OBJECT,
                value = null,
                editable = false,
                sourceKey = key,
                children = children,
            )
        }

        /** Child elements with repeated names indexed. */
        private fun elementChildren(element: XmlElement, parent: FieldPath, owner: XmlElement): List<Node> {
            val counts = element.elements().groupingBy { it.name }.eachCount()
            val ordinals = HashMap<String, Int>()
            val out = mutableListOf<Node>()
            for (child in element.elements()) {
                val ordinal = ordinals[child.name] ?: 0
                ordinals[child.name] = ordinal + 1
                val path = if ((counts[child.name] ?: 0) > 1) {
                    parent.child(child.name).child(ordinal)
                } else {
                    parent.child(child.name)
                }
                nodeFor(child, path, child.name, owner)?.let { out += it }
            }
            return out
        }

        private fun attributeNode(
            element: XmlElement,
            index: Int,
            attribute: XmlAttribute,
            parent: FieldPath,
        ): Node? {
            val key = "@${attribute.name}"
            val claimed = claim(parent.child(key)) ?: return null
            totalValues++
            if (emitted >= maxNodes) return null
            emitted++
            val encoded = claimed.encode()
            val value = XmlEntities.decode(attribute.rawValue)
            val (type, inferred) = XmlValues.infer(value)
            refs[encoded] = XmlRef(
                read = { element.attributes[index].value },
                write = { next ->
                    val target = element.attributes[index]
                    target.rawValue = XmlEntities.escapeAttribute(next, target.quote)
                },
                kind = XmlRef.Kind.ATTRIBUTE,
            )
            if (Humanizer.isCryptic(attribute.name)) crypticKeys++
            return Node(
                path = encoded,
                displayName = key,
                type = type,
                value = inferred,
                category = CategoryClassifier.classify(encoded, attribute.name, inferred, type),
                editable = true,
                sourceKey = key,
            )
        }

        private fun textNode(element: XmlElement, parent: FieldPath): Node? {
            val claimed = claim(parent.child(TEXT_KEY)) ?: return null
            totalValues++
            if (emitted >= maxNodes) return null
            emitted++
            val encoded = claimed.encode()
            val text = element.textValue()
            val (type, value) = XmlValues.infer(text)
            refs[encoded] = textRef(element)
            return Node(
                path = encoded,
                displayName = "#text",
                type = type,
                value = value,
                category = CategoryClassifier.classify(encoded, element.name, value, type),
                editable = true,
                sourceKey = TEXT_KEY,
            )
        }

        // ---- apple plist -------------------------------------------------------------

        private fun plistDict(dict: XmlElement, path: FieldPath, key: String): Node {
            emitted++
            val children = mutableListOf<Node>()
            for ((keyElement, valueElement) in plistPairs(dict)) {
                if (keyElement == null) {
                    warnings += "plist entry without a <key> was skipped"
                    continue
                }
                val name = keyElement.textValue().trim()
                if (name.isEmpty()) continue
                if (Humanizer.isCryptic(name)) crypticKeys++
                nodeFor(valueElement, path.child(name), name, dict)?.let { children += it }
            }
            return Node(
                path = path.encode(),
                displayName = Humanizer.humanize(key),
                type = FieldType.OBJECT,
                value = null,
                editable = false,
                sourceKey = key,
                children = children,
            )
        }

        private fun plistArray(array: XmlElement, path: FieldPath, key: String): Node {
            emitted++
            val children = mutableListOf<Node>()
            array.elements().forEachIndexed { index, item ->
                nodeFor(item, path.child(index), index.toString(), array)?.let { children += it }
            }
            return Node(
                path = path.encode(),
                displayName = Humanizer.humanize(key),
                type = FieldType.LIST,
                value = null,
                editable = false,
                sourceKey = key,
                children = children,
            )
        }

        /**
         * A plist value tag: the type comes from the tag, never from the text, so
         * `<string>123</string>` stays a string and `<integer>007</integer>` stays an integer.
         */
        private fun plistLeaf(
            element: XmlElement,
            path: FieldPath,
            key: String,
            tag: String,
            parent: XmlElement?,
        ): Node {
            emitted++
            val encoded = path.encode()
            val type = XmlValues.plistType(tag)
            val boolean = tag == "true" || tag == "false"
            val value = if (boolean) {
                FieldValue.Bool(tag == "true")
            } else {
                XmlValues.plistValue(tag, element.textValue())
            }
            refs[encoded] = if (boolean) boolRef(element, parent) else textRef(element)
            return Node(
                path = encoded,
                displayName = Humanizer.humanize(key),
                type = type,
                value = value,
                category = CategoryClassifier.classify(encoded, key, value, type),
                editable = true,
                sourceKey = key,
            )
        }

        private fun boolRef(element: XmlElement, parent: XmlElement?): XmlRef {
            val index = parent?.children?.indexOf(element) ?: -1
            return XmlRef(
                read = {
                    val current = if (index >= 0) parent?.children?.getOrNull(index) as? XmlElement else element
                    current?.name ?: "false"
                },
                write = { next ->
                    if (parent == null || index !in parent.children.indices) return@XmlRef
                    val replacement = XmlElement(if (next.equals("true", true)) "true" else "false")
                    replacement.selfClosing = true
                    parent.children[index] = replacement
                },
                kind = XmlRef.Kind.PLIST_BOOL,
            )
        }

        /** Pairs each `<key>` with the value element that follows it. */
        private fun plistPairs(dict: XmlElement): List<Pair<XmlElement?, XmlElement>> {
            val elements = dict.elements()
            val out = mutableListOf<Pair<XmlElement?, XmlElement>>()
            var i = 0
            while (i < elements.size) {
                val current = elements[i]
                if (current.name.equals("key", true)) {
                    val value = elements.getOrNull(i + 1)
                    if (value != null) out += current to value
                    i += 2
                } else {
                    out += null to current
                    i += 1
                }
            }
            return out
        }

        // ---- shared helpers ---------------------------------------------------------

        private fun textRef(element: XmlElement): XmlRef = XmlRef(
            read = { element.textValue() },
            write = { next -> setText(element, next) },
            kind = XmlRef.Kind.TEXT,
        )

        /**
         * Writes into the first text/cdata child and blanks any other non-blank text run.
         * Children are replaced, never removed, so indexes captured by other refs stay valid.
         * An element that was empty gets one text child appended and loses its `/>`.
         */
        private fun setText(element: XmlElement, value: String) {
            val cdataIndex = element.children.indexOfFirst { it is XmlCData }
            if (cdataIndex >= 0) {
                element.children[cdataIndex] = XmlCData(value)
                blankOtherRuns(element, cdataIndex)
                return
            }
            val textIndex = element.children.indexOfFirst { it is XmlText }
            if (textIndex >= 0) {
                element.children[textIndex] = XmlText(XmlEntities.escapeText(value))
                blankOtherRuns(element, textIndex)
                return
            }
            element.children += XmlText(XmlEntities.escapeText(value))
            element.selfClosing = false
        }

        private fun blankOtherRuns(element: XmlElement, keepIndex: Int) {
            element.children.forEachIndexed { index, child ->
                if (index == keepIndex) return@forEachIndexed
                when (child) {
                    is XmlText -> if (child.raw.isNotBlank()) element.children[index] = XmlText("")
                    is XmlCData -> if (child.content.isNotEmpty()) element.children[index] = XmlCData("")
                    else -> Unit
                }
            }
        }

        private fun claim(candidate: FieldPath): FieldPath? {
            val encoded = candidate.encode()
            if (seen.add(encoded) && !refs.containsKey(encoded)) return candidate
            var suffix = 2
            while (suffix < 1000) {
                val segments = candidate.segments.toMutableList()
                val last = segments.removeAt(segments.size - 1)
                segments += when (last) {
                    is FieldPath.Segment.Key -> FieldPath.Segment.Key("${last.name}#$suffix")
                    is FieldPath.Segment.Index -> last
                }
                val attempt = FieldPath(segments)
                if (seen.add(attempt.encode()) && !refs.containsKey(attempt.encode())) return attempt
                suffix++
            }
            warnings += "path $encoded is ambiguous and was skipped"
            return null
        }

        private fun countSubtree(element: XmlElement): Long =
            1L + element.attributes.size + element.elements().sumOf { countSubtree(it) }
    }
}
