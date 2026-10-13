package dev.savant.ai

import dev.savant.domain.chat.PendingCall
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.session.EditorSession

/**
 * What the chat asks, and what it gets back.
 *
 * Two engines implement this: [LocalAssistant] (on device, deterministic, no key needed) and the
 * provider backed engine added in build step 6. Both answer with either text or *proposed* edits —
 * an engine can never write a file.
 */
interface AssistantEngine {

    val id: String

    val label: String

    /** True when answers come from a network provider; drives the privacy copy in settings. */
    val isRemote: Boolean

    /** The tool list this engine is given. Merged with MCP tools for remote engines. */
    fun tools(): List<ToolSpec> = ToolCatalog.ALL

    suspend fun respond(input: AssistantInput): AssistantReply
}

data class AssistantInput(
    val text: String,
    val session: EditorSession?,
    val history: List<LlmMessage> = emptyList(),
    /**
     * Calls the user has already allowed, by [PendingCall.key].
     *
     * Empty on a fresh turn. When a web or connector call needs an ok, the turn ends with
     * [AssistantReply.Confirmation] and the chat sends the same text again with the approved keys, so
     * an approval covers one call and not the session.
     */
    val approved: Set<String> = emptySet(),
)

sealed interface AssistantReply {

    val lines: List<String>

    /** Plain answer, nothing to confirm. */
    data class Text(override val lines: List<String>) : AssistantReply

    /** Edits waiting for the diff preview. [requestExport] also rebuilds the file. */
    data class Proposal(
        override val lines: List<String>,
        val edits: List<FieldEdit>,
        val requestExport: Boolean = false,
    ) : AssistantReply

    /** Something the user has to fix: no file, unknown field, invalid value. */
    data class Failure(override val lines: List<String>) : AssistantReply

    /**
     * Nothing was sent, and nothing will be until the user says so.
     *
     * Only web and connector calls land here. A tool that edits a save file does not need this gate,
     * because the diff preview is a stronger one: it shows the old and new value before anything is
     * written.
     */
    data class Confirmation(
        override val lines: List<String>,
        val calls: List<PendingCall>,
    ) : AssistantReply
}
