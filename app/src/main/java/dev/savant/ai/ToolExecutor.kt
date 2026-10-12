package dev.savant.ai

import dev.savant.domain.model.EditSource
import dev.savant.domain.model.EditValidator
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldPath
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.Node
import dev.savant.domain.model.asDouble
import dev.savant.domain.model.displayText
import dev.savant.domain.session.EditorSession
import dev.savant.domain.session.SaveEditor

/** What a tool call produced. */
data class ToolOutcome(
    val ok: Boolean,
    val text: String,
    val edits: List<FieldEdit> = emptyList(),
    val requestExport: Boolean = false,
) {
    val lines: List<String> get() = text.split("\n").filter { it.isNotBlank() }

    companion object {
        fun fail(text: String) = ToolOutcome(false, text)
        fun ok(text: String) = ToolOutcome(true, text)
    }
}

/**
 * Runs tool calls against the open session.
 *
 * This is the single execution path for both the on-device parser and any provider: a model can
 * only ever reach a file through these eight tools, and the value changing ones return *proposed*
 * edits. Nothing here writes bytes.
 */
class ToolExecutor(private val editor: SaveEditor) {

    fun execute(call: ToolCall, session: EditorSession?): ToolOutcome {
        if (session == null) {
            return ToolOutcome.fail("no save file is open — attach one first")
        }
        return when (call.name) {
            "list_fields" -> listFields(call, session.tree)
            "search_fields" -> searchFields(call, session.tree)
            "get_field" -> getField(call, session.tree)
            "explain_field" -> explainField(call, session.tree)
            "set_field" -> setField(call, session)
            "set_many" -> setMany(call, session)
            "undo" -> undo(session)
            "export" -> ToolOutcome(
                ok = !session.readOnly,
                text = if (session.readOnly) {
                    "this file is read-only: ${session.tree.readOnlyReason}"
                } else {
                    "rebuilding ${session.file.name}"
                },
                requestExport = !session.readOnly,
            )
            else -> ToolOutcome.fail("unknown tool: ${call.name}")
        }
    }

    // ---- read only tools ---------------------------------------------------------

    private fun listFields(call: ToolCall, tree: FieldTree): ToolOutcome {
        val limit = call.arg("limit")?.toIntOrNull() ?: 40
        val category = call.arg("category")?.let { label ->
            FieldCategory.entries.firstOrNull { it.label.equals(label, true) }
        }
        val groups = tree.groups().filter { category == null || it.category == category }
        if (groups.isEmpty()) {
            return ToolOutcome.ok(if (tree.readOnly) "no fields: ${tree.readOnlyReason}" else "no fields match")
        }
        val lines = mutableListOf(
            "${tree.editableCount} editable values in ${groups.size} group(s)" +
                if (tree.truncated) " (truncated view)" else "",
        )
        for (group in groups) {
            lines += "${group.category.label} (${group.count})"
            for (node in group.nodes.take(limit)) lines += row(node)
            if (group.count > limit) lines += "  ... ${group.count - limit} more"
        }
        return ToolOutcome.ok(lines.joinToString("\n"))
    }

    private fun searchFields(call: ToolCall, tree: FieldTree): ToolOutcome {
        val query = call.arg("query") ?: return ToolOutcome.fail("search_fields needs a query")
        val limit = call.arg("limit")?.toIntOrNull() ?: 25
        val matches = tree.search(query, limit)
        if (matches.isEmpty()) return ToolOutcome.ok("no field matches '$query'")
        val lines = mutableListOf("${matches.size} match(es) for '$query'")
        matches.forEach { lines += row(it) }
        return ToolOutcome.ok(lines.joinToString("\n"))
    }

    private fun getField(call: ToolCall, tree: FieldTree): ToolOutcome {
        val target = call.arg("path") ?: return ToolOutcome.fail("get_field needs a path")
        val matches = resolve(tree, target)
        val node = when {
            matches.isEmpty() -> return ToolOutcome.fail("no field matches '$target'")
            matches.size == 1 -> matches.first()
            else -> return ToolOutcome.ok(
                buildString {
                    appendLine("'${target}' matches ${matches.size} fields:")
                    matches.take(8).forEach { appendLine(row(it)) }
                }.trimEnd(),
            )
        }
        val siblings = tree.node(parentPath(node.path))?.children?.filter { it.path != node.path }?.take(6)
            ?: emptyList()
        return ToolOutcome.ok(
            buildString {
                appendLine(node.path)
                appendLine(pad("name") + node.label)
                appendLine(pad("type") + node.type.tag + if (node.fixedWidth) " (fixed width)" else "")
                appendLine(pad("value") + (node.value?.displayText() ?: "-"))
                appendLine(pad("category") + node.category.label)
                appendLine(pad("editable") + if (node.editable) "yes" else "no")
                node.effectiveMax?.let { appendLine(pad("max") + it.toLong().toString()) }
                node.byteOffset?.let { appendLine(pad("offset") + "0x" + it.toString(16)) }
                node.aiGuess?.let { appendLine(pad("ai") + "${it.displayName} (${it.confidenceLabel})") }
                if (siblings.isNotEmpty()) {
                    appendLine(pad("siblings") + siblings.joinToString(", ") { it.sourceKey })
                }
            }.trimEnd(),
        )
    }

