package dev.savant.data.settings

/**
 * Everything the user can toggle, in one immutable snapshot.
 *
 * Steps 1-3 own appearance, confirmations and the first-launch warning. The provider, search and
 * MCP sections arrive with their steps; their fields live here already so the screen does not have
 * to be rebuilt and so no key is ever stored in two places.
 */
data class SavantSettings(
    // appearance
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val reducedMotion: Boolean = false,
    val monoValues: Boolean = true,

    // editing behaviour
    val confirmBeforeApply: Boolean = true,
    val showParseLog: Boolean = true,

    // first launch
    val warningAccepted: Boolean = false,

    // ai provider (step 6)
    val providerKind: String = "local",
    val providerLabel: String = "on device",
    val baseUrl: String = "",
    val modelName: String = "",
    val temperature: Float = 0.2f,
    val maxOutputTokens: Int = 1024,
    /** Never the key itself. Keys are held by [SecretStore]; this is only the masked echo. */
    val apiKeyMask: String = "",
    val sendFieldValues: Boolean = true,

    // web tools + connectors
    /** Master switch for `web_search` and `fetch_url`. Off by default: they leave the device. */
    val webToolsEnabled: Boolean = false,
    val searchApiEnabled: Boolean = false,
    val searchProvider: String = "duckduckgo",
    val searchApiKeyMask: String = "",
    /** Ask before every web call. Turning this off is a decision about convenience, not safety. */
    val confirmWebCalls: Boolean = true,
    val mcpConfirmEveryCall: Boolean = true,
    /**
     * Domains `fetch_url` may read, separated by commas or spaces. Empty means "any domain, but ask
     * first" rather than "any domain, silently" — see [dev.savant.ai.web.WebPolicy].
     */
    val allowListedDomains: String = "",

    // data
    val keepBackups: Boolean = true,
    val logNetworkRequests: Boolean = false,
) {
    val isRemoteProvider: Boolean get() = providerKind != "local"
}

enum class ThemeMode(val label: String) {
    SYSTEM("system"),
    DARK("dark"),
    LIGHT("light");

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}
