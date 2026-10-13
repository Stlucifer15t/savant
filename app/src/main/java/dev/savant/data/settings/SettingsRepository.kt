package dev.savant.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "savant-settings")

private object Keys {
    val THEME = stringPreferencesKey("theme")
    val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
    val MONO_VALUES = booleanPreferencesKey("mono_values")
    val CONFIRM_BEFORE_APPLY = booleanPreferencesKey("confirm_before_apply")
    val SHOW_PARSE_LOG = booleanPreferencesKey("show_parse_log")
    val WARNING_ACCEPTED = booleanPreferencesKey("warning_accepted")
    val SEND_FIELD_VALUES = booleanPreferencesKey("send_field_values")
    val SEARCH_API_ENABLED = booleanPreferencesKey("search_api_enabled")
    val SEARCH_API_KEY_MASK = stringPreferencesKey("search_api_key_mask")
    val MCP_CONFIRM = booleanPreferencesKey("mcp_confirm_every_call")
    val ALLOW_LIST = stringPreferencesKey("allow_listed_domains")
    val KEEP_BACKUPS = booleanPreferencesKey("keep_backups")
    val LOG_NETWORK = booleanPreferencesKey("log_network_requests")
}

/**
 * Preferences backed by Jetpack DataStore.
 *
 * A corrupt or unreadable store degrades to [SavantSettings] defaults instead of crashing the app:
 * savant holding a save file open is more important than a lost theme choice.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val settings: Flow<SavantSettings> = context.settingsStore.data
        .catch { throwable -> if (throwable is IOException) emit(emptyPreferences()) else throw throwable }
        .map { prefs ->
            val defaults = SavantSettings()
            SavantSettings(
                theme = ThemeMode.fromName(prefs[Keys.THEME]),
                reducedMotion = prefs[Keys.REDUCED_MOTION] ?: defaults.reducedMotion,
                monoValues = prefs[Keys.MONO_VALUES] ?: defaults.monoValues,
                confirmBeforeApply = prefs[Keys.CONFIRM_BEFORE_APPLY] ?: defaults.confirmBeforeApply,
                showParseLog = prefs[Keys.SHOW_PARSE_LOG] ?: defaults.showParseLog,
                warningAccepted = prefs[Keys.WARNING_ACCEPTED] ?: defaults.warningAccepted,
                sendFieldValues = prefs[Keys.SEND_FIELD_VALUES] ?: defaults.sendFieldValues,
                searchApiEnabled = prefs[Keys.SEARCH_API_ENABLED] ?: defaults.searchApiEnabled,
                searchApiKeyMask = prefs[Keys.SEARCH_API_KEY_MASK] ?: defaults.searchApiKeyMask,
                mcpConfirmEveryCall = prefs[Keys.MCP_CONFIRM] ?: defaults.mcpConfirmEveryCall,
                allowListedDomains = prefs[Keys.ALLOW_LIST] ?: defaults.allowListedDomains,
                keepBackups = prefs[Keys.KEEP_BACKUPS] ?: defaults.keepBackups,
                logNetworkRequests = prefs[Keys.LOG_NETWORK] ?: defaults.logNetworkRequests,
            )
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The settings as they are *right now*, for the two places that cannot wait for a collector:
     * the request log switch and the assistant's privacy rules. Always populated before either is
     * used because data store emits its first value on subscription.
     */
    val current: StateFlow<SavantSettings> = settings.stateIn(scope, SharingStarted.Eagerly, SavantSettings())

    suspend fun setTheme(theme: ThemeMode) = update { it[Keys.THEME] = theme.name }
    suspend fun setReducedMotion(enabled: Boolean) = update { it[Keys.REDUCED_MOTION] = enabled }
    suspend fun setMonoValues(enabled: Boolean) = update { it[Keys.MONO_VALUES] = enabled }
    suspend fun setConfirmBeforeApply(enabled: Boolean) = update { it[Keys.CONFIRM_BEFORE_APPLY] = enabled }
    suspend fun setShowParseLog(enabled: Boolean) = update { it[Keys.SHOW_PARSE_LOG] = enabled }
    suspend fun acceptWarning() = update { it[Keys.WARNING_ACCEPTED] = true }
    suspend fun setKeepBackups(enabled: Boolean) = update { it[Keys.KEEP_BACKUPS] = enabled }
    suspend fun setLogNetworkRequests(enabled: Boolean) = update { it[Keys.LOG_NETWORK] = enabled }
    suspend fun setSendFieldValues(enabled: Boolean) = update { it[Keys.SEND_FIELD_VALUES] = enabled }

    suspend fun resetToDefaults() {
        context.settingsStore.edit { it.clear() }
    }

    private suspend fun update(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit { prefs -> block(prefs) }
    }
}
