package dev.savant.data.plugins

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.savant.plugins.GamePlugin
import dev.savant.plugins.PluginCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pluginStore: DataStore<Preferences> by preferencesDataStore(name = "savant-plugins")

/** What an import did. Refused is not an error state, it is the answer. */
sealed interface ImportOutcome {
    data class Imported(val plugins: List<GamePlugin>, val skipped: List<String> = emptyList()) : ImportOutcome
    data class Refused(val reasons: List<String>) : ImportOutcome
}

/**
 * The installed plugins, as one json document.
 *
 * Not a database table: a plugin is a document a person reads, edits, exports and shares, and keeping
 * it as the same json on disk as in the share sheet means export is a copy and import is a parse. The
 * schema was written down in the formats screen before the runtime existed, and [PluginCodec] is the
 * two meeting.
 */
@Singleton
class PluginStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val plugins: Flow<List<GamePlugin>> = context.pluginStore.data
        .catch { throwable -> if (throwable is IOException) emit(emptyPreferences()) else throw throwable }
        .map { prefs -> PluginCodec.decodeAll(prefs[Keys.DOCUMENT]).first }

    suspend fun current(): List<GamePlugin> = plugins.first()

    suspend fun save(plugin: GamePlugin) {
        write { current -> current.filterNot { it.id == plugin.id } + plugin }
    }

    suspend fun saveAll(plugins: List<GamePlugin>) {
        write { current ->
            val byId = current.associateBy { it.id }.toMutableMap()
            plugins.forEach { byId[it.id] = it }
            byId.values.toList()
        }
    }

    suspend fun delete(id: String) {
        write { current -> current.filterNot { it.id == id } }
    }

    suspend fun clear() {
        write { emptyList() }
    }

    /** Parses and validates an imported document. Nothing is stored unless at least one plugin is valid. */
    suspend fun importJson(text: String): ImportOutcome {
        val (good, bad) = PluginCodec.decodeAll(text)
        if (good.isEmpty()) return ImportOutcome.Refused(bad.ifEmpty { listOf("no plugin found in that file") })
        saveAll(good)
        return ImportOutcome.Imported(good, bad)
    }

    /** The document to share or save. Pretty printed, because somebody is going to read it. */
    suspend fun exportJson(onlyId: String? = null): String {
        val chosen = current().let { all -> if (onlyId == null) all else all.filter { it.id == onlyId } }
        return when (chosen.size) {
            1 -> PluginCodec.encode(chosen.single())
            else -> PluginCodec.encodeAll(chosen)
        }
    }

    private suspend fun write(change: (List<GamePlugin>) -> List<GamePlugin>) {
        val updated = change(current())
        context.pluginStore.edit { prefs ->
            prefs[Keys.DOCUMENT] = PluginCodec.encodeAll(updated)
        }
    }

    private object Keys {
        val DOCUMENT = stringPreferencesKey("document")
    }
}
