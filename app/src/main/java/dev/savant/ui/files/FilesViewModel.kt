package dev.savant.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.data.db.SessionRow
import dev.savant.data.history.ChatHistoryRepository
import dev.savant.data.history.HistoryHit
import dev.savant.data.session.SessionStore
import dev.savant.data.storage.Backup
import dev.savant.data.storage.BackupStore
import dev.savant.data.storage.ImportIndex
import dev.savant.data.storage.ImportRecord
import dev.savant.domain.session.EditorSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FilesUiState(
    val session: EditorSession? = null,
    val backups: List<Backup> = emptyList(),
    val imports: List<ImportRecord> = emptyList(),
    val busy: Boolean = false,
    /** Past sessions, newest first. */
    val history: List<SessionRow> = emptyList(),
    val historyQuery: String = "",
    /** Transcript rows matching [historyQuery], across every session. */
    val hits: List<HistoryHit> = emptyList(),
) {
    val backupBytes: Long get() = backups.sumOf { it.sizeBytes }
}

/**
 * Files: what is open, what savant backed up, and what was imported recently.
 *
 * Backups are the safety net for every edit — one tap restores the untouched original into a fresh
 * session. Below them is the history the local database keeps: every session savant opened and, on
 * a search, the transcript lines that match. Viewing one shows it in chat as read-only history;
 * reopening one goes back to the file itself.
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    private val sessions: SessionStore,
    private val backups: BackupStore,
    private val imports: ImportIndex,
    private val history: ChatHistoryRepository,
) : ViewModel() {

    private val notice = MutableStateFlow<String?>(null)
    private val sessionsSeen = MutableStateFlow<List<SessionRow>>(emptyList())
    private val hits = MutableStateFlow<List<HistoryHit>>(emptyList())
    private val query = MutableStateFlow("")
    private var searchJob: Job? = null

    private val live = combine(sessions.session, backups.backups, imports.records, sessions.busy) { session, backupList, importList, busy ->
        FilesUiState(session = session, backups = backupList, imports = importList, busy = busy)
    }

    val state: StateFlow<FilesUiState> =
        combine(live, sessionsSeen, hits, query) { base, past, matches, text ->
            base.copy(history = past, historyQuery = text, hits = matches)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesUiState())

    init {
        viewModelScope.launch { history.observeSessions(50).collect { sessionsSeen.value = it } }
        viewModelScope.launch {
            // Both stores move step 3's flat files into the database on first touch.
            imports.refresh()
            backups.refresh()
        }
    }

    val noticeText: StateFlow<String?> = notice

    fun onRestore(backupId: String) = viewModelScope.launch {
        val report = sessions.restore(backupId)
        notice.value = if (report == null) "that backup is gone" else "restored the original"
    }

    fun onDeleteBackup(id: String) = viewModelScope.launch {
        backups.delete(id)
        notice.value = "backup deleted"
    }

    fun onReopen(uri: String) = viewModelScope.launch {
        val report = sessions.openUri(Uri.parse(uri))
        notice.value = if (report.session == null) "that file could not be reopened" else null
    }

    fun onForgetImport(uri: String) = viewModelScope.launch { imports.remove(uri) }

    fun onClearBackups() = viewModelScope.launch {
        backups.clear()
        notice.value = "backups cleared"
    }

    fun onClearImports() = viewModelScope.launch {
        imports.clear()
        notice.value = "import list cleared"
    }

    fun onCloseFile() {
        sessions.close()
        notice.value = "file closed"
    }

    /** Search runs over the stored plain text, debounced so typing does not hammer sqlite. */
    fun onHistoryQuery(value: String) {
        query.value = value
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(150)
            hits.value = history.searchEntries(value, limit = 40)
        }
    }

    /** Shows a past conversation in chat. Values are not stored, so it is read-only history. */
    fun onOpenHistory(sessionId: String) {
        sessions.submitTranscript(sessionId)
    }

    fun onReopenHistory(row: SessionRow) = viewModelScope.launch {
        val report = sessions.openUri(Uri.parse(row.uri))
        notice.value = if (report.session == null) "that file could not be reopened" else null
    }

    fun onDeleteHistory(sessionId: String) = viewModelScope.launch {
        history.deleteSession(sessionId)
        notice.value = "session deleted"
    }

    fun onClearHistory() = viewModelScope.launch {
        history.clear()
        hits.value = emptyList()
        notice.value = "history cleared"
    }

    fun onNoticeShown() {
        notice.value = null
    }
}
