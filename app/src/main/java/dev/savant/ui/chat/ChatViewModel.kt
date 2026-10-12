package dev.savant.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.ai.AssistantEngine
import dev.savant.ai.AssistantInput
import dev.savant.ai.AssistantReply
import dev.savant.data.session.OpenReport
import dev.savant.data.session.SessionStore
import dev.savant.data.settings.SavantSettings
import dev.savant.data.settings.SettingsRepository
import dev.savant.data.storage.SaveFileRepository
import dev.savant.domain.model.EditSource
import dev.savant.domain.model.EditValidator
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.ImportedFile
import dev.savant.domain.model.Node
import dev.savant.domain.session.EditorSession
import dev.savant.domain.session.ExportArtifact
import dev.savant.domain.session.SaveEditor
import dev.savant.parsers.categorize.Humanizer
import dev.savant.ui.chat.model.ChatEntry
import dev.savant.ui.chat.model.ChatRole
import dev.savant.ui.chat.model.DiffEntry
import dev.savant.ui.chat.model.DiffStatus
import dev.savant.ui.chat.model.ExportEntry
import dev.savant.ui.chat.model.FieldsEntry
import dev.savant.ui.chat.model.FileEntry
import dev.savant.ui.chat.model.NoticeEntry
import dev.savant.ui.chat.model.ParseEntry
import dev.savant.ui.chat.model.TextEntry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Everything the chat screen renders. One immutable snapshot, replaced on every change. */
data class ChatUiState(
    val entries: List<ChatEntry> = emptyList(),
    val input: String = "",
    val busy: Boolean = false,
    val fileName: String? = null,
    val formatBadge: String? = null,
    val backedUp: Boolean = false,
    val readOnly: Boolean = false,
    val readOnlyReason: String? = null,
    val pendingDiffId: String? = null,
    val pendingEditCount: Int = 0,
    val export: ExportArtifact? = null,
    val canUndo: Boolean = false,
    /** Paths already changed in this session: their values render in the accent colour. */
    val changedPaths: Set<String> = emptySet(),
    val engineLabel: String = "on device",
    val reducedMotion: Boolean = false,
    val showWarning: Boolean = false,
) {
    val isEmpty: Boolean get() = entries.none { it is FileEntry || it is FieldsEntry }
    val hasFile: Boolean get() = fileName != null
}

/** Side effects the activity has to run: share sheets and document pickers. */
sealed interface ChatEvent {
    data class Share(val uri: Uri, val fileName: String) : ChatEvent
    data class CreateDocument(val fileName: String, val mimeType: String) : ChatEvent
}

