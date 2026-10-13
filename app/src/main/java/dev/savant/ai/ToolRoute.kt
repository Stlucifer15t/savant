package dev.savant.ai

import dev.savant.domain.chat.PendingCall
import dev.savant.domain.model.FieldEdit

/**
 * What one tool call produced, whichever executor ran it.
 *
 * Three kinds of tool reach a model in savant: the eight that edit a save file, the two that read the
 * web, and whatever a connector offers. They answer in different shapes, and the tool loop needs one,
 * so this is the shape — with `NeedsConfirmation` as the only way a call can stop and wait for the
 * user.
 */
sealed interface ToolRoute {

    data class Done(
        val text: String,
        val edits: List<FieldEdit> = emptyList(),
        val requestExport: Boolean = false,
    ) : ToolRoute

    data class NeedsConfirmation(val pending: List<PendingCall>) : ToolRoute
}
