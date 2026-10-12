package dev.savant.data.session

import android.net.Uri
import dev.savant.data.storage.BackupStore
import dev.savant.data.storage.ImportIndex
import dev.savant.data.storage.ImportRecord
import dev.savant.data.storage.FileLoadResult
import dev.savant.data.storage.SaveFileRepository
import dev.savant.di.IoDispatcher
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.ImportedFile
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.parser.NodeLimits
import dev.savant.domain.parser.ParseHints
import dev.savant.domain.session.EditOutcome
import dev.savant.domain.session.EditorSession
import dev.savant.domain.session.ExportArtifact
import dev.savant.domain.session.OpenOutcome
import dev.savant.domain.session.SaveEditor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What happened when savant tried to open a file. */
sealed interface OpenReport {
    /** Fully editable: parsed, backed up, ready for the diff preview. */
    data class Opened(override val session: EditorSession, val backupId: String?) : OpenReport

    /** Parsed and readable, but savant will not write it. Carries the reason for the chat. */
    data class ReadOnly(override val session: EditorSession, val backupId: String?, val reason: String) : OpenReport

    /** Could not be opened at all. [lines] is exactly what the chat prints. */
    data class Rejected(val lines: List<String>) : OpenReport {
        override val session: EditorSession? get() = null
    }

    /** The session when one could be created, even a read-only one. */
    val session: EditorSession?
}

/**
 * The one open save file, shared by every screen.
 *
 * Chat edits it, Files restores into it, Finder searches it, and the top bar reads [session] for
 * the file name, format badge and backup dot. All parsing and rebuilding runs on the IO
 * dispatcher — a 50 MB save must never touch the main thread.
 */
