package dev.savant.ai

import dev.savant.domain.session.EditorSession
import dev.savant.domain.model.formatBytes

/**
 * What the user allowed savant to send.
 *
 * These are the privacy switches from settings, resolved once per turn: [sendFieldValues] decides
 * whether the model sees numbers or only paths and types, and a save above
 * [dev.savant.domain.parser.NodeLimits.TRIM_TO_AI_BYTES] is trimmed regardless, because that rule is
 * about size and not about trust.
 */
data class PromptPolicy(
    val sendFieldValues: Boolean = true,
    val maxFields: Int = 400,
    val mentionFileName: Boolean = true,
)

/**
 * The system prompt for a provider turn.
 *
 * Written to be boring on purpose. The model's job here is narrow — read the file through tools,
 * propose edits with exact paths, never invent anything — and every line of this exists because a
 * looser version produced a made up path or a rounded number.
 */
object SystemPrompt {

    fun forSession(session: EditorSession?, policy: PromptPolicy): String = buildString {
        append(ROLE)
        append("\n\nthe file\n")
        append(fileBlock(session, policy))
        append("\n\neditable values\n")
        append(valuesBlock(session, policy))
    }

    private fun fileBlock(session: EditorSession?, policy: PromptPolicy): String {
        if (session == null) return "none. the user has not attached a save file; ask them to attach one."
        val file = session.file
        return buildString {
            if (policy.mentionFileName) append("name: ${file.name}\n")
            append("format: ${session.formatBadge}\n")
            append("size: ${formatBytes(file.sizeBytes)}\n")
            append("values: ${session.tree.totalCount} total, ${session.tree.editableCount} editable\n")
            if (session.readOnly) {
                append("read only: ${session.tree.readOnlyReason ?: "savant cannot rebuild this file safely"}\n")
                append("do not propose edits. explain what is readable and stop.\n")
            }
            if (session.appliedEdits.isNotEmpty()) {
                append("already proposed in this session: ${session.appliedEdits.size} edit(s)\n")
            }
            session.tree.warnings.take(3).forEach { append("warning: $it\n") }
        }.trimEnd()
    }

    private fun valuesBlock(session: EditorSession?, policy: PromptPolicy): String {
        if (session == null) return "none"
        val tree = session.tree
        if (tree.editableCount == 0) return "none — this file has no editable values"
        val includeValues = policy.sendFieldValues && !AiPayload.mustTrim(session.file.sizeBytes)
        val note = when {
            !policy.sendFieldValues -> "(values withheld by the user's privacy setting — ask before assuming one)"
            AiPayload.mustTrim(session.file.sizeBytes) ->
                "(file is above ${formatBytes(dev.savant.domain.parser.NodeLimits.TRIM_TO_AI_BYTES)}: paths and types only)"
            else -> ""
        }
        return AiPayload.fieldSummary(tree, includeValues = includeValues, maxFields = policy.maxFields) +
            if (note.isEmpty()) "" else "\n$note"
    }

    private val ROLE = """
        savant edits game save files. you read the file through tools and propose edits. you never write bytes.

        rules
        - only use paths that list_fields or search_fields returned. never invent a path, a key or an offset.
        - every value change goes through set_field or set_many. those return a proposal, and the user confirms it in a diff preview before anything is written to a file.
        - if the file is read only, say so and stop.
        - if a value you need is not shown, ask for it instead of guessing.
        - numbers are exact: no rounding, no units, no invented ranges.
        - short factual lines. no preamble, no emoji, no restating the question.
    """.trimIndent()
}
