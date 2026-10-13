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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
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
    val PROVIDER_KIND = stringPreferencesKey("provider_kind")
    val PROVIDER_LABEL = stringPreferencesKey("provider_label")
    val BASE_URL = stringPreferencesKey("base_url")
    val MODEL_NAME = stringPreferencesKey("model_name")
    val TEMPERATURE = floatPreferencesKey("temperature")
    val MAX_OUTPUT_TOKENS = intPreferencesKey("max_output_tokens")
    val API_KEY_MASK = stringPreferencesKey("api_key_mask")
    val SEND_FIELD_VALUES = booleanPreferencesKey("send_field_values")
    val WEB_TOOLS = booleanPreferencesKey("web_tools_enabled")
    val SEARCH_API_ENABLED = booleanPreferencesKey("search_api_enabled")
    val SEARCH_PROVIDER = stringPreferencesKey("search_provider")
    val CONFIRM_WEB = booleanPreferencesKey("confirm_web_calls")
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
                providerKind = prefs[Keys.PROVIDER_KIND] ?: defaults.providerKind,
                providerLabel = prefs[Keys.PROVIDER_LABEL] ?: defaults.providerLabel,
                baseUrl = prefs[Keys.BASE_URL] ?: defaults.baseUrl,
                modelName = prefs[Keys.MODEL_NAME] ?: defaults.modelName,
                temperature = prefs[Keys.TEMPERATURE] ?: defaults.temperature,
                maxOutputTokens = prefs[Keys.MAX_OUTPUT_TOKENS] ?: defaults.maxOutputTokens,
                apiKeyMask = prefs[Keys.API_KEY_MASK] ?: defaults.apiKeyMask,
                sendFieldValues = prefs[Keys.SEND_FIELD_VALUES] ?: defaults.sendFieldValues,
                webToolsEnabled = prefs[Keys.WEB_TOOLS] ?: defaults.webToolsEnabled,
                searchApiEnabled = prefs[Keys.SEARCH_API_ENABLED] ?: defaults.searchApiEnabled,
                searchProvider = prefs[Keys.SEARCH_PROVIDER] ?: defaults.searchProvider,
                confirmWebCalls = prefs[Keys.CONFIRM_WEB] ?: defaults.confirmWebCalls,
                searchApiKeyMask = prefs[Keys.SEARCH_API_KEY_MASK] ?: defaults.searchApiKeyMask,
                mcpConfirmEveryCall = prefs[Keys.MCP_CONFIRM] ?: defaults.mcpConfirmEveryCall,
                allowListedDomains = prefs[Keys.ALLOW_LIST] ?: defaults.allowListedDomains,
                keepBackups = prefs[Keys.KEEP_BACKUPS] ?: defaults.keepBackups,
                logNetworkRequests = prefs[Keys.LOG_NETWORK] ?: defaults.logNetworkRequests,
            )
        }

    suspend fun setTheme(theme: ThemeMode) = update { it[Keys.THEME] = theme.name }
    suspend fun setReducedMotion(enabled: Boolean) = update { it[Keys.REDUCED_MOTION] = enabled }
    suspend fun setMonoValues(enabled: Boolean) = update { it[Keys.MONO_VALUES] = enabled }
    suspend fun setConfirmBeforeApply(enabled: Boolean) = update { it[Keys.CONFIRM_BEFORE_APPLY] = enabled }
    suspend fun setShowParseLog(enabled: Boolean) = update { it[Keys.SHOW_PARSE_LOG] = enabled }
    suspend fun acceptWarning() = update { it[Keys.WARNING_ACCEPTED] = true }
    suspend fun setKeepBackups(enabled: Boolean) = update { it[Keys.KEEP_BACKUPS] = enabled }
    suspend fun setLogNetworkRequests(enabled: Boolean) = update { it[Keys.LOG_NETWORK] = enabled }
    suspend fun setSendFieldValues(enabled: Boolean) = update { it[Keys.SEND_FIELD_VALUES] = enabled }

    // ---- web tools and connectors ---------------------------------------------------

    suspend fun setWebTools(enabled: Boolean) = update { it[Keys.WEB_TOOLS] = enabled }
    suspend fun setSearchApi(enabled: Boolean) = update { it[Keys.SEARCH_API_ENABLED] = enabled }
    suspend fun setSearchProvider(id: String) = update { it[Keys.SEARCH_PROVIDER] = id }
    suspend fun setConfirmWebCalls(enabled: Boolean) = update { it[Keys.CONFIRM_WEB] = enabled }
    suspend fun setMcpConfirmEveryCall(enabled: Boolean) = update { it[Keys.MCP_CONFIRM] = enabled }
    suspend fun setAllowListedDomains(value: String) = update { it[Keys.ALLOW_LIST] = value }

    /** The masked echo of a key. The key itself goes to [SecretStore] and nowhere else. */
    suspend fun setApiKeyMask(mask: String) = update { it[Keys.API_KEY_MASK] = mask }
    suspend fun setSearchApiKeyMask(mask: String) = update { it[Keys.SEARCH_API_KEY_MASK] = mask }

    suspend fun setProvider(kind: String, label: String, baseUrl: String, model: String) = update {
        it[Keys.PROVIDER_KIND] = kind
        it[Keys.PROVIDER_LABEL] = label
        it[Keys.BASE_URL] = baseUrl
        it[Keys.MODEL_NAME] = model
    }

    suspend fun setGeneration(temperature: Float, maxOutputTokens: Int) = update {
        it[Keys.TEMPERATURE] = temperature
        it[Keys.MAX_OUTPUT_TOKENS] = maxOutputTokens
    }

    suspend fun resetToDefaults() {
        context.settingsStore.edit { it.clear() }
    }

    private suspend fun update(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit { prefs -> block(prefs) }
    }
}
