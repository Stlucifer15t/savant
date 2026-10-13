package dev.savant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.ai.ProviderProfiles
import dev.savant.data.settings.ProviderProfileRepository
import dev.savant.data.settings.SavantSettings
import dev.savant.data.settings.SecretStore
import dev.savant.data.settings.SettingsRepository
import dev.savant.data.settings.ThemeMode
import dev.savant.data.history.ChatHistoryRepository
import dev.savant.data.history.HistoryCounts
import dev.savant.data.storage.BackupStore
import dev.savant.data.storage.ImportIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: SavantSettings = SavantSettings(),
    val backupCount: Int = 0,
    val counts: HistoryCounts = HistoryCounts(),
    val backupBytes: Long = 0,
    val importCount: Int = 0,
    val notice: String? = null,
    /** `on device`, or the active profile as `provider · model`. */
    val engineLabel: String = "on device",
    val isRemote: Boolean = false,
    val providerCount: Int = 0,
    /** Where api keys live: `encrypted file`, or `memory only` when the keystore was unusable. */
    val keyStorage: String = "memory only",
)

/**
 * Settings.
 *
 * Step 6 owns the provider rows: which engine answers, where the keys live and what left the
 * device. The search and MCP rows are still inert — they light up with build step 8 — and the
 * values shown here (`engineLabel`, `keyStorage`) are read from the same sources the chat uses, so
 * the screen cannot claim an encryption that is not in force.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val backups: BackupStore,
    private val imports: ImportIndex,
    private val history: ChatHistoryRepository,
    private val profiles: ProviderProfileRepository,
    secrets: SecretStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(keyStorage = secrets.storageLabel))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { prefs -> mutate { it.copy(settings = prefs) } }
        }
        viewModelScope.launch {
            backups.backups.collect { list ->
                mutate { it.copy(backupCount = list.size, backupBytes = list.sumOf { backup -> backup.sizeBytes }) }
            }
        }
        viewModelScope.launch {
            imports.records.collect { list -> mutate { it.copy(importCount = list.size) } }
        }
        viewModelScope.launch {
            profiles.profiles.collect { list ->
                val active = list.firstOrNull { it.active }
                mutate {
                    it.copy(
                        providerCount = list.size,
                        engineLabel = active?.let { profile -> ProviderProfiles.label(profile) } ?: "on device",
                        isRemote = active != null,
                    )
                }
            }
        }
        refreshCounts()
    }

    private fun mutate(block: (SettingsUiState) -> SettingsUiState) {
        _state.value = block(_state.value)
    }

    /** Recounts the tables; the row in the data section is a plain projection of this. */
    private fun refreshCounts() = viewModelScope.launch {
        val counts = history.counts()
        mutate { it.copy(counts = counts) }
    }

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
        mutate { it.copy(notice = "backups cleared") }
        refreshCounts()
    }

    fun onClearImports() = viewModelScope.launch {
        imports.clear()
        mutate { it.copy(notice = "import list cleared") }
        refreshCounts()
    }

    fun onClearHistory() = viewModelScope.launch {
        history.clear()
        imports.clear()
        backups.clear()
        mutate { it.copy(notice = "history, imports and backups cleared") }
        refreshCounts()
    }

    fun onResetSettings() = viewModelScope.launch {
        settings.resetToDefaults()
        mutate { it.copy(notice = "settings reset") }
    }

    fun onNoticeShown() = mutate { it.copy(notice = null) }
}
