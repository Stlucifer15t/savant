package dev.savant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.ai.AiPayload
import dev.savant.ai.NetworkLog
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderModels
import dev.savant.ai.ProviderPing
import dev.savant.ai.ProviderProfile
import dev.savant.ai.ProviderProfiles
import dev.savant.ai.net.HttpTransport
import dev.savant.ai.providers.HttpLlmClient
import dev.savant.data.settings.ProviderProfileRepository
import dev.savant.data.settings.SecretStore
import dev.savant.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the connection panel is showing right now.
 *
 * The two calls a provider screen makes are different questions — "does this work?" and "what does
 * it offer?" — and the panel shows both answers side by side instead of one line of text that gets
 * overwritten by whichever ran last.
 */
data class ConnectionCheck(
    val testing: Boolean = false,
    val test: ProviderPing? = null,
    val reading: Boolean = false,
    val models: ProviderModels? = null,
) {
    val busy: Boolean get() = testing || reading

    /** The one line under the buttons when nothing has been checked yet. */
    val prompt: String
        get() = "test the endpoint, or read the models it offers — neither sends anything about a save file"
}

data class ProvidersUiState(
    val profiles: List<ProviderProfile> = emptyList(),
    val activeId: String? = null,
    val editing: ProviderProfile? = null,
    /** `****abcd`, or empty when this profile has no key yet. The key itself never reaches a screen. */
    val keyMask: String = "",
    val storageLabel: String = "memory only",
    /** Where the keys live, so a keystore that fell back to memory is visible rather than implied. */
    val keyPlace: String = "",
    val connection: ConnectionCheck = ConnectionCheck(),
    /** A model picked from the list is a draft until the profile is saved, like every other field. */
    val pickedModel: String? = null,
    val sendFieldValues: Boolean = true,
    val notice: String? = null,
) {
    val active: ProviderProfile? get() = profiles.firstOrNull { it.id == activeId }

    /** `****1234`, or empty. Never the key itself. */
    val keyMaskOf: String get() = keyMask

    val hasKey: Boolean get() = keyMask.isNotBlank()
}

/**
 * The provider settings screen.
 *
 * Three rules run through it. A key is written to [SecretStore] and read back only as a mask — the
 * screen has no code path that puts a stored key into a text field. A profile is tested and listed
 * with the values on screen, not the ones on disk, so the answers are about what the user is about
 * to save. And reading the model list is a `GET` that carries no conversation and nothing about the
 * open file, so it can never be the moment a save file leaves the device.
 */
