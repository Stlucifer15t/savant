package dev.savant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.ai.AiPayload
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderProfile
import dev.savant.ai.RemoteLlmClient
import dev.savant.ai.provider.HttpTransport
import dev.savant.ai.provider.NetworkEvent
import dev.savant.ai.provider.NetworkPolicy
import dev.savant.ai.provider.ProfileSet
import dev.savant.data.ai.NetworkLog
import dev.savant.data.history.ChatHistoryRepository
import dev.savant.data.history.HistoryCounts
import dev.savant.data.settings.ProfileStore
import dev.savant.data.settings.SavantSettings
import dev.savant.data.settings.SecretStore
import dev.savant.data.settings.SettingsRepository
import dev.savant.data.settings.ThemeMode
import dev.savant.data.storage.BackupStore
import dev.savant.data.storage.ImportIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Result of the "test connection" button, which never carries save data. */
data class PingState(
    val running: Boolean,
    val ok: Boolean? = null,
    val detail: String = "",
)

data class SettingsUiState(
    val settings: SavantSettings = SavantSettings(),
    val backupCount: Int = 0,
    val counts: HistoryCounts = HistoryCounts(),
    val backupBytes: Long = 0,
    val importCount: Int = 0,
    val notice: String? = null,
    // providers
    val profiles: List<ProviderProfile> = emptyList(),
    val activeId: String? = null,
    /** `profile id → ****abcd`. The key itself never enters ui state. */
    val keyMasks: Map<String, String> = emptyMap(),
    val secretsEncrypted: Boolean = true,
    val editing: ProviderProfile? = null,
    /** What is typed in the key field right now; blank means "leave the stored key alone". */
    val keyDraft: String = "",
    val keyIsSet: Boolean = false,
    val ping: PingState? = null,
    val network: List<NetworkEvent> = emptyList(),
) {
    val active: ProviderProfile? get() = profiles.firstOrNull { it.id == activeId }

    val engineLabel: String
        get() = active?.let { "${it.name} · ${it.model}" } ?: "on device"

    /** What savant will do with this profile's endpoint, in one line. */
    fun verdictFor(profile: ProviderProfile): NetworkPolicy.Verdict = NetworkPolicy.assess(profile.effectiveBaseUrl)
}

