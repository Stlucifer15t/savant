package dev.savant.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keys in `EncryptedSharedPreferences`, behind a `MasterKey` in the android keystore.
 *
 * Values are AES-256-GCM and the preference names are AES-256-SIV, so even the *names* of the keys
 * are not readable in the xml file on disk. `allowBackup` is off in the manifest, which matters more
 * than any of that: an encrypted preference that is copied to a cloud backup is still a copy.
 *
 * The keystore fails on some devices — a corrupted key after an os update, a work profile with a
 * hardware store that will not initialise. When it does, savant keeps the key in memory for this
 * process, says so in settings through [encrypted], and does not crash while a save file is open.
 */
@Singleton
class EncryptedSecretStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SecretStore {

    private val prefs: SharedPreferences? by lazy { createPreferences() }
    private val memory = mutableMapOf<String, String>()

    override val encrypted: Boolean get() = prefs != null

    override fun put(id: String, secret: String) {
        if (secret.isBlank()) {
            clear(id)
            return
        }
        val store = prefs
        if (store == null) {
            synchronized(memory) { memory[id] = secret }
            return
        }
        val written = runCatching { store.edit().putString(id, secret).apply() }.isSuccess
        if (!written) synchronized(memory) { memory[id] = secret }
    }

    override fun get(id: String): String? {
        val store = prefs
        val stored = if (store == null) null else runCatching { store.getString(id, null) }.getOrNull()
        if (stored != null) return stored
        return synchronized(memory) { memory[id] }
    }

    override fun has(id: String): Boolean = !get(id).isNullOrBlank()

    override fun clear(id: String) {
        synchronized(memory) { memory.remove(id) }
        val store = prefs ?: return
        runCatching { store.edit().remove(id).apply() }
    }

    override fun clearAll() {
        synchronized(memory) { memory.clear() }
        val store = prefs ?: return
        runCatching { store.edit().clear().apply() }
    }

    private fun createPreferences(): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    companion object {
        const val FILE_NAME = "savant-secrets"
    }
}
