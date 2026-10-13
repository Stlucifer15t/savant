package dev.savant.mcp

/** How a server wants to be authenticated. The key itself lives in the secret store, never here. */
enum class McpAuth(val id: String, val label: String) {
    /** No auth header at all. The only sane choice for a server on your own network. */
    NONE("none", "none"),

    /** `authorization: Bearer <key>`. */
    BEARER("bearer", "bearer token"),

    /** A key in a header the server names, e.g. `x-api-key`. */
    HEADER("header", "custom header"),
    ;

    companion object {
        fun fromId(id: String?): McpAuth = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: NONE
    }
}

/**
 * One remote MCP server.
 *
 * Remote only: streamable http and sse. There is no stdio transport and no subprocess, so a server
 * cannot be a program on the phone and savant never spawns one — an app that edits save files has no
 * business executing anything.
 *
 * [confirmEveryCall] is per server rather than global, because a connector that only reads a wiki you
 * run yourself is not the same risk as one that posts somewhere.
 */
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val auth: McpAuth = McpAuth.NONE,
    val authHeader: String = "authorization",
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val enabled: Boolean = true,
    val confirmEveryCall: Boolean = true,
    val notes: String = "",
    /** Protocol version the server reported at the last handshake, and when. */
    val protocolVersion: String? = null,
    val serverVersion: String? = null,
    val lastSeen: Long? = null,
) {
    val summary: String
        get() = buildString {
            append(if (enabled) "on" else "off")
            append(" · ")
            append(auth.label)
            if (confirmEveryCall) append(" · confirms every call")
            serverVersion?.let { append(" · $it") }
        }

    companion object {
        /** The spec's own default, and long enough for a slow connector, short enough to not hang a turn. */
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
    }
}

/** What a handshake said. [detail] is shown verbatim, with key material already redacted. */
data class McpHandshake(
    val ok: Boolean,
    val serverName: String? = null,
    val serverVersion: String? = null,
    val protocolVersion: String? = null,
    val detail: String = "",
    val latencyMillis: Long = 0,
)

/** One tool a server offers, as savant stores it between sessions. */
data class McpTool(
    val serverId: String,
    val name: String,
    val description: String,
    val parameters: List<McpParameter> = emptyList(),
) {
    /**
     * Namespaced so two servers can both offer `search` without colliding.
     *
     * `__` and not `.` or `/`: openai's function names only allow letters, digits, underscores and
     * dashes, and a name a provider rejects takes the whole turn down with it.
     */
    val qualifiedName: String get() = "${name}__$serverId"

    companion object {
        const val SEPARATOR = "__"

        /** Splits a qualified name back into the tool and then the server id. */
        fun split(qualified: String): Pair<String, String>? {
            val at = qualified.lastIndexOf(SEPARATOR)
            if (at <= 0 || at + SEPARATOR.length >= qualified.length) return null
            return qualified.substring(at + SEPARATOR.length) to qualified.substring(0, at)
        }
    }
}

data class McpParameter(
    val name: String,
    val type: String,
    val description: String = "",
    val required: Boolean = false,
    val options: List<String> = emptyList(),
)

/** The result of a `tools/call`. [isError] is the server's own flag, not a transport failure. */
data class McpCallResult(
    val ok: Boolean,
    val text: String,
    val isError: Boolean = false,
    val detail: String? = null,
)
