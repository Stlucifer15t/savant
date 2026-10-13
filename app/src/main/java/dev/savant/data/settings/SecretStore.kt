package dev.savant.data.settings

/**
 * Where keys live.
 *
 * One rule: a key is never in [SavantSettings], never in a profile, never in a log and never in a
 * backup. Settings carry a masked echo (`****abcd`) so the ui can say a key is set; this store carries
 * the key itself, encrypted, and hands it back only to the code that is about to put it in an
 * authorization header.
 *
 * Implementations: [EncryptedSecretStore] on device, and a plain map in tests.
 */
interface SecretStore {

    /** False only when the platform keystore refused, which the ui says out loud. */
    val encrypted: Boolean get() = true

    fun put(id: String, secret: String)

    fun get(id: String): String?

    fun has(id: String): Boolean

    fun clear(id: String)

    fun clearAll()

    companion object {
        const val PROVIDER_KEY = "provider.apiKey"
        const val SEARCH_KEY = "search.apiKey"
        const val MCP_PREFIX = "mcp."

        /** Each provider profile keeps its own key, so switching profiles does not overwrite one. */
        fun profileKey(profileId: String): String = "profile.$profileId"
    }
}
