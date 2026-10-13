package dev.savant.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API keys in one aes-gcm encrypted file, under a key that never leaves the android keystore.
 *
 * Deliberately not `EncryptedSharedPreferences`: that library is deprecated, and its failure mode
 * on a device with a broken keystore is a crash. This is a smaller surface — one file, one key
 * alias — with a defined fallback: if the keystore cannot be used, [degraded] flips, the keys stay
 * in memory for the process lifetime, and settings says `memory only` instead of claiming
 * encryption it does not have.
 *
 * A key can also become unusable *after* it was used, for example when every screen lock is
 * removed. Decryption then fails, [SecretVault.decode] returns nothing, and savant asks for the key
 * again rather than taking the app down.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SecretStore {

    private val values = mutableMapOf<String, String>()
    private var loaded = false
    private var degradedReason: String? = null

    val degraded: Boolean get() = degradedReason != null

    override val storageLabel: String get() = if (degraded) "memory only" else "encrypted file"

    override val isVolatile: Boolean get() = degraded

    val note: String? get() = degradedReason

    override fun put(id: String, secret: String) {
        load()
        if (secret.isBlank()) values.remove(id) else values[id] = secret
        persist()
    }

    override fun get(id: String): String? {
        load()
        return values[id]
    }

    override fun has(id: String): Boolean = !get(id).isNullOrBlank()

    override fun clear(id: String) {
        load()
        values.remove(id)
        persist()
    }

    override fun clearAll() {
        load()
        values.clear()
        runCatching { vaultFile().delete() }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val file = vaultFile()
        if (!file.exists()) return
        val bytes = runCatching { file.readBytes() }.getOrElse { thrown ->
            degrade("could not read the secrets file: ${thrown.message}")
            return
        }
        try {
            values += SecretVault.decode(bytes, ::decrypt)
        } catch (thrown: Throwable) {
            degrade("stored keys could not be decrypted (${thrown.javaClass.simpleName}) — enter them again")
        }
    }

    private fun persist() {
        if (degraded) return
        val bytes = try {
            SecretVault.encode(values.toMap(), ::encrypt)
        } catch (thrown: Throwable) {
            degrade("keys stay in memory: the keystore refused a write (${thrown.javaClass.simpleName})")
            return
        }
        // Written to a sibling and renamed: a half written vault would lose every key at once.
        val file = vaultFile()
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (thrown: Throwable) {
            degrade("keys stay in memory: ${thrown.javaClass.simpleName}")
            temp.delete()
        }
    }

    private fun degrade(reason: String) {
        degradedReason = reason
    }

    private fun vaultFile(): File = File(context.filesDir, FILE_NAME)

    // ---- keystore --------------------------------------------------------------------

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return iv + cipher.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size <= IV_BYTES) throw IllegalStateException("secrets file is truncated")
        val iv = blob.copyOfRange(0, IV_BYTES)
        val body = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "savant.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FILE_NAME = "secrets.vault"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
