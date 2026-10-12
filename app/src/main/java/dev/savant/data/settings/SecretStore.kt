package dev.savant.data.settings

import javax.inject.Inject
import javax.inject.Singleton

/**
 * API keys and tokens.
 *
 * Step 3 keeps them in memory for the process lifetime only: nothing is written to disk, which is
 * the safest possible default and is enough while the assistant is the on-device one. Step 6 swaps
 * this implementation for EncryptedSharedPreferences so a configured provider survives a restart,
 * and every UI surface keeps showing only [dev.savant.ai.AiPayload.redactKey] output.
 */
@Singleton
class SecretStore @Inject constructor() {

    private val values = mutableMapOf<String, String>()

    fun put(id: String, secret: String) {
        if (secret.isBlank()) values.remove(id) else values[id] = secret
    }

    fun get(id: String): String? = values[id]

    fun has(id: String): Boolean = !values[id].isNullOrBlank()

    fun clear(id: String) {
        values.remove(id)
    }

    fun clearAll() = values.clear()

    companion object {
        const val PROVIDER_KEY = "provider.apiKey"
        const val SEARCH_KEY = "search.apiKey"
        const val MCP_PREFIX = "mcp."
    }
}
