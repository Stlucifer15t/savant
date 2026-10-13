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
    /**
     * Per profile settings — the model, the endpoint, the temperature — live in
     * [ProviderProfileRepository], not here: there is more than one of them and they change
     * together. What stays is the rule that applies to all of them.
     */
    val sendFieldValues: Boolean = true,

    // search + mcp (step 6 / 8)
    val searchApiEnabled: Boolean = false,
    val searchApiKeyMask: String = "",
    val mcpConfirmEveryCall: Boolean = true,
    val allowListedDomains: String = "",

    // data
    val keepBackups: Boolean = true,
    val logNetworkRequests: Boolean = false,
)

enum class ThemeMode(val label: String) {
    SYSTEM("system"),
    DARK("dark"),
    LIGHT("light");

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SYSTEM
    }
}
