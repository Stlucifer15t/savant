package dev.savant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.data.settings.SavantSettings
import dev.savant.data.settings.SettingsRepository
import dev.savant.data.settings.ThemeMode
import dev.savant.data.storage.BackupStore
import dev.savant.data.storage.ImportIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: SavantSettings = SavantSettings(),
    val backupCount: Int = 0,
    val backupBytes: Long = 0,
    val importCount: Int = 0,
    val notice: String? = null,
)

/**
 * Settings.
 *
 * Step 3 owns appearance, editing behaviour, privacy and data. The provider, search and MCP rows
 * are present but inert: they light up with their own build steps, and no key is ever written to
 * disk before [dev.savant.data.settings.SecretStore] moves to encrypted storage in step 6.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val backups: BackupStore,
    private val imports: ImportIndex,
) : ViewModel() {

    private val notice = MutableStateFlow<String?>(null)

    val state: StateFlow<SettingsUiState> = combine(
        settings.settings,
        backups.backups,
        imports.records,
        notice,
    ) { prefs, backupList, importList, message ->
        SettingsUiState(
            settings = prefs,
            backupCount = backupList.size,
            backupBytes = backupList.sumOf { it.sizeBytes },
            importCount = importList.size,
            notice = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onTheme(mode: ThemeMode) = viewModelScope.launch { settings.setTheme(mode) }
    fun onReducedMotion(enabled: Boolean) = viewModelScope.launch { settings.setReducedMotion(enabled) }
    fun onMonoValues(enabled: Boolean) = viewModelScope.launch { settings.setMonoValues(enabled) }
    fun onConfirmBeforeApply(enabled: Boolean) = viewModelScope.launch { settings.setConfirmBeforeApply(enabled) }
    fun onShowParseLog(enabled: Boolean) = viewModelScope.launch { settings.setShowParseLog(enabled) }
    fun onKeepBackups(enabled: Boolean) = viewModelScope.launch { settings.setKeepBackups(enabled) }
    fun onLogNetwork(enabled: Boolean) = viewModelScope.launch { settings.setLogNetworkRequests(enabled) }
    fun onSendFieldValues(enabled: Boolean) = viewModelScope.launch { settings.setSendFieldValues(enabled) }

    fun onClearBackups() = viewModelScope.launch {
        backups.clear()
        notice.value = "backups cleared"
    }

    fun onClearImports() = viewModelScope.launch {
        imports.clear()
        notice.value = "import list cleared"
    }

    fun onResetSettings() = viewModelScope.launch {
        settings.resetToDefaults()
        notice.value = "settings reset"
    }

    fun onNoticeShown() {
        notice.value = null
    }
}
