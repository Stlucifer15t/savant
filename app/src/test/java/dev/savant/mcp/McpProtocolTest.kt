package dev.savant.mcp

import dev.savant.ai.ParamType
import dev.savant.parsers.json.JsonPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP over streamable http, on the host.
 *
 * A connector is somebody else's program, so the two things that have to be exactly right are the
 * shape of what savant sends and the shape of what it accepts back — including an sse framed answer
 * and a json-rpc error, which are the two ways a server says "no".
 */
class McpProtocolTest {

    @Test
    fun initializeIsJsonRpcWithAProtocolVersionAndClientInfo() {
        val body = JsonPayload.read(McpProtocol.initializeBody(7))

        assertEquals("2.0", body.string("jsonrpc"))
        assertEquals("initialize", body.string("method"))
        assertEquals(7, body.int("id"))
        val params = body.obj("params")!!
        assertEquals(McpProtocol.PROTOCOL_VERSION, params.string("protocolVersion"))
        assertEquals("savant", params.obj("clientInfo")?.string("name"))
        assertTrue(params.obj("capabilities") != null)
    }

    @Test
    fun theInitializedNotificationHasNoIdBecauseItExpectsNoAnswer() {
        val body = JsonPayload.read(McpProtocol.initializedNotification())
        assertEquals("notifications/initialized", body.string("method"))
        assertNull(body["id"])
    }

    @Test
    fun aToolCallCarriesTheNameAndItsArguments() {
        val body = JsonPayload.read(
            McpProtocol.toolCallBody(3, "search_wiki", JsonPayload.read("""{"query":"crc32"}""")),
        )
        assertEquals("tools/call", body.string("method"))
        assertEquals(3, body.int("id"))
        assertEquals("search_wiki", body.obj("params")?.string("name"))
        assertEquals("crc32", body.obj("params")?.obj("arguments")?.string("query"))
    }

    @Test
    fun sseFramingIsTakenOffBeforeAnythingIsParsed() {
        val streamed = """
            event: message
            data: {"jsonrpc":"2.0","id":4,"result":{"tools":[]}}

            data: [DONE]
        """.trimIndent()

        assertEquals("""{"jsonrpc":"2.0","id":4,"result":{"tools":[]}}""", McpProtocol.unwrap(streamed, 4))

        val plain = """{"jsonrpc":"2.0","id":4,"result":{}}"""
        assertEquals(plain, McpProtocol.unwrap(plain, 4))
    }

    @Test
    fun whenAStreamCarriesSeveralFramesTheMatchingIdWins() {
        val streamed = "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}\n\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"second\"}]}}\n"

        val reply = McpProtocol.parse(streamed, 2)
        assertEquals(2, reply.id)
        assertEquals("second", McpProtocol.tools(reply, "srv").single().name)
    }