    private fun explainField(call: ToolCall, tree: FieldTree): ToolOutcome {
        val target = call.arg("path") ?: return ToolOutcome.fail("explain_field needs a path")
        val matches = resolve(tree, target)
        if (matches.isEmpty()) return ToolOutcome.fail("no field matches '$target'")
        val node = matches.first()
        return ToolOutcome.ok(explain(node))
    }

    /** Deterministic reading of a field. The model can do better; this is the offline floor. */
    fun explain(node: Node): String {
        val key = node.sourceKey.lowercase()
        val value = node.value?.displayText() ?: "nothing"
        val guess = when {
            node.category == FieldCategory.CURRENCY -> "spendable money. games usually read it straight; some recompute a checksum over it"
            node.category == FieldCategory.STATS && key.contains("hp") -> "hit points. raising max and current together avoids a dead-on-load save"
            node.category == FieldCategory.STATS -> "a character statistic. values far outside the normal range can break level-up maths"
            node.category == FieldCategory.INVENTORY -> "an item slot or stack size. zero counts often mean the slot is empty"
            node.category == FieldCategory.FLAGS -> "a progress flag. flipping it can skip or repeat content"
            node.type == FieldType.STRING -> "free text, probably a name or a path"
            else -> "an unnamed numeric field"
        }
        return buildString {
            appendLine("${node.path} = $value (${node.type.tag}, ${node.category.label})")
            append("guess: $guess")
            if (node.aiGuess != null) append(" — renamed by the model, not by the file")
        }
    }

    // ---- value changing tools ----------------------------------------------------

    private fun setField(call: ToolCall, session: EditorSession): ToolOutcome {
        val target = call.arg("path") ?: return ToolOutcome.fail("set_field needs a path")
        val rawValue = call.arg("value") ?: return ToolOutcome.fail("set_field needs a value")
        val matches = resolve(session.tree, target).filter { it.editable }
        if (matches.isEmpty()) return ToolOutcome.fail("no editable field matches '$target'")
        if (matches.size > 1) {
            return ToolOutcome.ok(
                buildString {
                    appendLine("'$target' matches ${matches.size} fields — be specific, or say 'set all $target':")
                    matches.take(8).forEach { appendLine(row(it)) }
                }.trimEnd(),
            )
        }
        val node = matches.first()
        val (edit, problem) = editor.propose(node, rawValue, EditSource.AI_TOOL)
        if (edit == null) return ToolOutcome.fail("${node.label}: ${problem ?: "invalid value"}")
        return ToolOutcome(
            ok = true,
            text = "${node.path}: ${edit.oldText} -> ${edit.newText}",
            edits = listOf(edit),
        )
    }

    private fun setMany(call: ToolCall, session: EditorSession): ToolOutcome {
        val target = call.arg("target") ?: return ToolOutcome.fail("set_many needs a target")
        val op = (call.arg("op") ?: "set").lowercase()
        val rawValue = call.arg("value")
        val nodes = resolve(session.tree, target).filter { it.editable }
        if (nodes.isEmpty()) return ToolOutcome.fail("no editable field matches '$target'")

        val edits = mutableListOf<FieldEdit>()
        val skipped = mutableListOf<String>()
        for (node in nodes.take(MAX_BATCH)) {
            val value = valueFor(node, op, rawValue)
            if (value == null) {
                skipped += "${node.label}: $op does not apply to ${node.type.tag}"
                continue
            }
            val (edit, problem) = editor.proposeValue(node, value, EditSource.AI_TOOL)
            if (edit == null) skipped += "${node.label}: ${problem ?: "invalid"}" else edits += edit
        }

        val head = when {
            edits.isEmpty() -> "nothing to change in ${nodes.size} field(s)"
            else -> "${edits.size} field(s) would change" + if (nodes.size > MAX_BATCH) " (capped at $MAX_BATCH)" else ""
        }
        val body = edits.take(12).joinToString("\n") { "  ${it.path}: ${it.oldText} -> ${it.newText}" }
        val tail = buildList {
            if (edits.size > 12) add("  ... ${edits.size - 12} more")
            addAll(skipped.take(4).map { "  skipped $it" })
            if (skipped.size > 4) add("  ... ${skipped.size - 4} more skipped")
        }
        return ToolOutcome(
            ok = edits.isNotEmpty(),
            text = (listOf(head) + body.split("\n") + tail).filter { it.isNotBlank() }.joinToString("\n"),
            edits = edits,
        )
    }

