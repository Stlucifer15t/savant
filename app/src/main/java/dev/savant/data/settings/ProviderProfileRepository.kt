package dev.savant.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.savant.ai.AiPayload
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderProfile
import dev.savant.ai.ProviderProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore(name = "savant-providers")

private object ProfileKeys {
    val LIST = stringPreferencesKey("profiles")
    val ACTIVE = stringPreferencesKey("active_profile")
}

/**
 * The provider profiles on this device: which ones exist, and which one is answering.
 *
 * Settings only — no key material ever lands in the data store. A profile can be renamed, retargeted
 * at another model or deleted without touching a secret; removing one clears its key through
 * [SecretStore], the only thing that knows where keys live.
 */
@Singleton
class ProviderProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val preferences = context.profileStore.data
        .catch { thrown -> if (thrown is IOException) emit(emptyPreferences()) else throw thrown }

    val profiles: StateFlow<List<ProviderProfile>> = preferences
        .map { prefs ->
            val activeId = prefs[ProfileKeys.ACTIVE]
            ProviderProfiles.read(prefs[ProfileKeys.LIST])
                .map { profile -> profile.copy(active = profile.id == activeId) }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The profile that answers right now, or null for the on-device parser.
     *
     * A state flow starting at null, so the chat header reads `on device` on the first frame
     * instead of flashing a provider name it is about to replace.
     */
    val activeProfile: StateFlow<ProviderProfile?> = profiles
        .map { list -> list.firstOrNull { it.active } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun save(profile: ProviderProfile) {
        val current = snapshot()
        val next = if (current.any { it.id == profile.id }) {
            current.map { if (it.id == profile.id) profile else it }
        } else {
            current + profile
        }
        write(next)
    }

    suspend fun remove(profile: ProviderProfile) {
        val before = snapshot()
        write(before.filterNot { it.id == profile.id })
        secrets.clear(SecretStore.providerKey(profile.id))
        if (before.firstOrNull { it.active }?.id == profile.id) activate(null)
    }

    /** Null goes back to the on-device parser. */
    suspend fun activate(id: String?) = context.profileStore.edit { prefs ->
        if (id == null) prefs.remove(ProfileKeys.ACTIVE) else prefs[ProfileKeys.ACTIVE] = id
    }

    /** A new profile of [kind], saved and made active in one step. */
    suspend fun add(kind: ProviderKind): ProviderProfile {
        val profile = ProviderProfiles.blank(kind)
        save(profile)
        activate(profile.id)
        return profile
    }

    suspend fun putKey(profile: ProviderProfile, key: String) {
        secrets.put(SecretStore.providerKey(profile.id), key)
    }

    suspend fun clearKey(profile: ProviderProfile) {
        secrets.clear(SecretStore.providerKey(profile.id))
    }

    /** The key itself: only ever handed to a client, never to a screen. */
    suspend fun keyOf(profile: ProviderProfile): String? = secrets.get(SecretStore.providerKey(profile.id))

    /** `****abcd` for the row. */
    suspend fun keyMaskOf(profile: ProviderProfile): String =
        AiPayload.redactKey(secrets.get(SecretStore.providerKey(profile.id)))

    private suspend fun snapshot(): List<ProviderProfile> = profiles.first()

    private suspend fun write(list: List<ProviderProfile>) = context.profileStore.edit { prefs ->
        prefs[ProfileKeys.LIST] = ProviderProfiles.write(list.map { profile -> profile.copy(active = false) })
    }
}
