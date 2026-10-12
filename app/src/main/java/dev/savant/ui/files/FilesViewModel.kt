package dev.savant.ui.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.data.session.SessionStore
import dev.savant.data.storage.Backup
import dev.savant.data.storage.BackupStore
import dev.savant.data.storage.ImportIndex
import dev.savant.data.storage.ImportRecord
import dev.savant.domain.session.EditorSession
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
) {
    val backupBytes: Long get() = backups.sumOf { it.sizeBytes }
}

/**
 * Files: what is open, what savant backed up, and what was imported recently.
 *
 * Backups are the safety net for every edit — one tap restores the untouched original into a fresh
 * session. Chat history and sessions move into Room in step 4; until then the lists are file
 * backed and this screen is the only place they surface.
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    private val sessions: SessionStore,
    private val backups: BackupStore,
    private val imports: ImportIndex,
) : ViewModel() {

    private val notice = MutableStateFlow<String?>(null)

    val state: StateFlow<FilesUiState> =
        combine(sessions.session, backups.backups, imports.records, sessions.busy) { session, backupList, importList, busy ->
            FilesUiState(session = session, backups = backupList, imports = importList, busy = busy)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesUiState())

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

    fun onNoticeShown() {
        notice.value = null
    }
}