    @Test
    fun aToolsListBecomesToolSpecsUnderNamespacedNames() {
        val body = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[
              {"name":"search_wiki","description":"look things up",
               "inputSchema":{"type":"object","properties":{
                 "query":{"type":"string","description":"what to find"},
                 "limit":{"type":"integer","description":"how many"}},
                 "required":["query"]}},
              {"name":"ping","description":""}
            ]}}
        """.trimIndent()

        val tools = McpProtocol.tools(McpProtocol.parse(body, 1), "wiki")

        assertEquals(2, tools.size)
        val search = tools[0]
        assertEquals("search_wiki", search.name)
        assertEquals("search_wiki__wiki", search.qualifiedName)
        assertEquals(2, search.parameters.size)
        assertTrue(search.parameters.first { it.name == "query" }.required)
        assertFalse(search.parameters.first { it.name == "limit" }.required)
        assertEquals("integer", search.parameters.first { it.name == "limit" }.type)
        assertEquals(0, tools[1].parameters.size)

        val spec = McpProtocol.toolSpec(search, "the wiki")
        assertEquals("search_wiki__wiki", spec.name)
        assertTrue(spec.description.startsWith("[mcp · the wiki]"))
        assertEquals(ParamType.STRING, spec.parameters.first { it.name == "query" }.type)
        assertEquals(ParamType.INTEGER, spec.parameters.first { it.name == "limit" }.type)
    }

    @Test
    fun qualifiedNamesSplitBackIntoServerAndTool() {
        assertEquals("search_wiki" to "wiki", McpTool.split("search_wiki__wiki"))
        assertEquals("b__c" to "a", McpTool.split("b__c__a"))
        assertNull(McpTool.split("plain_name"))
        assertNull(McpTool.split("__wiki"))
        assertNull(McpTool.split("tool__"))
    }

    @Test
    fun argumentsAreTypedTheWayTheSchemaAsked() {
        val parameters = listOf(
            McpParameter("count", "integer"),
            McpParameter("ratio", "number"),
            McpParameter("deep", "boolean"),
            McpParameter("query", "string"),
        )

        val json = JsonPayload.write(
            McpProtocol.arguments(
                mapOf("count" to "42", "ratio" to "0.5", "deep" to "true", "query" to "crc32"),
                parameters,
            ),
        )

        assertEquals("""{"count":42,"ratio":0.5,"deep":true,"query":"crc32"}""", json)
    }

    @Test
    fun anArgumentThatDoesNotParseIsSentAsTextRatherThanDropped() {
        val json = JsonPayload.write(
            McpProtocol.arguments(mapOf("count" to "many"), listOf(McpParameter("count", "integer"))),
        )
        assertEquals("""{"count":"many"}""", json)
    }

    @Test
    fun aToolCallResultJoinsItsTextBlocks() {
        val body = """
            {"jsonrpc":"2.0","id":9,"result":{"isError":false,"content":[
              {"type":"text","text":"first line"},{"type":"text","text":"second line"},{"type":"image"}]}}
        """.trimIndent()

        val result = McpProtocol.callResult(McpProtocol.parse(body, 9))

        assertTrue(result.ok)
        assertTrue(result.text.contains("first line\nsecond line"))
        // Content savant cannot render is reported, not silently dropped.
        assertTrue(result.text.contains("[image content]"))
    }

    @Test
    fun aServersOwnErrorFlagSurvives() {
        val body = """{"jsonrpc":"2.0","id":9,"result":{"isError":true,"content":[{"type":"text","text":"no such page"}]}}"""
        val result = McpProtocol.callResult(McpProtocol.parse(body, 9))
        assertFalse(result.ok)
        assertTrue(result.isError)
        assertEquals("no such page", result.text)
    }

    @Test
    fun aJsonRpcErrorBecomesADetailAndNotAnException() {
        val body = """{"jsonrpc":"2.0","id":9,"error":{"code":-32601,"message":"method not found"}}"""
        val result = McpProtocol.callResult(McpProtocol.parse(body, 9))
        assertFalse(result.ok)
        assertTrue(result.detail!!.contains("-32601"))
        assertTrue(result.detail!!.contains("method not found"))
    }

    @Test
    fun anAnswerThatIsNotJsonRpcSaysSo() {
        val reply = McpProtocol.parse("<html>not this</html>", 1)
        assertTrue(reply.error != null)
        assertTrue(reply.error!!.message.contains("json-rpc"))
    }

    @Test
    fun aLongResultIsClampedBeforeAModelSeesIt() {
        val long = "x".repeat(McpProtocol.MAX_RESULT_CHARS + 500)
        val clamped = McpProtocol.clamp(long)
        assertTrue(clamped.length < long.length)
        assertTrue(clamped.endsWith("characters]"))
        val short = long.take(100)
        assertEquals(short, McpProtocol.clamp(short))
    }

    @Test
    fun aHandshakeReportsTheServerAndItsProtocol() {
        val body = """
            {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26",
             "serverInfo":{"name":"wiki connector","version":"1.4.2"},"capabilities":{"tools":{}}}}
        """.trimIndent()

        val (name, version, protocol) = McpProtocol.handshakeInfo(McpProtocol.parse(body, 1))
        assertEquals("wiki connector", name)
        assertEquals("1.4.2", version)
        assertEquals("2025-03-26", protocol)
    }

    // ---- the stored document ---------------------------------------------------------

    @Test
    fun serversAndTheirCachedToolsRoundTrip() {
        val server = McpServer(
            id = "wiki",
            name = "the wiki",
            url = "https://mcp.example.com/mcp",
            auth = McpAuth.BEARER,
            confirmEveryCall = true,
            serverVersion = "1.4.2",
            protocolVersion = McpProtocol.PROTOCOL_VERSION,
            lastSeen = 1_700_000_000_000,
        )
        val tools = listOf(
            McpTool(
                serverId = "wiki",
                name = "search_wiki",
                description = "look things up",
                parameters = listOf(McpParameter("query", "string", "what to find", required = true)),
            ),
        )
        val set = McpSet(listOf(server), mapOf("wiki" to tools))

        val back = McpCodec.decode(McpCodec.encode(set))

        assertEquals(set.servers, back.servers)
        assertEquals(set.tools, back.tools)
        assertEquals(listOf(server), back.enabled)
        // A key is not part of a connector, so it cannot leak into the document.
        assertFalse(McpCodec.encode(set).contains("authorization"))
    }

    @Test
    fun deletingAServerDeletesItsToolsToo() {
        val set = McpSet(
            servers = listOf(McpServer(id = "a", name = "a", url = "https://a.example/mcp")),
            tools = mapOf("a" to listOf(McpTool(serverId = "a", name = "do", description = ""))),
        )

        val after = set.withoutServer("a")
        assertTrue(after.servers.isEmpty())
        assertTrue(after.tools.isEmpty())
    }

    @Test
    fun toolsForAServerThatIsGoneAreIgnored() {
        val document = """{"servers":[{"id":"a","name":"a","url":"https://a.example/mcp"}],
            "tools":{"a":[{"name":"keep","description":""}],"ghost":[{"name":"gone","description":""}]}}"""
        val back = McpCodec.decode(document)
        assertEquals(listOf("a"), back.tools.keys.toList())
    }

    @Test
    fun aCorruptOrMissingDocumentIsAnEmptySet() {
        assertEquals(McpSet(), McpCodec.decode(null))
        assertEquals(McpSet(), McpCodec.decode(""))
        assertEquals(McpSet(), McpCodec.decode("{not json"))
    }

    @Test
    fun aServerWithNoUrlIsNotAServer() {
        assertNull(McpCodec.decodeServer(JsonPayload.read("""{"id":"a","name":"a","url":"  "}""")))
        assertNull(McpCodec.decodeServer(JsonPayload.read("""{"id":"a","name":"a"}""")))
    }

    @Test
    fun defaultsAreTheSafeOnes() {
        val server = McpCodec.decodeServer(JsonPayload.read("""{"url":"https://a.example/mcp"}"""))!!
        assertTrue(server.enabled)
        assertTrue("a connector confirms every call until the user says otherwise", server.confirmEveryCall)
        assertEquals(McpAuth.NONE, server.auth)
        assertEquals(McpServer.DEFAULT_TIMEOUT_MILLIS, server.timeoutMillis)
        assertEquals("https://a.example/mcp", server.name)
        assertTrue(server.id.isNotBlank())
    }
}
