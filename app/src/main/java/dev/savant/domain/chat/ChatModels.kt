package dev.savant.domain.chat

import dev.savant.domain.model.Confidence
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldGroup
import dev.savant.domain.model.ImportedFile
import dev.savant.domain.parser.ParseLogLine
import dev.savant.domain.session.ExportArtifact

/** Who said it. Drives the accent line versus the compact bubble. */
enum class ChatRole { USER, ASSISTANT }

/**
 * One row in the chat stream.
 *
 * Kept in the domain package: entries carry only domain types, which is what lets the history
 * repository persist them without the data layer depending on the ui layer.
 *
 * The stream is heterogeneous on purpose: savant answers with a file chip, a parse log, field
 * groups, a diff and an export card, not with wall-of-text bubbles. Order in the list is the order
 * on screen; nothing is grouped by sender.
 */
sealed interface ChatEntry {
    val id: String
    val atMillis: Long
}

data class TextEntry(
    override val id: String,
    val role: ChatRole,
    val lines: List<String>,
    override val atMillis: Long = System.currentTimeMillis(),
    /** Failure copy is muted-red-free: same accent, different first word. Kept for a11y. */
    val isError: Boolean = false,
) : ChatEntry {
    val text: String get() = lines.joinToString("\n")
}

/** The attached file, as it entered: name, size, format badge, detection confidence, hex pattern. */
data class FileEntry(
    override val id: String,
    val file: ImportedFile,
    val formatBadge: String,
    val confidence: Confidence?,
    override val atMillis: Long = System.currentTimeMillis(),
) : ChatEntry

/** The staggered monospace parse log. */
data class ParseEntry(
    override val id: String,
    val log: List<ParseLogLine>,
    val done: Boolean,
    override val atMillis: Long = System.currentTimeMillis(),
) : ChatEntry

/** "found N editable values" plus the group cards. */
data class FieldsEntry(
    override val id: String,
    val headline: String,
    val groups: List<FieldGroup>,
    val totalCount: Int,
    val editableCount: Int,
    val collapsed: Set<String> = emptySet(),
    /** `currency 3 · stats 12` — what a restored card shows instead of live rows. */
    val summaryLines: List<String> = emptyList(),
    /** True when the entry came out of the database: the field list itself is not stored. */
    val restored: Boolean = false,
    override val atMillis: Long = System.currentTimeMillis(),
) : ChatEntry

/**
 * A diff waiting for confirmation, or one already resolved.
 *
 * Nothing is written until the user presses apply; [status] records what happened so the row stays
 * in the transcript instead of vanishing.
 */
enum class DiffStatus { PENDING, APPLIED, CANCELLED, PARTIAL }

data class DiffEntry(
    override val id: String,
    val edits: List<FieldEdit>,
    val status: DiffStatus,
    val note: String?,
    /** Rows the user reverted inside the preview; they are dropped from the applied batch. */
    val dropped: Set<String> = emptySet(),
    val appliedCount: Int = 0,
    override val atMillis: Long = System.currentTimeMillis(),
) : ChatEntry {
    val liveEdits: List<FieldEdit> get() = edits.filterNot { it.path in dropped }
    val pending: Boolean get() = status == DiffStatus.PENDING
}

/** The rebuilt file, with its verification verdict and the two ways out. */
data class ExportEntry(
    override val id: String,
    val artifact: ExportArtifact,
    val shared: Boolean = false,
    val savedTo: String? = null,
    /** True when the card came out of the database: the rebuilt bytes are gone, so export is off. */
    val restored: Boolean = false,
    override val atMillis: Long = System.currentTimeMillis(),
) : ChatEntry

/** Read-only notice: parsed, but savant will not write this one. */
data class NoticeEntry(
    override val id: String,
    val title: String,
    val lines: List<String>,
    override val atMillis: Long = System.currentTimeMillis(),
) : ChatEntry
