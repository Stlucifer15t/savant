package dev.savant.data.settings

/**
 * API keys and tokens.
 *
 * Two implementations: [KeystoreSecretStore] keeps them in a file encrypted with a key that only
 * exists inside the android keystore, and [MemorySecretStore] holds them for the process lifetime
 * and writes nothing. The second is the fallback when a device has no usable keystore — savant
 * still works, and the settings screen says which one is in use rather than pretending.
 *
 * Every UI surface shows only [dev.savant.ai.AiPayload.redactKey] output, whatever is stored.
 */
interface SecretStore {

    fun put(id: String, secret: String)

    fun get(id: String): String?

    fun has(id: String): Boolean = !get(id).isNullOrBlank()

    fun clear(id: String)

    fun clearAll()

    /** `encrypted file`, or `memory only` when the keystore was unavailable. Shown in settings. */
    val storageLabel: String

    /** True when nothing is written to disk. */
    val isVolatile: Boolean get() = true

    companion object {
        /** The single provider key written by build steps before profiles existed. */
        const val PROVIDER_KEY = "provider.apiKey"
        const val SEARCH_KEY = "search.apiKey"
        const val MCP_PREFIX = "mcp."

        fun providerKey(profileId: String): String = "provider.$profileId.apiKey"
    }
}

/** Process scoped, nothing on disk. The default before a key is entered, and the fallback after. */
class MemorySecretStore(override val storageLabel: String = "memory only") : SecretStore {

    private val values = mutableMapOf<String, String>()

    override fun put(id: String, secret: String) {
        if (secret.isBlank()) values.remove(id) else values[id] = secret
    }

    override fun get(id: String): String? = values[id]

    override fun has(id: String): Boolean = !values[id].isNullOrBlank()

    override fun clear(id: String) {
        values.remove(id)
    }

    override fun clearAll() = values.clear()
}