@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val profiles: ProviderProfileRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val transport: HttpTransport,
    private val log: NetworkLog,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ProvidersUiState(
            storageLabel = secrets.storageLabel,
            keyPlace = if (secrets.isVolatile) "keys stay in memory until the app restarts" else "keys are encrypted at rest",
        ),
    )
    val state: StateFlow<ProvidersUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            profiles.profiles.collect { list ->
                mutate { state ->
                    state.copy(
                        profiles = list,
                        activeId = list.firstOrNull { it.active }?.id,
                        // An editor stays open while its profile still exists; the draft on screen
                        // is never overwritten by a stale copy from disk mid-edit.
                        editing = state.editing?.takeIf { draft -> list.any { it.id == draft.id } },
                    )
                }
            }
        }
        viewModelScope.launch {
            settings.settings.collect { prefs -> mutate { it.copy(sendFieldValues = prefs.sendFieldValues) } }
        }
    }

    fun onEdit(profile: ProviderProfile) {
        mutate { it.copy(editing = profile, keyMask = "", connection = ConnectionCheck(), pickedModel = null) }
        viewModelScope.launch {
            val mask = profiles.keyMaskOf(profile)
            mutate { it.copy(keyMask = mask) }
        }
    }

    fun onEditDraft(profile: ProviderProfile) = mutate { it.copy(editing = profile) }

    fun onCloseEditor() = mutate { it.copy(editing = null, connection = ConnectionCheck(), pickedModel = null) }

    fun onAdd(kind: ProviderKind) = viewModelScope.launch {
        val profile = profiles.add(kind)
        mutate {
            it.copy(
                editing = profile,
                keyMask = "",
                connection = ConnectionCheck(),
                pickedModel = null,
                notice = "${kind.label} profile added",
            )
        }
    }

    fun onSave(profile: ProviderProfile) = viewModelScope.launch {
        profiles.save(profile)
        mutate { it.copy(editing = profile, notice = "saved ${profile.name}") }
    }

    fun onDelete(profile: ProviderProfile) = viewModelScope.launch {
        profiles.remove(profile)
        mutate { it.copy(editing = null, connection = ConnectionCheck(), notice = "removed ${profile.name} and its key") }
    }

    /** Null hands the chat back to the on-device parser. */
    fun onActivate(id: String?) = viewModelScope.launch {
        profiles.activate(id)
        val label = id?.let { active -> profiles.profiles.value.firstOrNull { it.id == active }?.let { ProviderProfiles.label(it) } }
        mutate { it.copy(notice = if (label == null) "on device parser restored" else "now answering with $label") }
    }

    /** A blank [key] clears it. The value is never echoed back. */
    fun onKey(profile: ProviderProfile, key: String) = viewModelScope.launch {
        profiles.putKey(profile, key)
        mutate {
            it.copy(
                keyMask = AiPayload.redactKey(key),
                notice = if (key.isBlank()) "key cleared for ${profile.name}" else "key stored for ${profile.name} (${secrets.storageLabel})",
            )
        }
    }

    /**
     * Tests the values on screen: an unsaved base url or model is what gets checked.
     *
     * A connection that answers is also the moment the model list can be read, so a successful test
     * asks for the list too — one tap answers both questions, and a failure costs one request
     * instead of two.
     */
    fun onTest(profile: ProviderProfile, typedKey: String) = viewModelScope.launch {
        val key = keyFor(profile, typedKey)
        mutate { it.copy(connection = it.connection.copy(testing = true, test = null)) }
        val client = clientFor(profile, key)
        val result = client.ping()
        mutate { it.copy(connection = it.connection.copy(testing = false, test = result)) }
        if (result.ok && state.value.connection.models == null) onReadModels(profile, typedKey)
    }

    /** Reads the ids the endpoint offers. A `GET` with no body — never a completion, never a file. */
    fun onReadModels(profile: ProviderProfile, typedKey: String) = viewModelScope.launch {
        val key = keyFor(profile, typedKey)
        mutate { it.copy(connection = it.connection.copy(reading = true, models = null)) }
        val result = clientFor(profile, key).models()
        mutate {
            it.copy(
                connection = it.connection.copy(reading = false, models = result),
                notice = if (result.ok) null else "could not read the model list",
            )
        }
    }

    /** Keeps the picked name in the draft, so "save" is still what writes it. */
    fun onPickModel(profile: ProviderProfile, model: String) {
        mutate { it.copy(editing = profile.copy(model = model.trim()), pickedModel = model.trim()) }
    }

    fun onSendFieldValues(enabled: Boolean) = viewModelScope.launch { settings.setSendFieldValues(enabled) }

    fun onNoticeShown() = mutate { it.copy(notice = null) }

    /** A typed key wins; otherwise the stored one, which is never read into a field. */
    private suspend fun keyFor(profile: ProviderProfile, typedKey: String): String? =
        typedKey.trim().ifBlank { runCatching { profiles.keyOf(profile) }.getOrNull() }

    private fun clientFor(profile: ProviderProfile, key: String?) = HttpLlmClient(
        profile = profile,
        transport = transport,
        apiKey = { key },
        log = log,
    )

    private fun mutate(block: (ProvidersUiState) -> ProvidersUiState) {
        _state.value = block(_state.value)
    }
}
