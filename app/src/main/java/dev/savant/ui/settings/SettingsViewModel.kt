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
import dev.savant.ai.web.SearchProvider
import dev.savant.ai.web.WebPolicy
import dev.savant.data.mcp.McpServerStore
import dev.savant.mcp.McpAuth
import dev.savant.mcp.McpClient
import dev.savant.mcp.McpServer
import dev.savant.mcp.McpTool
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
    // web tools
    val searchKeyDraft: String = "",
    val searchKeyIsSet: Boolean = false,
    // connectors
    val connectors: List<McpServer> = emptyList(),
    val connectorTools: Map<String, List<McpTool>> = emptyMap(),
    val editingServer: McpServer? = null,
    val serverKeyDraft: String = "",
    val serverKeyIsSet: Boolean = false,
    val connectingId: String? = null,
    val connectResult: String? = null,
) {
    val active: ProviderProfile? get() = profiles.firstOrNull { it.id == activeId }

    val engineLabel: String
        get() = active?.let { "${it.name} · ${it.model}" } ?: "on device"

    /** What savant will do with this profile's endpoint, in one line. */
    fun verdictFor(profile: ProviderProfile): NetworkPolicy.Verdict = NetworkPolicy.assess(profile.effectiveBaseUrl)

    val allowList: List<String> get() = WebPolicy.parseAllowList(settings.allowListedDomains)

    val searchProvider: SearchProvider get() = SearchProvider.fromId(settings.searchProvider)

    fun toolsFor(serverId: String): List<McpTool> = connectorTools[serverId] ?: emptyList()
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
    private val connectors: McpServerStore,
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
            connectors.set.collect { set ->
                _state.update { it.copy(connectors = set.servers, connectorTools = set.tools) }
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(searchKeyIsSet = secrets.has(SecretStore.SEARCH_KEY)) }
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

    // ---- web tools --------------------------------------------------------------------

    fun onWebTools(enabled: Boolean) = viewModelScope.launch { settings.setWebTools(enabled) }
    fun onSearchApi(enabled: Boolean) = viewModelScope.launch { settings.setSearchApi(enabled) }
    fun onConfirmWebCalls(enabled: Boolean) = viewModelScope.launch { settings.setConfirmWebCalls(enabled) }
    fun onAllowListChanged(value: String) = viewModelScope.launch { settings.setAllowListedDomains(value) }

    fun onSearchProvider(provider: SearchProvider) = viewModelScope.launch { settings.setSearchProvider(provider.id) }

    fun onSearchKeyDraft(value: String) = _state.update { it.copy(searchKeyDraft = value) }

    /** Blank clears it; anything else replaces it. The key never goes back into the field. */
    fun onSaveSearchKey() {
        val draft = _state.value.searchKeyDraft
        secrets.put(SecretStore.SEARCH_KEY, draft)
        viewModelScope.launch {
            settings.setSearchApiKeyMask(AiPayload.redactKey(draft))
            _state.update {
                it.copy(
                    searchKeyDraft = "",
                    searchKeyIsSet = secrets.has(SecretStore.SEARCH_KEY),
                    notice = if (draft.isBlank()) "search key removed" else "search key stored as ${AiPayload.redactKey(draft)}",
                )
            }
        }
    }

    // ---- connectors -------------------------------------------------------------------

    fun onAddConnector() {
        _state.update {
            it.copy(
                editingServer = McpServer(id = "", name = "", url = ""),
                serverKeyDraft = "",
                serverKeyIsSet = false,
                connectResult = null,
            )
        }
    }

    fun onEditConnector(server: McpServer) {
        _state.update {
            it.copy(
                editingServer = server,
                serverKeyDraft = "",
                serverKeyIsSet = secrets.has(SecretStore.MCP_PREFIX + server.id),
                connectResult = null,
            )
        }
    }

    fun onCancelServer() = _state.update { it.copy(editingServer = null, serverKeyDraft = "") }

    fun onServerName(value: String) = editServer { it.copy(name = value) }
    fun onServerUrl(value: String) = editServer { it.copy(url = value) }
    fun onServerAuth(auth: McpAuth) = editServer { it.copy(auth = auth) }
    fun onServerAuthHeader(value: String) = editServer { it.copy(authHeader = value) }
    fun onServerConfirm(enabled: Boolean) = editServer { it.copy(confirmEveryCall = enabled) }
    fun onServerKeyDraft(value: String) = _state.update { it.copy(serverKeyDraft = value) }

    private fun editServer(change: (McpServer) -> McpServer) {
        _state.update { state ->
            val current = state.editingServer ?: return@update state
            state.copy(editingServer = change(current))
        }
    }

    fun onSaveConnector() {
        val draft = _state.value.editingServer ?: return
        val url = draft.url.trim()
        if (url.isBlank()) {
            _state.update { it.copy(notice = "a connector needs a url") }
            return
        }
        val verdict = NetworkPolicy.assess(url)
        if (!verdict.allowed) {
            _state.update { it.copy(notice = (verdict as NetworkPolicy.Verdict.Refused).reason) }
            return
        }
        val server = draft.copy(
            id = draft.id.ifBlank { dev.savant.mcp.McpCodec.newId(draft.name.ifBlank { url }) },
            name = draft.name.trim().ifBlank { url },
            url = url,
        )
        viewModelScope.launch {
            connectors.save(server)
            val key = _state.value.serverKeyDraft
            if (key.isNotBlank()) secrets.put(SecretStore.MCP_PREFIX + server.id, key)
            _state.update {
                it.copy(
                    editingServer = null,
                    serverKeyDraft = "",
                    notice = "saved ${server.name}" + if (key.isNotBlank()) " · key ${AiPayload.redactKey(key)}" else "",
                )
            }
        }
    }

    fun onDeleteConnector(id: String) = viewModelScope.launch {
        connectors.delete(id)
        secrets.clear(SecretStore.MCP_PREFIX + id)
        _state.update {
            it.copy(
                notice = "connector removed",
                editingServer = if (it.editingServer?.id == id) null else it.editingServer,
            )
        }
    }

    fun onToggleConnector(id: String, enabled: Boolean) = viewModelScope.launch { connectors.setEnabled(id, enabled) }

    fun onToggleConnectorConfirm(id: String, confirm: Boolean) = viewModelScope.launch {
        connectors.setConfirmEveryCall(id, confirm)
    }

    /**
     * Handshake, then list tools.
     *
     * The tool list is stored, because "what can the model call" has to be answerable without a
     * network round trip — it is part of the prompt, and the prompt is built before anything is sent.
     */
    fun onConnect(id: String) {
        val server = _state.value.connectors.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            _state.update { it.copy(connectingId = id, connectResult = null) }
            val key = _state.value.serverKeyDraft.ifBlank { secrets.get(SecretStore.MCP_PREFIX + id) }
            val logging = _state.value.settings.logNetworkRequests
            val client = McpClient(server, { key }, transport) { event -> if (logging) networkLog.record(event) }

            val handshake = client.initialize()
            if (!handshake.ok) {
                _state.update { it.copy(connectingId = null, connectResult = "${server.name}: ${handshake.detail}") }
                return@launch
            }
            val listed = client.listTools()
            val updated = server.copy(
                protocolVersion = handshake.protocolVersion ?: server.protocolVersion,
                serverVersion = handshake.serverVersion ?: handshake.serverName ?: server.serverVersion,
                lastSeen = System.currentTimeMillis(),
            )
            connectors.recordConnection(updated, listed.tools)
            _state.update {
                it.copy(connectingId = null, connectResult = "${server.name}: ${handshake.detail} · ${listed.detail}")
            }
        }
    }

    fun onConnectResultShown() = _state.update { it.copy(connectResult = null) }

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
