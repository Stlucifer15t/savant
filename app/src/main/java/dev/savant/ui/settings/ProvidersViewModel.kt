package dev.savant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.ai.AiPayload
import dev.savant.ai.NetworkLog
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderPing
import dev.savant.ai.ProviderProfile
import dev.savant.ai.ProviderProfiles
import dev.savant.ai.net.HttpTransport
import dev.savant.ai.providers.HttpLlmClient
import dev.savant.data.settings.ProviderProfileRepository
import dev.savant.data.settings.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProvidersUiState(
    val profiles: List<ProviderProfile> = emptyList(),
    val activeId: String? = null,
    val editing: ProviderProfile? = null,
    /** `****abcd`, or empty when this profile has no key yet. The key itself never reaches a screen. */
    val keyMask: String = "",
    val storageLabel: String = "memory only",
    /** Where the keys live, so a keystore that fell back to memory is visible rather than implied. */
    val keyPlace: String = "",
    val testing: Boolean = false,
    val test: ProviderPing? = null,
    val notice: String? = null,
) {
    val active: ProviderProfile? get() = profiles.firstOrNull { it.id == activeId }
}

/**
 * The provider settings screen.
 *
 * Two rules run through it. A key is written to [SecretStore] and read back only as a mask — the
 * screen has no code path that puts a stored key into a text field. And a profile is tested with
 * the values on screen, not the ones on disk, so "test connection" answers for what the user is
 * about to save.
 */
@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val profiles: ProviderProfileRepository,
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
    }

    fun onEdit(profile: ProviderProfile) {
        mutate { it.copy(editing = profile, keyMask = "", test = null) }
        viewModelScope.launch {
            val mask = profiles.keyMaskOf(profile)
            mutate { it.copy(keyMask = mask) }
        }
    }

    fun onEditDraft(profile: ProviderProfile) = mutate { it.copy(editing = profile) }

    fun onCloseEditor() = mutate { it.copy(editing = null, test = null) }

    fun onAdd(kind: ProviderKind) = viewModelScope.launch {
        val profile = profiles.add(kind)
        mutate { it.copy(editing = profile, keyMask = "", test = null, notice = "${kind.label} profile added") }
    }

    fun onSave(profile: ProviderProfile) = viewModelScope.launch {
        profiles.save(profile)
        mutate { it.copy(editing = profile, notice = "saved ${profile.name}") }
    }

    fun onDelete(profile: ProviderProfile) = viewModelScope.launch {
        profiles.remove(profile)
        mutate { it.copy(editing = null, test = null, notice = "removed ${profile.name} and its key") }
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

    /** Tests the values on screen: an unsaved base url or model is what gets checked. */
    fun onTest(profile: ProviderProfile, typedKey: String) = viewModelScope.launch {
        mutate { it.copy(testing = true, test = null) }
        val key = typedKey.trim().ifBlank { runCatching { profiles.keyOf(profile) }.getOrNull() }
        val client = HttpLlmClient(
            profile = profile,
            transport = transport,
            apiKey = { key },
            log = log,
        )
        val result = client.ping()
        mutate { it.copy(testing = false, test = result) }
    }

    fun onNoticeShown() = mutate { it.copy(notice = null) }

    private fun mutate(block: (ProvidersUiState) -> ProvidersUiState) {
        _state.value = block(_state.value)
    }
}
