package dev.savant.mcp

import dev.savant.ai.provider.HttpCall
import dev.savant.ai.provider.HttpResult
import dev.savant.ai.provider.HttpTransport
import dev.savant.ai.provider.NetworkEvent
import dev.savant.ai.provider.NetworkPolicy

/** What `tools/list` produced. */
data class McpToolsResult(
    val ok: Boolean,
    val tools: List<McpTool> = emptyList(),
    val detail: String = "",
)

/**
 * One MCP server, over streamable http.
 *
 * Three calls: handshake, list tools, call a tool. Everything else — the session id the server hands
 * back, sse framing, json-rpc errors, the network log entry — is handled here so nothing above this
 * class knows which transport a connector speaks.
 *
 * A client is built per use rather than held open: savant has no long lived connections, no
 * reconnect loop and nothing listening in the background. A turn opens, calls, closes.
 */
class McpClient(
    private val server: McpServer,
    private val apiKey: () -> String?,
    private val transport: HttpTransport,
    private val onEvent: (NetworkEvent) -> Unit = {},
) {

    private var sessionId: String? = null
    private var nextId = 1

    suspend fun initialize(): McpHandshake {
        val refused = NetworkPolicy.assess(server.url)
        if (refused is NetworkPolicy.Verdict.Refused) {
            return McpHandshake(ok = false, detail = refused.reason)
        }

        val id = nextId++
        val call = post(McpProtocol.initializeBody(id))
        val result = transport.execute(call, server.timeoutMillis)
        record(call, result, "initialize")
        if (!result.ok) {
            return McpHandshake(
                ok = false,
                detail = if (result.isTransportFailure) result.message else "http ${result.status}: ${result.message}",
                latencyMillis = result.latencyMillis,
            )
        }

        sessionId = result.headers[McpProtocol.SESSION_HEADER] ?: sessionId
        val reply = McpProtocol.parse(result.body, id)
        val error = reply.error
        if (error != null) {
            return McpHandshake(ok = false, detail = error.message, latencyMillis = result.latencyMillis)
        }
        val (name, version, protocol) = McpProtocol.handshakeInfo(reply)

        // The server expects to be told the handshake finished; it is a notification, so no id and no
        // answer, and failing to deliver it is not worth failing the connection over.
        runCatching {
            val notify = post(McpProtocol.initializedNotification())
            transport.execute(notify, server.timeoutMillis).also { record(notify, it, "initialized") }
        }

        return McpHandshake(
            ok = true,
            serverName = name,
            serverVersion = version,
            protocolVersion = protocol,
            detail = if (sessionId != null) "connected, session held" else "connected",
            latencyMillis = result.latencyMillis,
        )
    }

    suspend fun listTools(): McpToolsResult {
        val id = nextId++
        val call = post(McpProtocol.toolsListBody(id))
        val result = transport.execute(call, server.timeoutMillis)
        record(call, result, "tools/list")
        if (!result.ok) {
            return McpToolsResult(ok = false, detail = if (result.isTransportFailure) result.message else "http ${result.status}")
        }
        val reply = McpProtocol.parse(result.body, id)
        val error = reply.error
        if (error != null) return McpToolsResult(ok = false, detail = error.message)
        val tools = McpProtocol.tools(reply, server.id)
        return McpToolsResult(
            ok = true,
            tools = tools,
            detail = if (tools.isEmpty()) "connected, but the server offers no tools" else "${tools.size} tool(s)",
        )
    }

    suspend fun callTool(name: String, arguments: Map<String, String>, parameters: List<McpParameter>): McpCallResult {
        val id = nextId++
        val call = post(McpProtocol.toolCallBody(id, name, McpProtocol.arguments(arguments, parameters)))
        val result = transport.execute(call, server.timeoutMillis)
        record(call, result, "tools/call $name")
        if (!result.ok) {
            return McpCallResult(
                ok = false,
                text = "",
                isError = true,
                detail = if (result.isTransportFailure) result.message else "http ${result.status}: ${result.message}",
            )
        }
        val outcome = McpProtocol.callResult(McpProtocol.parse(result.body, id))
        return outcome.copy(text = McpProtocol.clamp(outcome.text))
    }

    private fun post(body: String): HttpCall {
        val headers = mutableListOf(
            "content-type" to "application/json",
            "accept" to "application/json, text/event-stream",
        )
        val key = apiKey()
        when (server.auth) {
            McpAuth.BEARER -> if (!key.isNullOrBlank()) headers += "authorization" to "Bearer $key"
            McpAuth.HEADER -> if (!key.isNullOrBlank()) headers += server.authHeader.ifBlank { "x-api-key" } to key
            McpAuth.NONE -> Unit
        }
        sessionId?.let { headers += McpProtocol.SESSION_HEADER to it }
        return HttpCall(method = "POST", url = server.url, headers = headers, body = body)
    }

    /** Shape and timing, never the body: a tool call may be carrying somebody's save data. */
    private fun record(call: HttpCall, result: HttpResult, method: String) {
        onEvent(
            NetworkEvent(
                atMillis = System.currentTimeMillis(),
                provider = "mcp:${server.name}",
                model = method,
                method = call.method,
                url = call.loggedUrl,
                status = result.status,
                latencyMillis = result.latencyMillis,
                requestBytes = call.body.length,
                responseBytes = result.body.length,
                keyMask = NetworkPolicy.redactKey(apiKey()),
                cleartext = NetworkPolicy.assess(call.url) is NetworkPolicy.Verdict.LocalCleartext,
                error = if (result.ok) null else result.message.take(200),
            ),
        )
    }
}
