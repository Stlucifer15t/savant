package dev.savant.ai

import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.Node
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.displayText
import dev.savant.domain.parser.NodeLimits
import dev.savant.parsers.categorize.Humanizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The offline assistant: a deterministic command parser in front of the shared [ToolExecutor].
 *
 * It exists so savant is useful before any API key is entered, and so the tool layer has a second
 * caller that proves the provider path is not special. Answers are short, lowercase and factual.
 */
class LocalAssistant(private val executor: ToolExecutor) : AssistantEngine {

    override val id = "local"
    override val label = "on-device parser"
    override val isRemote = false

    override suspend fun respond(input: AssistantInput): AssistantReply = withContext(Dispatchers.Default) {
        val text = input.text.trim()
        if (text.isEmpty()) {
            return@withContext AssistantReply.Text(listOf("type a command, or tap a value in the list to edit it"))
        }

        val call = CommandParser.parse(text)
        if (call == null) {
            return@withContext AssistantReply.Text(helpLines(text, input.session?.tree))
        }

        val outcome = executor.execute(call, input.session)
        when {
            !outcome.ok -> AssistantReply.Failure(outcome.lines)
            outcome.edits.isNotEmpty() -> AssistantReply.Proposal(outcome.lines, outcome.edits, outcome.requestExport)
            outcome.requestExport -> AssistantReply.Proposal(outcome.lines, emptyList(), true)
            else -> AssistantReply.Text(outcome.lines)
        }
    }

    private fun helpLines(text: String, tree: FieldTree?): List<String> {
        val lower = text.lowercase()
        if (tree == null) {
            return listOf(
                "no save file is open",
                "attach one with the clip icon, or share a file into savant",
            )
        }
        if (lower.contains("what can you do") || lower.contains("how do i")) {
            return commandHelp()
        }
        // Not a command: try a search, which is usually what people mean.
        val matches = tree.search(text, 5)
        if (matches.isNotEmpty()) {
            return buildList {
                add("no command in \"$text\" — did you mean one of these?")
                matches.forEach { node -> add("  ${node.path} = ${node.value?.displayText() ?: '-'}") }
                add("try: set ${matches.first().sourceKey} to 999")
            }
        }
        return buildList {
            add("that is not a command i know yet")
            addAll(commandHelp())
        }
    }

    private fun commandHelp(): List<String> = listOf(
        "commands:",
        "  set gold to 999999",
        "  max all stats",
        "  add 10 to strength",
        "  search health",
        "  what is v_0042",
        "  undo",
        "  export",
    )

    /** Reported in the chat after a parse: how much of the file is guesswork. */
    fun crypticNote(tree: FieldTree): String? {
        val cryptic = tree.editableNodes.count { Humanizer.isCryptic(it.sourceKey) }
        if (cryptic == 0) return null
        return "$cryptic key(s) look cryptic — a provider can rename them (step 6), the guesses stay marked"
    }
}

/**
 * What may ever be sent to a provider or a web tool.
 *
 * Rules, enforced here rather than in each client:
 * - a save larger than [dev.savant.domain.parser.NodeLimits.TRIM_TO_AI_BYTES] is trimmed to field
 *   names and types; values never go out
 * - raw file bytes never go out
 * - keys and tokens are redacted to their last four characters in anything that is logged
 */
object AiPayload {

    fun mustTrim(sizeBytes: Long): Boolean =
        sizeBytes > NodeLimits.TRIM_TO_AI_BYTES

    /**
     * A trimmed field listing: paths and types only, optionally values when the user allowed it
     * and the file is small.
     */
    fun fieldSummary(
        tree: FieldTree,
        includeValues: Boolean,
        maxFields: Int = 400,
    ): String {
        val nodes = tree.editableNodes.take(maxFields)
        val lines = nodes.map { node ->
            val value = if (includeValues && !holdsLongText(node)) " = ${node.value?.displayText()}" else ""
            "${node.path}: ${node.type.tag}$value"
        }
        val header = buildString {
            append("${tree.format.badge} save, ${tree.editableCount} editable values")
            if (tree.truncated) append(", view truncated")
            if (!includeValues) append(", values withheld")
            if (nodes.size < tree.editableCount) append(", showing ${nodes.size}")
        }
        return listOf(header).plus(lines).joinToString("\n")
    }

    private fun holdsLongText(node: Node): Boolean {
        val text = (node.value as? FieldValue.Text)?.value ?: return false
        return text.length > LONG_TEXT_CHARS
    }

    private const val LONG_TEXT_CHARS = 256

    /** `sk-ant-api03-...-abcd` -> `****abcd`. Used by every log and the network activity list. */
    fun redactKey(key: String?): String {
        if (key.isNullOrBlank()) return ""
        val tail = key.takeLast(4)
        return if (key.length <= 4) "****" else "****$tail"
    }

    /** Scrubs anything that looks like a bearer token or key from a message before it is logged. */
    fun redactText(text: String): String = text
        .replace(Regex("(?i)(authorization\\s*[:=]\\s*)\\S+"), "$1****")
        .replace(Regex("(?i)(api[-_]?key\\s*[\"']?\\s*[:=]\\s*[\"']?)[A-Za-z0-9_\\-.]+"), "$1****")
        .replace(Regex("(?i)(x-api-key\\s*[:=]\\s*)\\S+"), "$1****")
        .replace(Regex("sk-[A-Za-z0-9_\\-]{8,}")) { match -> "****" + match.value.takeLast(4) }
}