    private fun undo(session: EditorSession): ToolOutcome {
        val last = session.history.lastOrNull()
            ?: return ToolOutcome.fail("nothing to undo yet")
        val tree = session.tree
        val edits = last.edits.mapNotNull { edit ->
            val node = tree.node(edit.path) ?: return@mapNotNull null
            FieldEdit.of(node, edit.oldValue, EditSource.UNDO).takeUnless { it.isNoOp }
        }
        if (edits.isEmpty()) return ToolOutcome.fail("the last batch changed nothing")
        val body = edits.take(12).joinToString("\n") { "  ${it.path}: ${it.oldText} -> ${it.newText}" }
        return ToolOutcome(
            ok = true,
            text = "undo ${last.edits.size} edit(s) from ${last.note ?: "the last batch"}\n$body",
            edits = edits,
        )
    }

    // ---- helpers -----------------------------------------------------------------

    private fun valueFor(node: Node, op: String, rawValue: String?): FieldValue? = when (op) {
        "max" -> EditValidator.quickValue(node, EditValidator.QuickValue.MAX)
        "min" -> node.effectiveMin?.let { FieldValue.Int64(it.toLong()) } ?: EditValidator.quickValue(node, EditValidator.QuickValue.ZERO)
        "zero" -> EditValidator.quickValue(node, EditValidator.QuickValue.ZERO)
        "double" -> EditValidator.quickValue(node, EditValidator.QuickValue.DOUBLE)
        "half" -> EditValidator.quickValue(node, EditValidator.QuickValue.HALF)
        "set" -> rawValue?.let { EditValidator.coerce(node, it) }
        "add", "sub" -> {
            val delta = rawValue?.trim()?.let { FieldValue.parse(it).asDouble() } ?: return null
            val current = node.value?.asDouble() ?: return null
            val next = if (op == "add") current + delta else current - delta
            if (node.type.isInteger || node.value is FieldValue.Int64) {
                FieldValue.Int64(next.toLong(), unsigned = node.type.signed == false)
            } else {
                FieldValue.Real(next)
            }
        }
        else -> null
    }

    /**
     * Resolves what the user or the model meant: an exact path, a category ("all stats"),
     * "everything", or a fuzzy search.
     */
    fun resolve(tree: FieldTree, target: String): List<Node> {
        val raw = target.trim()
        if (raw.isEmpty()) return emptyList()
        val lower = raw.lowercase()

        tree.node(raw)?.let { node -> if (node.isLeaf) return listOf(node) }

        val bare = lower.removePrefix("all ").removePrefix("every ").removePrefix("each ").trim()
        if (lower in EVERYTHING) return tree.editableNodes
        FieldCategory.entries.firstOrNull { it.label == bare || it.label == lower }?.let { category ->
            return tree.editableNodes.filter { it.category == category }
        }
        if (bare == "fields" || bare == "values") return tree.editableNodes

        val exact = tree.all.filter { it.isLeaf && FieldTree.normalize(it.sourceKey) == FieldTree.normalize(raw) }
        if (exact.isNotEmpty()) return exact

        return tree.search(raw).filter { it.isLeaf }
    }

    private fun row(node: Node): String {
        val value = node.value?.displayText() ?: "-"
        val guess = if (node.aiGuess != null) " (${node.aiGuess.confidenceLabel})" else ""
        return "  ${node.path} = $value  [${node.type.tag}, ${node.category.label}]$guess"
    }

    private fun pad(label: String): String = label.padEnd(10)

    private fun parentPath(path: String): String = FieldPath.parse(path).parent().encode()

    companion object {
        const val MAX_BATCH = 2000
        private val EVERYTHING = setOf("all", "everything", "every field", "all fields", "the lot")
    }
}
