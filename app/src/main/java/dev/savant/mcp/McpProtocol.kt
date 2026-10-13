package dev.savant.mcp

import dev.savant.ai.ToolSpec
import dev.savant.ai.ToolParameter
import dev.savant.ai.ParamType
import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import dev.savant.parsers.json.arr
import dev.savant.parsers.json.bool
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/** One json-rpc reply, after any sse framing has been taken off. */
data class McpReply(
    val id: Int?,
    val result: Json?,
    val error: McpError?,
) {
    val isNotification: Boolean get() = id == null && error == null
}

data class McpError(val code: Int?, val message: String)

/**
 * MCP over streamable http, as data.
 *
 * The protocol is json-rpc 2.0 in a POST body, with three shapes savant cares about: `initialize`,
 * `tools/list` and `tools/call`. A server may answer with a json body or with an sse stream, so both
 * are unwrapped here and nothing above this object knows the difference.
 *
 * There is no stdio transport and no subprocess anywhere in this package. Remote only, by design: an
 * app that edits save files has no business spawning programs.
 */
object McpProtocol {

    /** The version savant asks for. A server that answers with another one is still usable. */
    const val PROTOCOL_VERSION = "2025-03-26"

    /** Sent back on every request after the handshake, because the server asked for it. */
    const val SESSION_HEADER = "mcp-session-id"

    const val CLIENT_NAME = "savant"
    const val CLIENT_VERSION = "0.1"

    fun initializeBody(id: Int): String = body(
        "initialize",
        obj(
            "protocolVersion" to str(PROTOCOL_VERSION),
            "capabilities" to obj(),
            "clientInfo" to obj("name" to str(CLIENT_NAME), "version" to str(CLIENT_VERSION)),
        ),
        id,
    )

    /** The polite no-answer notification that follows a successful handshake. */
    fun initializedNotification(): String = body("notifications/initialized", null, null)

    fun toolsListBody(id: Int): String = body("tools/list", obj(), id)

    fun toolCallBody(id: Int, name: String, arguments: Json): String =
        body("tools/call", obj("name" to str(name), "arguments" to arguments), id)

    fun body(method: String, params: Json?, id: Int?): String {
        val base = obj("jsonrpc" to str("2.0"), "method" to str(method))
        val withParams = if (params == null) base else base.with("params", params)
        return JsonPayload.write(if (id == null) withParams else withParams.with("id", num(id)))
    }

    /**
     * Takes sse framing off a response body.
     *
     * A streamable http server may answer `text/event-stream` with one or more `data:` lines; the
     * json-rpc reply savant asked for is the last data frame that carries the matching id, or the last
     * one at all when ids are not being echoed.
     */
    fun unwrap(rawBody: String, id: Int?): String {
        val body = rawBody.trim()
        if (!body.contains("\ndata:") && !body.startsWith("data:") && !body.startsWith("event:")) return body
        val frames = body.lineSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .toList()
        if (frames.isEmpty()) return body
        if (id == null) return frames.last()
        val matching = frames.lastOrNull { frame ->
            runCatching { JsonPayload.read(frame).let { it is Json.Obj && it.long("id") == id.toLong() } }.getOrDefault(false)
        }
        return matching ?: frames.last()
    }

    fun parse(rawBody: String, id: Int?): McpReply {
        val json = runCatching { JsonPayload.read(unwrap(rawBody, id)) }.getOrNull() as? Json.Obj
            ?: return McpReply(id, null, McpError(null, "the server did not answer with json-rpc"))
        val error = json.obj("error")
        return McpReply(
            id = json.int("id"),
            result = json.obj("result"),
            error = error?.let { McpError(it.int("code"), it.string("message") ?: "unexplained error") },
        )
    }

    /** Server info out of a handshake reply. */
    fun handshakeInfo(reply: McpReply): Triple<String?, String?, String?> {
        val result = reply.result ?: return Triple(null, null, null)
        val info = result.obj("serverInfo")
        return Triple(info?.string("name"), info?.string("version"), result.string("protocolVersion"))
    }