@Singleton
class SessionStore @Inject constructor(
    private val files: SaveFileRepository,
    private val backups: BackupStore,
    private val imports: ImportIndex,
    private val editor: SaveEditor,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val _session = MutableStateFlow<EditorSession?>(null)
    val session: StateFlow<EditorSession?> = _session.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * A file shared into the app from another activity.
     *
     * The activity cannot reach the chat view model directly, so it drops the uri here and the
     * chat picks it up on its next collection — which also covers a share arriving while the app
     * was in the background.
     */
    private val _incomingShare = MutableStateFlow<Uri?>(null)
    val incomingShare: StateFlow<Uri?> = _incomingShare.asStateFlow()

    /**
     * A draft command another screen wants to hand to the chat input.
     *
     * The finder uses it: search there, finish the sentence in the chat, where the diff preview is.
     */
    private val _pendingDraft = MutableStateFlow<String?>(null)
    val pendingDraft: StateFlow<String?> = _pendingDraft.asStateFlow()

    fun submitDraft(text: String) {
        _pendingDraft.value = text
    }

    fun clearDraft() {
        _pendingDraft.value = null
    }

    fun submitShare(uri: Uri?) {
        if (uri != null) _incomingShare.value = uri
    }

    fun clearShare() {
        _incomingShare.value = null
    }

    /** The raw bytes of the open original, kept so a backup can be taken after a late permission. */
    private var originalBytes: ByteArray? = null

    suspend fun openUri(
        uri: Uri,
        origin: ImportedFile.Origin = ImportedFile.Origin.PICKER,
        hints: ParseHints = ParseHints(),
        preferred: SaveFormat? = null,
    ): OpenReport = withContext(io) {
        _busy.value = true
        try {
            when (val loaded = files.load(uri, NodeLimits.MAX_FILE_BYTES)) {
                is FileLoadResult.TooLarge -> OpenReport.Rejected(
                    listOf(
                        "that file is too large",
                        "${formatSize(loaded.sizeBytes)} — savant reads up to ${formatSize(loaded.limitBytes)}",
                    ),
                )

                is FileLoadResult.Failed -> OpenReport.Rejected(listOf(loaded.reason))

                is FileLoadResult.Loaded -> openBytes(
                    uri = uri.toString(),
                    name = loaded.name,
                    mimeType = loaded.mimeType,
                    bytes = loaded.bytes,
                    origin = origin,
                    hints = hints,
                    preferred = preferred,
                )
            }
        } finally {
            _busy.value = false
        }
    }

    suspend fun openBytes(
        uri: String,
        name: String,
        mimeType: String?,
        bytes: ByteArray,
        origin: ImportedFile.Origin = ImportedFile.Origin.PICKER,
        hints: ParseHints = ParseHints(),
        preferred: SaveFormat? = null,
    ): OpenReport = withContext(io) {
        val file = ImportedFile(
            uri = uri,
            name = name,
            sizeBytes = bytes.size.toLong(),
            mimeType = mimeType,
            importedAt = System.currentTimeMillis(),
            hexPreview = ImportedFile.hexPreview(bytes),
            origin = origin,
        )
        val outcome: OpenOutcome = editor.open(file, bytes, hints, preferred)
        val session = outcome.session
        if (session == null) {
            originalBytes = null
            _session.value = null
            return@withContext OpenReport.Rejected(outcome.failure?.chatLines ?: listOf("could not open that file"))
        }

        originalBytes = bytes
        val backupId = backupOriginal(session, bytes)
        val stored = session.withBackup(backupId)
        _session.value = stored
        imports.add(
            ImportRecord(
                uri = uri,
                name = name,
                formatBadge = stored.formatBadge,
                sizeBytes = bytes.size.toLong(),
                importedAt = file.importedAt,
                backupId = backupId,
                opened = true,
                note = outcome.failure?.headline ?: stored.tree.readOnlyReason ?: "",
            ),
        )

        val failure = outcome.failure
        if (failure != null || stored.readOnly) {
            OpenReport.ReadOnly(
                session = stored,
                backupId = backupId,
                reason = stored.tree.readOnlyReason ?: failure?.detail ?: "read only",
            )
        } else {
            OpenReport.Opened(stored, backupId)
        }
    }

    /** Backs up the untouched original. Never the edited bytes — that is what export is for. */
    private suspend fun backupOriginal(session: EditorSession, bytes: ByteArray): String? =
        runCatching {
            backups.save(session.file.name, session.formatBadge, bytes, note = "original import").id
        }.getOrNull()

    suspend fun apply(edits: List<FieldEdit>, note: String? = null): EditOutcome? = withContext(io) {
        val current = _session.value ?: return@withContext null
        val outcome = editor.apply(current, edits, note)
        _session.value = outcome.session
        outcome
    }

    suspend fun undo(): EditOutcome? = withContext(io) {
        val current = _session.value ?: return@withContext null
        val outcome = editor.undo(current)
        _session.value = outcome.session
        outcome
    }

    /** Rebuilds, re-parses and verifies. Returns null only when no file is open. */
    suspend fun rebuild(fileName: String? = null): ExportArtifact? = withContext(io) {
        val current = _session.value ?: return@withContext null
        val artifact = editor.rebuild(current, fileName)
        _session.value = current.withExport(artifact)
        artifact
    }

    /** One-tap restore: reopens the backed up original as a fresh session. */
    suspend fun restore(backupId: String): OpenReport? = withContext(io) {
        val bytes = backups.read(backupId) ?: return@withContext null
        openBytes(
            uri = "backup://$backupId",
            name = "restored ${UUID.randomUUID().toString().take(6)}",
            mimeType = null,
            bytes = bytes,
            origin = ImportedFile.Origin.BACKUP,
        )
    }

    suspend fun reopen(record: ImportRecord): OpenReport? =
        openUri(Uri.parse(record.uri), origin = ImportedFile.Origin.PICKER)

    fun publish(session: EditorSession) {
        _session.value = session
    }

    fun close() {
        _session.value = null
        originalBytes = null
    }

    val hasOriginal: Boolean get() = originalBytes != null

    private fun formatSize(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes b"
        val kb = bytes / 1024.0
        return if (kb < 1024) "%.1f kb".format(kb) else "%.1f mb".format(kb / 1024)
    }
}