/**
 * The chat is the workbench: attach, read what was found, propose edits, confirm a diff, export.
 *
 * Two rules hold everywhere in this class. Nothing is written until a diff is confirmed, and
 * nothing is written to the user's original file — a rebuild always produces a new one that is
 * re-parsed and verified first.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessions: SessionStore,
    private val editor: SaveEditor,
    private val assistant: AssistantEngine,
    private val settings: SettingsRepository,
    private val files: SaveFileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val events = Channel<ChatEvent>(Channel.BUFFERED)
    val chatEvents = events.receiveAsFlow()

    /** Work this view model started, and work the session store started, kept apart on purpose. */
    private val localBusy = MutableStateFlow(false)
    private val ioBusy = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            sessions.session.collect { session -> publishSession(session) }
        }
        viewModelScope.launch {
            sessions.busy.collect { ioBusy.value = it }
        }
        viewModelScope.launch {
            combine(localBusy, ioBusy) { a, b -> a || b }.collect { busy -> mutate { it.copy(busy = busy) } }
        }
        viewModelScope.launch {
            settings.settings.collect { prefs ->
                mutate {
                    it.copy(
                        reducedMotion = prefs.reducedMotion,
                        showWarning = !prefs.warningAccepted,
                    )
                }
            }
        }
        viewModelScope.launch {
            sessions.pendingDraft.collect { draft ->
                if (draft != null) {
                    sessions.clearDraft()
                    mutate { it.copy(input = draft) }
                }
            }
        }
        viewModelScope.launch {
            sessions.incomingShare.collect { uri ->
                if (uri != null) {
                    sessions.clearShare()
                    openFile(uri, ImportedFile.Origin.SHARE)
                }
            }
        }
        mutate { it.copy(engineLabel = assistant.label) }
    }

    // ---- input ------------------------------------------------------------------

    fun onInputChanged(value: String) = mutate { it.copy(input = value) }

    fun onSend() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.busy) return
        mutate { it.copy(input = "") }
        setBusy(true)
        append(TextEntry(newId(), ChatRole.USER, listOf(text)))
        viewModelScope.launch {
            try {
                handle(text)
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun handle(text: String) {
        val session = sessions.session.value
        when (val reply = assistant.respond(AssistantInput(text, session))) {
            is AssistantReply.Text -> assistantText(reply.lines)

            is AssistantReply.Failure -> assistantText(reply.lines, isError = true)

            is AssistantReply.Proposal -> when {
                reply.edits.isNotEmpty() -> {
                    assistantText(reply.lines)
                    proposeDiff(reply.edits, note = text)
                }

                reply.requestExport -> rebuild(note = text)

                else -> assistantText(reply.lines)
            }
        }
    }

    // ---- attach / open ----------------------------------------------------------

    fun onAttach(uri: Uri) = openFile(uri, ImportedFile.Origin.PICKER)

    fun onSharedFile(uri: Uri) = openFile(uri, ImportedFile.Origin.SHARE)

    private fun openFile(uri: Uri, origin: ImportedFile.Origin) {
        setBusy(true)
        viewModelScope.launch {
            val report = sessions.openUri(uri, origin)
            render(report)
            setBusy(false)
        }
    }

    /** Reopens a row from the Files screen, or a restored backup. */
    fun openAgain(uri: String) {
        setBusy(true)
        viewModelScope.launch {
            val report = sessions.openUri(Uri.parse(uri), ImportedFile.Origin.PICKER)
            render(report)
            setBusy(false)
        }
    }

    private fun render(report: OpenReport) {
        when (report) {
            is OpenReport.Rejected -> assistantText(report.lines, isError = true)

            is OpenReport.Opened, is OpenReport.ReadOnly -> {
                val session = report.session ?: return
                append(
                    FileEntry(
                        id = newId(),
                        file = session.file,
                        formatBadge = session.formatBadge,
                        confidence = session.detection,
                    ),
                )
                if (session.log.isNotEmpty()) {
                    append(ParseEntry(newId(), session.log, done = true))
                }
                if (report is OpenReport.ReadOnly) {
                    append(
                        NoticeEntry(
                            id = newId(),
                            title = "opened read only",
                            lines = buildList {
                                add(report.reason)
                                add("values are visible, writing is blocked until a parser can rebuild this format")
                            },
                        ),
                    )
                }
                appendFieldsEntry(session.tree)
                crypticNote(session.tree)?.let { assistantText(listOf(it)) }
            }
        }
    }

    private fun appendFieldsEntry(tree: FieldTree) {
        append(
            FieldsEntry(
                id = newId(),
                headline = "found ${tree.editableCount} editable value${if (tree.editableCount == 1) "" else "s"}",
                groups = tree.groups(),
                totalCount = tree.totalCount,
                editableCount = tree.editableCount,
            ),
        )
        tree.warnings.take(MAX_WARNINGS).forEach { warning ->
            assistantText(listOf("warning: $warning"), isError = true)
        }
    }

    /** Machine-generated names get an offer, not a lecture. */
    private fun crypticNote(tree: FieldTree): String? {
        val cryptic = tree.editableNodes.filter { Humanizer.isCryptic(it.sourceKey ?: it.displayName) }
        if (cryptic.isEmpty()) return null
        val sample = cryptic.first().sourceKey ?: cryptic.first().displayName
        return "${cryptic.size} field${if (cryptic.size == 1) "" else "s"} look machine generated " +
            "(for example `$sample`) — ask \"explain $sample\" and savant will guess what it holds"
    }

    // ---- editing ----------------------------------------------------------------

    /** Tap a value in a field row: validated, then straight into the diff preview. */
    fun onInlineEdit(node: Node, raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val (edit, problem) = editor.propose(node, trimmed, EditSource.USER_INLINE)
            if (edit == null) {
                assistantText(listOf(problem ?: "that value does not fit ${node.path}"), isError = true)
                return@launch
            }
            proposeDiff(listOf(edit), note = "tap on ${node.label}")
        }
    }

    fun onQuickValue(node: Node, quick: EditValidator.QuickValue) {
        viewModelScope.launch {
            val value = EditValidator.quickValue(node, quick)
            if (value == null) {
                assistantText(listOf("${quick.label} does not apply to ${node.type.tag}"), isError = true)
                return@launch
            }
            val (edit, problem) = editor.proposeValue(node, value, EditSource.USER_INLINE)
            if (edit == null) {
                assistantText(listOf(problem ?: "no change"), isError = true)
                return@launch
            }
            proposeDiff(listOf(edit), note = "${quick.label} on ${node.label}")
        }
    }

    private fun proposeDiff(edits: List<FieldEdit>, note: String?) {
        if (edits.isEmpty()) return
        val session = sessions.session.value
        if (session != null && session.readOnly) {
            assistantText(listOf(session.tree.readOnlyReason ?: "this file is read only"), isError = true)
            return
        }
        val id = newId()
        append(DiffEntry(id = id, edits = edits, status = DiffStatus.PENDING, note = note))
        mutate { it.copy(pendingDiffId = id, pendingEditCount = edits.size) }
    }

    fun onRevertRow(diffId: String, path: String) {
        replaceEntry(diffId) { entry ->
            (entry as DiffEntry).copy(dropped = entry.dropped + path)
        }
        mutate { state ->
            val entry = state.entries.filterIsInstance<DiffEntry>().firstOrNull { it.id == diffId }
            state.copy(pendingEditCount = entry?.liveEdits?.size ?: 0)
        }
    }

    fun onCancelDiff(diffId: String) {
        replaceEntry(diffId) { entry -> (entry as DiffEntry).copy(status = DiffStatus.CANCELLED) }
        clearPending()
    }

    fun onConfirmDiff(diffId: String) {
        val entry = _state.value.entries.filterIsInstance<DiffEntry>().firstOrNull { it.id == diffId } ?: return
        val edits = entry.liveEdits
        if (edits.isEmpty()) {
            onCancelDiff(diffId)
            return
        }
        setBusy(true)
        viewModelScope.launch {
            try {
                val outcome = sessions.apply(edits, note = entry.note)
                if (outcome == null) {
                    assistantText(listOf("no save file is open"), isError = true)
                    return@launch
                }
                val applied = outcome.applied.size
                val status = when {
                    applied == 0 -> DiffStatus.CANCELLED
                    applied < edits.size -> DiffStatus.PARTIAL
                    else -> DiffStatus.APPLIED
                }
                replaceEntry(diffId) { current -> (current as DiffEntry).copy(status = status, appliedCount = applied) }
                clearPending()
                sessions.session.value?.let { session -> refreshFieldEntries(session.tree) }
                assistantText(outcome.lines)
                if (applied > 0) rebuild(note = entry.note)
            } finally {
                setBusy(false)
            }
        }
    }

    fun onUndo() {
        setBusy(true)
        viewModelScope.launch {
            try {
                val outcome = sessions.undo() ?: return@launch
                if (outcome.applied.isEmpty()) {
                    assistantText(listOf("nothing to undo"))
                    return@launch
                }
                sessions.session.value?.let { refreshFieldEntries(it.tree) }
                assistantText(listOf("reverted ${outcome.applied.size} edit${if (outcome.applied.size == 1) "" else "s"}"))
                mutate { it.copy(export = null) }
            } finally {
                setBusy(false)
            }
        }
    }

    // ---- export -----------------------------------------------------------------

    private suspend fun rebuild(note: String?) {
        val artifact = sessions.rebuild()
        if (artifact == null) {
            assistantText(listOf("no save file is open"), isError = true)
            return
        }
        append(
            ExportEntry(
                id = newId(),
                artifact = artifact,
            ),
        )
        assistantText(
            buildList {
                add(artifact.report.summary)
                if (!artifact.usable) add("export is blocked until the rebuilt file re-parses cleanly")
                if (note != null) add("from: $note")
            },
            isError = !artifact.usable,
        )
        mutate { it.copy(export = artifact) }
    }

    fun onShareExport() {
        val artifact = _state.value.export ?: return
        viewModelScope.launch {
            val uri = files.stageForShare(artifact.fileName, artifact.bytes)
            if (uri == null) {
                assistantText(listOf("could not stage that file for sharing"), isError = true)
                return@launch
            }
            markExport(artifact) { it.copy(shared = true) }
            events.send(ChatEvent.Share(uri, artifact.fileName))
        }
    }

    fun onSaveExport() {
        val artifact = _state.value.export ?: return
        viewModelScope.launch {
            events.send(
                ChatEvent.CreateDocument(
                    fileName = artifact.fileName,
                    mimeType = mimeFor(artifact.fileName),
                ),
            )
        }
    }

    /** Called by the screen once ACTION_CREATE_DOCUMENT returns. */
    fun onSaveResult(uri: Uri?) {
        val artifact = _state.value.export ?: return
        if (uri == null) return
        viewModelScope.launch {
            val written = files.writeTo(uri, artifact.bytes)
            if (written) {
                markExport(artifact) { it.copy(savedTo = files.displayName(uri)) }
                assistantText(listOf("wrote ${artifact.fileName} (${artifact.bytes.size} bytes)"))
            } else {
                assistantText(listOf("could not write to that location"), isError = true)
            }
        }
    }

    private fun markExport(artifact: ExportArtifact, transform: (ExportEntry) -> ExportEntry) {
        val entry = _state.value.entries.filterIsInstance<ExportEntry>().lastOrNull { it.artifact == artifact }
            ?: return
        replaceEntry(entry.id) { transform(it as ExportEntry) }
    }

    private fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "xml", "plist" -> "application/xml"
        else -> "application/octet-stream"
    }

    // ---- files screen interop ---------------------------------------------------

    fun onRestore(backupId: String) {
        setBusy(true)
        viewModelScope.launch {
            try {
                val report = sessions.restore(backupId)
                if (report == null) {
                    assistantText(listOf("that backup is gone"), isError = true)
                    return@launch
                }
                assistantText(listOf("restored the original and reopened it"))
                render(report)
            } finally {
                setBusy(false)
            }
        }
    }

    fun onCloseFile() {
        sessions.close()
        clearPending()
        mutate { it.copy(export = null) }
        assistantText(listOf("closed the file — nothing was written to it"))
    }

    fun onToggleGroup(entryId: String, category: FieldCategory) {
        replaceEntry(entryId) { entry ->
            val fields = entry as FieldsEntry
            val key = category.name
            fields.copy(
                collapsed = if (key in fields.collapsed) fields.collapsed - key else fields.collapsed + key,
            )
        }
    }

    fun onWarningDismissed() {
        viewModelScope.launch { settings.acceptWarning() }
    }

    // ---- plumbing ---------------------------------------------------------------

    private fun publishSession(session: EditorSession?) {
        mutate {
            it.copy(
                fileName = session?.file?.name,
                formatBadge = session?.formatBadge,
                backedUp = session?.backupId != null,
                readOnly = session?.readOnly ?: false,
                readOnlyReason = session?.tree?.readOnlyReason,
                canUndo = session?.canUndo ?: false,
                changedPaths = session?.appliedEdits?.map { edit -> edit.path }?.toSet() ?: emptySet(),
                export = session?.export ?: it.export,
            )
        }
        if (session == null) {
            mutate { it.copy(pendingDiffId = null, pendingEditCount = 0) }
        }
    }

    private fun refreshFieldEntries(tree: FieldTree) {
        mutate { state ->
            state.copy(
                entries = state.entries.map { entry ->
                    if (entry is FieldsEntry) {
                        entry.copy(groups = tree.groups(), editableCount = tree.editableCount, totalCount = tree.totalCount)
                    } else {
                        entry
                    }
                },
            )
        }
    }

    private fun assistantText(lines: List<String>, isError: Boolean = false) {
        if (lines.isEmpty()) return
        append(TextEntry(newId(), ChatRole.ASSISTANT, lines, isError = isError))
    }

    private fun append(entry: ChatEntry) = mutate { it.copy(entries = it.entries + entry) }

    private fun replaceEntry(id: String, transform: (ChatEntry) -> ChatEntry) = mutate { state ->
        state.copy(entries = state.entries.map { if (it.id == id) transform(it) else it })
    }

    private fun clearPending() = mutate { it.copy(pendingDiffId = null, pendingEditCount = 0) }

    private fun setBusy(value: Boolean) {
        localBusy.value = value
    }

    private fun mutate(block: (ChatUiState) -> ChatUiState) {
        _state.value = block(_state.value)
    }

    private fun newId(): String = UUID.randomUUID().toString().take(8)

    private companion object {
        const val MAX_WARNINGS = 5
    }
}