    /** `tools/list` → the tools a server offers, with their parameter schemas. */
    fun tools(reply: McpReply, serverId: String): List<McpTool> {
        val list = reply.result?.list("tools") ?: return emptyList()
        return list.mapNotNull { item ->
            val tool = item as? Json.Obj ?: return@mapNotNull null
            val name = tool.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val schema = tool.obj("inputSchema")
            val required = schema?.strings("required") ?: emptyList()
            val properties = schema?.obj("properties")
            McpTool(
                serverId = serverId,
                name = name,
                description = tool.string("description").orEmpty(),
                parameters = properties?.members?.map { (key, value) ->
                    val property = value as? Json.Obj
                    McpParameter(
                        name = key,
                        type = property?.string("type") ?: "string",
                        description = property?.string("description").orEmpty(),
                        required = key in required,
                        options = property?.strings("enum") ?: emptyList(),
                    )
                } ?: emptyList(),
            )
        }
    }

    /** A cached tool, as the model sees it. */
    fun toolSpec(tool: McpTool, serverName: String): ToolSpec = ToolSpec(
        name = tool.qualifiedName,
        description = "[mcp · $serverName] ${tool.description}".trim().take(DESCRIPTION_LIMIT),
        parameters = tool.parameters.map { parameter ->
            ToolParameter(
                name = parameter.name,
                type = paramType(parameter.type),
                description = parameter.description.ifBlank { parameter.name },
                required = parameter.required,
                options = parameter.options,
            )
        },
    )

    private fun paramType(type: String): ParamType = when (type.lowercase()) {
        "number" -> ParamType.NUMBER
        "integer" -> ParamType.INTEGER
        "boolean" -> ParamType.BOOLEAN
        "array" -> ParamType.ARRAY
        else -> ParamType.STRING
    }

    /**
     * Tool arguments, typed the way the server's schema asked.
     *
     * A model hands savant strings, and a server that declared `integer` will reject `"42"`. Anything
     * that does not parse as the declared type is sent as a string rather than dropped: the server's
     * own error is more useful than savant guessing.
     */
    fun arguments(arguments: Map<String, String>, parameters: List<McpParameter>): Json.Obj {
        val byName = parameters.associateBy { it.name }
        return obj(
            arguments.map { (key, value) ->
                key to when (byName[key]?.type?.lowercase()) {
                    "integer" -> value.trim().toLongOrNull()?.let { num(it) } ?: str(value)
                    "number" -> value.trim().toDoubleOrNull()?.let { num(it) } ?: str(value)
                    "boolean" -> when {
                        value.equals("true", true) -> bool(true)
                        value.equals("false", true) -> bool(false)
                        else -> str(value)
                    }

                    else -> str(value)
                }
            },
        )
    }

    /** `tools/call` → the text content blocks, joined, plus the server's own error flag. */
    fun callResult(reply: McpReply): McpCallResult {
        val error = reply.error
        if (error != null) {
            return McpCallResult(ok = false, text = "", isError = true, detail = "mcp error ${error.code ?: ""}: ${error.message}")
        }
        val result = reply.result
            ?: return McpCallResult(ok = false, text = "", isError = true, detail = "the server answered with no result")
        val content = result.list("content")
        val text = content.mapNotNull { block ->
            val item = block as? Json.Obj ?: return@mapNotNull null
            when (item.string("type")) {
                "text" -> item.string("text")
                // Anything savant cannot render is reported, not silently dropped.
                else -> "[${item.string("type") ?: "unknown"} content]"
            }
        }.joinToString("\n")
        val isError = result.bool("isError") ?: false
        return McpCallResult(
            ok = !isError,
            text = text.ifBlank { if (isError) "" else "the server returned no text" },
            isError = isError,
        )
    }

    /** What goes into a request body is capped: a tool call is not a place to smuggle a save file. */
    const val DESCRIPTION_LIMIT = 400
    const val MAX_RESULT_CHARS = 8_000

    fun clamp(text: String): String =
        if (text.length <= MAX_RESULT_CHARS) text else text.take(MAX_RESULT_CHARS) + "\n[truncated at $MAX_RESULT_CHARS characters]"
}
