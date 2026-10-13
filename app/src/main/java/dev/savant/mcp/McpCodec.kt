package dev.savant.mcp

import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import dev.savant.parsers.json.arr
import dev.savant.parsers.json.bool
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/** The stored connector list, plus the tools each one offered the last time savant asked. */
data class McpSet(
    val servers: List<McpServer> = emptyList(),
    val tools: Map<String, List<McpTool>> = emptyMap(),
) {
    val enabled: List<McpServer> get() = servers.filter { it.enabled }

    fun withServer(server: McpServer): McpSet = McpSet(
        servers = servers.filterNot { it.id == server.id } + server,
        tools = tools,
    )

    fun withoutServer(id: String): McpSet = McpSet(
        servers = servers.filterNot { it.id == id },
        // A deleted server's tools are deleted with it, or the model keeps being offered a call that
        // cannot be made.
        tools = tools.filterKeys { it != id },
    )

    fun withTools(id: String, listed: List<McpTool>): McpSet = copy(tools = tools + (id to listed))
}

/**
 * Connectors ⇄ json, for the preferences file.
 *
 * Same rules as the profile and plugin codecs: a key is never part of the document, decoding never
 * throws, and a server from a newer build is skipped rather than fatal. The cached tool list is stored
 * with the server, because "what can the model call" has to be answerable without a network round
 * trip — a tool list is part of the prompt, and the prompt is built before anything is sent.
 */
object McpCodec {

    fun encode(set: McpSet): String = JsonPayload.write(
        obj(
            "servers" to arr(set.servers.map { encodeServer(it) }),
            "tools" to obj(
                set.tools.map { (serverId, tools) -> serverId to arr(tools.map { encodeTool(it) }) },
            ),
        ),
    )

    fun decode(text: String?): McpSet {
        if (text.isNullOrBlank()) return McpSet()
        val json = runCatching { JsonPayload.read(text) }.getOrNull() as? Json.Obj ?: return McpSet()
        val servers = json.list("servers").mapNotNull { decodeServer(it) }
        val ids = servers.map { it.id }.toSet()
        val tools = json.obj("tools")?.members?.mapNotNull { (serverId, value) ->
            if (serverId !in ids) return@mapNotNull null
            serverId to (value as? Json.Arr)?.items?.mapNotNull { decodeTool(it, serverId) } ?: emptyList()
        }?.toMap() ?: emptyMap()
        return McpSet(servers = servers, tools = tools)
    }

    fun encodeServer(server: McpServer): Json.Obj {
        val base = obj(
            "id" to str(server.id),
            "name" to str(server.name),
            "url" to str(server.url),
            "auth" to str(server.auth.id),
            "enabled" to bool(server.enabled),
            "confirmEveryCall" to bool(server.confirmEveryCall),
        )
        var out = base
        if (server.auth == McpAuth.HEADER) out = out.with("authHeader", str(server.authHeader))
        if (server.timeoutMillis != McpServer.DEFAULT_TIMEOUT_MILLIS) out = out.with("timeoutMillis", num(server.timeoutMillis))
        if (server.notes.isNotBlank()) out = out.with("notes", str(server.notes))
        server.protocolVersion?.let { out = out.with("protocolVersion", str(it)) }
        server.serverVersion?.let { out = out.with("serverVersion", str(it)) }
        server.lastSeen?.let { out = out.with("lastSeen", num(it)) }
        return out
    }

    fun decodeServer(json: Json): McpServer? {
        val item = json as? Json.Obj ?: return null
        val url = item.string("url")?.trim().orEmpty()
        if (url.isBlank()) return null
        val id = item.string("id")?.takeIf { it.isNotBlank() } ?: newId(item.string("name") ?: url)
        return McpServer(
            id = id,
            name = item.string("name")?.takeIf { it.isNotBlank() } ?: url,
            url = url,
            auth = McpAuth.fromId(item.string("auth")),
            authHeader = item.string("authHeader")?.takeIf { it.isNotBlank() } ?: "authorization",
            timeoutMillis = item.long("timeoutMillis") ?: McpServer.DEFAULT_TIMEOUT_MILLIS,
            enabled = item.bool("enabled") ?: true,
            confirmEveryCall = item.bool("confirmEveryCall") ?: true,
            notes = item.string("notes").orEmpty(),
            protocolVersion = item.string("protocolVersion"),
            serverVersion = item.string("serverVersion"),
            lastSeen = item.long("lastSeen"),
        )
    }

    fun encodeTool(tool: McpTool): Json.Obj = obj(
        "name" to str(tool.name),
        "description" to str(tool.description),
        "parameters" to arr(
            tool.parameters.map { parameter ->
                obj(
                    "name" to str(parameter.name),
                    "type" to str(parameter.type),
                    "description" to str(parameter.description),
                    "required" to bool(parameter.required),
                    "options" to arr(parameter.options.map { str(it) }),
                )
            },
        ),
    )

    fun decodeTool(json: Json, serverId: String): McpTool? {
        val item = json as? Json.Obj ?: return null
        val name = item.string("name")?.takeIf { it.isNotBlank() } ?: return null
        return McpTool(
            serverId = serverId,
            name = name,
            description = item.string("description").orEmpty(),
            parameters = item.list("parameters").mapNotNull { entry ->
                val parameter = entry as? Json.Obj ?: return@mapNotNull null
                McpParameter(
                    name = parameter.string("name") ?: return@mapNotNull null,
                    type = parameter.string("type") ?: "string",
                    description = parameter.string("description").orEmpty(),
                    required = parameter.bool("required") ?: false,
                    options = parameter.strings("options"),
                )
            },
        )
    }

    fun newId(name: String): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24)
        return slug.ifBlank { "server" } + "-" + System.currentTimeMillis().toString(36).takeLast(4)
    }
}
