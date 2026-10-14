package dev.savant.parsers.ini

import dev.savant.domain.model.FieldPath
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.Node
import dev.savant.domain.model.SaveFormat
import dev.savant.parsers.categorize.CategoryClassifier
import dev.savant.parsers.categorize.Humanizer

/**
 * Builds the field tree from an ini document.
 *
 * Mapping rules:
 * - a `[section]` becomes an object node at `section`; its entries are leaves at `section.key`
 * - entries before the first section are leaves at the root, `key`
 * - a section that appears twice is one node — its entries are merged, in file order — because that
 *   is how every ini reader treats it, and a warning says so
 * - a key that repeats inside a section keeps the first path and the repeats get `key#2`, `key#3`,
 *   so a multi-value key (`[Hotkeys] bind=...` three times) stays addressable
 * - section and key names are never split on `.` or `/`: `[/Script/Engine.GameUserSettings]` is one
 *   name, backtick quoted in the path, and the file's own structure is the structure
 *
 * The tree is capped at `maxNodes`; the document never is, so exporting a capped tree cannot drop
 * a line the user never saw.
 */
internal object IniTreeBuilder {

    class Result(
        val tree: FieldTree,
        val refs: Map<String, IniLine>,
        val totalValues: Int,
        val crypticKeys: Int,
    )

    fun build(
        model: IniDocumentModel,
        maxNodes: Int,
        extraWarnings: List<String> = emptyList(),
        readOnlyReason: String? = null,
    ): Result {
        val refs = HashMap<String, IniLine>()
        val seen = HashSet<String>()
        val warnings = mutableListOf<String>()
        val globals = mutableListOf<Node>()
        val sections = LinkedHashMap<String, MutableList<Node>>()
        val repeatedSections = LinkedHashSet<String>()

        var current: MutableList<Node>? = null
        var currentPath = FieldPath.ROOT
        var emitted = 0
        var truncated = false
        var totalValues = 0
        var crypticKeys = 0

        fun claim(candidate: FieldPath): FieldPath? {
            if (seen.add(candidate.encode())) return candidate
            var suffix = 2
            val segments = candidate.segments
            val last = segments.last() as FieldPath.Segment.Key
            while (suffix < 1000) {
                val attempt = FieldPath(segments.dropLast(1) + FieldPath.Segment.Key("${last.name}#$suffix"))
                if (seen.add(attempt.encode())) return attempt
                suffix++
            }
            warnings += "path ${candidate.encode()} repeats too often and was skipped"
            return null
        }

        for (line in model.lines) {
            when (line.kind) {
                IniLine.Kind.SECTION -> {
                    val name = line.sectionName ?: continue
                    val existing = sections[name]
                    if (existing != null) {
                        repeatedSections += name
                        current = existing
                    } else {
                        if (emitted < maxNodes) emitted++
                        current = mutableListOf<Node>().also { sections[name] = it }
                    }
                    currentPath = FieldPath.ROOT.child(name)
                }

                IniLine.Kind.ENTRY -> {
                    val key = line.key ?: continue
                    totalValues++
                    val path = claim(currentPath.child(key)) ?: continue
                    if (emitted >= maxNodes) {
                        if (!truncated) {
                            truncated = true
                            warnings += "showing the first $maxNodes values; everything else is written back untouched"
                        }
                        continue
                    }
                    emitted++
                    if (Humanizer.isCryptic(key)) crypticKeys++
                    val (type, value) = IniValues.infer(line.valueText, line.quoted)
                    val encoded = path.encode()
                    refs[encoded] = line
                    val node = Node(
                        path = encoded,
                        displayName = Humanizer.humanize(key),
                        type = type,
                        value = value,
                        category = CategoryClassifier.classify(encoded, key, value, type),
                        editable = true,
                        sourceKey = key,
                    )
                    (current ?: globals) += node
                }

                else -> Unit
            }
        }

        for (name in repeatedSections) warnings += "section [$name] appears more than once; its keys are shown together"

        val roots = globals + sections.map { (name, children) ->
            Node(
                path = FieldPath.ROOT.child(name).encode(),
                displayName = Humanizer.humanize(name),
                type = FieldType.OBJECT,
                value = null,
                editable = false,
                sourceKey = name,
                children = children,
            )
        }
        val tree = FieldTree(
            format = SaveFormat.INI,
            roots = roots,
            warnings = (warnings + extraWarnings).distinct(),
            truncated = truncated,
            readOnlyReason = readOnlyReason,
        )
        return Result(tree, refs, totalValues, crypticKeys)
    }
}
