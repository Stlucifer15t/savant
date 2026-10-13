package dev.savant.data.mcp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.savant.mcp.McpCodec
import dev.savant.mcp.McpServer
import dev.savant.mcp.McpSet
import dev.savant.mcp.McpTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.mcpStore: DataStore<Preferences> by preferencesDataStore(name = "savant-connectors")

/**
 * The connector list, as one json document, plus the tools each one offered.
 *
 * Two flows and one write path, so the assistant can answer "what can the model call" from memory
 * while the settings screen is the only thing that ever does a handshake on purpose.
 */
@Singleton
class McpServerStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val set: Flow<McpSet> = context.mcpStore.data
        .catch { throwable -> if (throwable is IOException) emit(emptyPreferences()) else throw throwable }
        .map { prefs -> McpCodec.decode(prefs[Keys.DOCUMENT]) }

    val servers: Flow<List<McpServer>> = set.map { it.servers }

    suspend fun current(): McpSet = set.first()

    suspend fun save(server: McpServer) = write { it.withServer(server) }

    suspend fun delete(id: String) = write { it.withoutServer(id) }

    suspend fun setEnabled(id: String, enabled: Boolean) = write { current ->
        current.copy(servers = current.servers.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    suspend fun setConfirmEveryCall(id: String, confirm: Boolean) = write { current ->
        current.copy(servers = current.servers.map { if (it.id == id) it.copy(confirmEveryCall = confirm) else it })
    }

    /** Stores what a handshake and `tools/list` reported, so the next turn can offer the tools offline. */
    suspend fun recordConnection(server: McpServer, tools: List<McpTool>) = write { current ->
        current.withServer(server).withTools(server.id, tools)
    }

    suspend fun saveTools(id: String, tools: List<McpTool>) = write { it.withTools(id, tools) }

    suspend fun clear() = write { McpSet() }

    private suspend fun write(change: (McpSet) -> McpSet) {
        val updated = change(current())
        context.mcpStore.edit { prefs -> prefs[Keys.DOCUMENT] = McpCodec.encode(updated) }
    }

    private object Keys {
        val DOCUMENT = stringPreferencesKey("document")
    }
}
