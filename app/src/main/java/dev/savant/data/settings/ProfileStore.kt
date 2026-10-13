package dev.savant.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.savant.ai.ProviderProfile
import dev.savant.ai.provider.ProfileCodec
import dev.savant.ai.provider.ProfileSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore(name = "savant-profiles")

/**
 * Provider profiles: which endpoint, which model, how hot, and which one is active.
 *
 * Stored as one json document in a DataStore file, because a profile list is a document and not a set
 * of columns, and because [ProfileCodec] is pure jvm — the round trip is a unit test, not a device
 * test. Keys are *not* in here; each profile's key lives in [SecretStore] under its id.
 *
 * Every write also mirrors the active profile into [SavantSettings], which is what the top bar, the
 * chat engine label and the privacy copy read. Two stores, one direction, no chance of a screen
 * disagreeing with the one that actually makes the call.
 */
@Singleton
class ProfileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {

    val profiles: Flow<ProfileSet> = context.profileStore.data
        .catch { throwable -> if (throwable is IOException) emit(emptyPreferences()) else throw throwable }
        .map { prefs -> ProfileCodec.decode(prefs[Keys.PROFILES]) }

    suspend fun current(): ProfileSet = profiles.first()

    suspend fun save(profile: ProviderProfile) = write { it.withProfile(profile) }

    suspend fun delete(id: String) = write { it.withoutProfile(id) }

    /** `null` switches savant back to the on device engine. */
    suspend fun setActive(id: String?) = write { it.withActive(id) }

    private suspend fun write(change: (ProfileSet) -> ProfileSet) {
        val updated = change(current())
        context.profileStore.edit { prefs -> prefs[Keys.PROFILES] = ProfileCodec.encode(updated) }
        mirror(updated)
    }

    private suspend fun mirror(set: ProfileSet) {
        val active = set.active
        settings.setProvider(
            kind = active?.provider?.id ?: "local",
            label = active?.let { profile -> "${profile.name} · ${profile.model}" } ?: "on device",
            baseUrl = active?.effectiveBaseUrl ?: "",
            model = active?.model ?: "",
        )
        active?.let { profile -> settings.setGeneration(profile.temperature, profile.maxOutputTokens) }
    }

    private object Keys {
        val PROFILES = stringPreferencesKey("profiles")
    }
}
