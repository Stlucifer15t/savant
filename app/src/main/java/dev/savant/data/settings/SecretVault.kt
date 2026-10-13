package dev.savant.data.settings

import dev.savant.parsers.json.JsonPayload
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/**
 * The envelope around the encrypted secrets file.
 *
 * Keeping the *format* in pure kotlin, with the cipher passed in, means the part that can silently
 * destroy a user's keys — version handling, truncation, a decrypt that returns garbage — is unit
 * tested with a fake cipher, while the android keystore stays a thin adapter that only knows how to
 * encrypt and decrypt bytes.
 *
 * Layout: `S V L T` · version byte · ciphertext. The ciphertext is whatever the platform cipher
 * produced, including its own iv, so this file never has to know about iv lengths.
 */
object SecretVault {

    private const val MAGIC_A = 'S'.code.toByte()
    private const val MAGIC_B = 'V'.code.toByte()
    private const val MAGIC_C = 'L'.code.toByte()
    private const val MAGIC_D = 'T'.code.toByte()

    const val VERSION: Byte = 1
    const val HEADER_BYTES = 5

    fun seal(plain: ByteArray, encrypt: (ByteArray) -> ByteArray): ByteArray {
        val body = encrypt(plain)
        val out = ByteArray(HEADER_BYTES + body.size)
        out[0] = MAGIC_A
        out[1] = MAGIC_B
        out[2] = MAGIC_C
        out[3] = MAGIC_D
        out[4] = VERSION
        body.copyInto(out, HEADER_BYTES)
        return out
    }

    /** Null when this is not a vault, is a version savant does not know, or will not decrypt. */
    fun open(vault: ByteArray, decrypt: (ByteArray) -> ByteArray): ByteArray? {
        if (vault.size <= HEADER_BYTES) return null
        if (vault[0] != MAGIC_A || vault[1] != MAGIC_B || vault[2] != MAGIC_C || vault[3] != MAGIC_D) return null
        if (vault[4] != VERSION) return null
        return runCatching { decrypt(vault.copyOfRange(HEADER_BYTES, vault.size)) }.getOrNull()
    }

    /** Secrets as one json object inside the envelope: `{"provider.p1.apiKey":"sk-…"}`. */
    fun encode(secrets: Map<String, String>, encrypt: (ByteArray) -> ByteArray): ByteArray =
        seal(JsonPayload.write(obj(secrets.map { (id, secret) -> id to str(secret) })).toByteArray(Charsets.UTF_8), encrypt)

    /** Empty when the vault is missing, corrupt, or was written by a key that no longer exists. */
    fun decode(vault: ByteArray, decrypt: (ByteArray) -> ByteArray): Map<String, String> {
        val plain = open(vault, decrypt) ?: return emptyMap()
        val json = JsonPayload.readOrEmpty(String(plain, Charsets.UTF_8))
        val members = (json as? dev.savant.parsers.json.Json.Obj)?.members ?: return emptyMap()
        return members.mapNotNull { (id, value) ->
            val secret = (value as? dev.savant.parsers.json.Json.Str)?.value
            if (secret.isNullOrEmpty()) null else id to secret
        }.toMap()
    }
}
