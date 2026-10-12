package dev.savant.domain.session

import dev.savant.domain.model.AppliedEditBatch
import dev.savant.domain.model.Confidence
import dev.savant.domain.model.ImportedFile
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.parser.ParseLogLine
import dev.savant.domain.parser.SaveDocument
import dev.savant.domain.parser.SaveParser

/**
 * One open save file: the document, its detection result, the edit history and the last rebuild.
 *
 * Immutable — every change returns a copy, which keeps the UI state predictable and makes undo a
 * list operation instead of a mutation.
 */
data class EditorSession(
    val id: String,
    val file: ImportedFile,
    val parser: SaveParser,
    val document: SaveDocument,
    val detection: Confidence,
    val log: List<ParseLogLine> = emptyList(),
    val history: List<AppliedEditBatch> = emptyList(),
    val export: ExportArtifact? = null,
    val backupId: String? = null,
    val openedAt: Long = System.currentTimeMillis(),
) {
    val tree get() = document.tree
    val format: SaveFormat get() = document.tree.format
    val formatBadge: String get() = document.tree.format.badge
    val readOnly: Boolean get() = document.tree.readOnly
    val canUndo: Boolean get() = history.isNotEmpty()

    /** Every edit applied so far, oldest first. */
    val appliedEdits: List<dev.savant.domain.model.FieldEdit> get() = history.flatMap { it.edits }

    val pendingEditCount: Int get() = appliedEdits.size

    fun withTreeChanges(batch: AppliedEditBatch): EditorSession = copy(history = history + batch, export = null)

    fun withExport(artifact: ExportArtifact?): EditorSession = copy(export = artifact)

    fun withBackup(backupId: String?): EditorSession = copy(backupId = backupId)
}

/**
 * A rebuilt file, ready to hand to the share sheet or ACTION_CREATE_DOCUMENT.
 *
 * [report] is the re-parse verification: savant never offers an export it could not read back.
 */
data class ExportArtifact(
    val fileName: String,
    val bytes: ByteArray,
    val report: dev.savant.domain.parser.VerificationReport,
    val builtAt: Long = System.currentTimeMillis(),
) {
    val sizeBytes: Long get() = bytes.size.toLong()
    val usable: Boolean get() = report.ok

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExportArtifact) return false
        return fileName == other.fileName && bytes.contentEquals(other.bytes) && report == other.report
    }

    override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
}