/**
 * Settings.
 *
 * Appearance, editing behaviour, providers, privacy and data. The provider half is the one with
 * teeth: a profile is a shape (endpoint, model, generation), its key lives in encrypted storage
 * under the profile's id, and "test connection" is the only call savant makes that carries no save
 * data at all — one GET to the model list.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val backups: BackupStore,
    private val imports: ImportIndex,
    private val history: ChatHistoryRepository,
    private val profiles: ProfileStore,
    private val secrets: SecretStore,
    private val transport: HttpTransport,
    private val networkLog: NetworkLog,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { prefs ->
                _state.update { it.copy(settings = prefs) }
            }
        }
        viewModelScope.launch {
            backups.backups.collect { list ->
                _state.update { it.copy(backupCount = list.size, backupBytes = list.sumOf { backup -> backup.sizeBytes }) }
            }
        }
        viewModelScope.launch {
            imports.records.collect { list -> _state.update { it.copy(importCount = list.size) } }
        }
        viewModelScope.launch {
            profiles.profiles.collect { set ->
                _state.update { it.copy(profiles = set.profiles, activeId = set.activeId) }
                refreshKeyMasks(set)
            }
        }
        viewModelScope.launch {
            networkLog.entries.collect { events -> _state.update { it.copy(network = events) } }
        }
        viewModelScope.launch {
            _state.update { it.copy(secretsEncrypted = secrets.encrypted) }
            imports.refresh()
            backups.refresh()
            _state.update { it.copy(counts = history.counts()) }
        }
    }

    private suspend fun refreshKeyMasks(set: ProfileSet) {
        val masks = set.profiles.associate { profile ->
            profile.id to AiPayload.redactKey(secrets.get(SecretStore.profileKey(profile.id)))
        }
        _state.update { it.copy(keyMasks = masks) }
    }

    // ---- appearance and behaviour ---------------------------------------------------

    fun onTheme(mode: ThemeMode) = viewModelScope.launch { settings.setTheme(mode) }
    fun onReducedMotion(enabled: Boolean) = viewModelScope.launch { settings.setReducedMotion(enabled) }
    fun onMonoValues(enabled: Boolean) = viewModelScope.launch { settings.setMonoValues(enabled) }
    fun onConfirmBeforeApply(enabled: Boolean) = viewModelScope.launch { settings.setConfirmBeforeApply(enabled) }
    fun onShowParseLog(enabled: Boolean) = viewModelScope.launch { settings.setShowParseLog(enabled) }
    fun onKeepBackups(enabled: Boolean) = viewModelScope.launch { settings.setKeepBackups(enabled) }
    fun onLogNetwork(enabled: Boolean) = viewModelScope.launch { settings.setLogNetworkRequests(enabled) }
    fun onSendFieldValues(enabled: Boolean) = viewModelScope.launch { settings.setSendFieldValues(enabled) }

    // ---- providers ------------------------------------------------------------------

    fun onAddProfile(kind: ProviderKind) {
        _state.update {
            it.copy(
                editing = ProfileSet.draft(kind),
                keyDraft = "",
                keyIsSet = false,
                ping = null,
                notice = null,
            )
        }
    }

    fun onEditProfile(profile: ProviderProfile) {
        _state.update {
            it.copy(
                editing = profile,
                keyDraft = "",
                keyIsSet = secrets.has(SecretStore.profileKey(profile.id)),
                ping = null,
                notice = null,
            )
        }
    }

    fun onCancelEdit() {
        _state.update { it.copy(editing = null, keyDraft = "", ping = null) }
    }

    fun onEditingKind(kind: ProviderKind) {
        _state.update { state ->
            val current = state.editing ?: return@update state
            // Switching provider moves the endpoint to that provider's own, unless it was typed.
            val baseUrl = if (current.baseUrl.isBlank() || current.baseUrl == current.provider.defaultBaseUrl) {
                kind.defaultBaseUrl
            } else {
                current.baseUrl
            }
            state.copy(editing = current.copy(provider = kind, baseUrl = baseUrl, name = if (current.name == current.provider.label) kind.label else current.name))
        }
    }

    fun onEditingName(value: String) = edit { it.copy(name = value) }
    fun onEditingModel(value: String) = edit { it.copy(model = value) }
    fun onEditingBaseUrl(value: String) = edit { it.copy(baseUrl = value) }
    fun onEditingTemperature(value: Float) = edit { it.copy(temperature = value) }
    fun onEditingMaxTokens(value: Int) = edit { it.copy(maxOutputTokens = value) }
    fun onKeyDraft(value: String) = _state.update { it.copy(keyDraft = value) }

    private fun edit(change: (ProviderProfile) -> ProviderProfile) {
        _state.update { state ->
            val current = state.editing ?: return@update state
            state.copy(editing = change(current))
        }
    }

    fun onSaveProfile() {
        val profile = _state.value.editing ?: return
        if (profile.model.isBlank()) {
            _state.update { it.copy(notice = "a profile needs a model name") }
            return
        }
        val verdict = NetworkPolicy.assess(profile.effectiveBaseUrl)
        if (!verdict.allowed) {
            _state.update { it.copy(notice = (verdict as NetworkPolicy.Verdict.Refused).reason) }
            return
        }
        viewModelScope.launch {
            profiles.save(profile)
            val draft = _state.value.keyDraft
            if (draft.isNotBlank()) secrets.put(SecretStore.profileKey(profile.id), draft)
            refreshKeyMasks(profiles.current())
            _state.update {
                it.copy(
                    editing = null,
                    keyDraft = "",
                    notice = "saved ${profile.name}" + if (draft.isNotBlank()) " · key ${AiPayload.redactKey(draft)}" else "",
                )
            }
        }
    }

    fun onDeleteProfile(id: String) = viewModelScope.launch {
        profiles.delete(id)
        secrets.clear(SecretStore.profileKey(id))
        refreshKeyMasks(profiles.current())
        _state.update { it.copy(notice = "profile deleted", editing = if (it.editing?.id == id) null else it.editing) }
    }

    /** `null` switches savant back to the on device engine. */
    fun onActivate(id: String?) = viewModelScope.launch {
        profiles.setActive(id)
        _state.update {
            it.copy(
                activeId = id,
                notice = if (id == null) "back to the on device engine" else "using ${it.profiles.firstOrNull { profile -> profile.id == id }?.name ?: "that profile"}",
            )
        }
    }

    /**
     * One GET to the provider's model list.
     *
     * It carries no save data, no field names and no values, which is why it is safe to press before
     * trusting a profile with a file.
     */
    fun onTestConnection() {
        val profile = _state.value.editing ?: _state.value.active ?: return
        viewModelScope.launch {
            _state.update { it.copy(ping = PingState(running = true, detail = "calling ${NetworkPolicy.redactUrl(profile.effectiveBaseUrl)}")) }
            val key = _state.value.keyDraft.ifBlank { secrets.get(SecretStore.profileKey(profile.id)) }
            val client = RemoteLlmClient(
                profile = profile,
                apiKey = { key },
                transport = transport,
                onEvent = { event -> if (_state.value.settings.logNetworkRequests) networkLog.record(event) },
            )
            val ping = client.ping()
            _state.update {
                it.copy(
                    ping = PingState(running = false, ok = ping.ok, detail = "${ping.detail} · ${ping.latencyMillis} ms"),
                )
            }
        }
    }

    fun onClearNetworkLog() {
        networkLog.clear()
        _state.update { it.copy(notice = "network log cleared") }
    }

    fun onForgetKeys() = viewModelScope.launch {
        secrets.clearAll()
        refreshKeyMasks(profiles.current())
        _state.update { it.copy(keyDraft = "", keyIsSet = false, notice = "every stored key was deleted") }
    }

    // ---- data -----------------------------------------------------------------------

    private fun refreshCounts() = viewModelScope.launch { _state.update { it.copy(counts = history.counts()) } }

    fun onClearBackups() = viewModelScope.launch {
        backups.clear()
        _state.update { it.copy(backupCount = 0, backupBytes = 0, notice = "backups cleared") }
        refreshCounts()
    }

    fun onClearImports() = viewModelScope.launch {
        imports.clear()
        _state.update { it.copy(importCount = 0, notice = "import list cleared") }
        refreshCounts()
    }

    fun onClearHistory() = viewModelScope.launch {
        history.clear()
        imports.clear()
        backups.clear()
        _state.update {
            it.copy(
                counts = HistoryCounts(),
                backupCount = 0,
                backupBytes = 0,
                importCount = 0,
                notice = "history, imports and backups cleared",
            )
        }
    }

    fun onResetSettings() = viewModelScope.launch {
        settings.resetToDefaults()
        _state.update { it.copy(notice = "settings reset") }
    }

    fun onNoticeShown() {
        _state.update { it.copy(notice = null) }
    }

    /** The current profile set, for callers that need more than the ui state snapshot. */
    suspend fun currentProfiles(): ProfileSet = profiles.profiles.first()
}
