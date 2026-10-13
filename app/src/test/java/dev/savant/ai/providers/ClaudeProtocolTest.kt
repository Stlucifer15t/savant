package dev.savant.ai.providers

import dev.savant.ai.LlmMessage
import dev.savant.ai.LlmRequest
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderProfile
import dev.savant.ai.StopReason
import dev.savant.ai.ToolCall
import dev.savant.ai.ToolCatalog
import dev.savant.ai.net.TransportException
import dev.savant.parsers.json.JsonPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anthropic `messages` dialect.
 *
 * The three things that break a careless adapter: a system prompt that is a field not a message,
 * `input_schema` instead of a `function` wrapper, and tool results that are user turns — two of them
 * have to arrive merged or the api refuses the conversation.
 */
class ClaudeProtocolTest {

    private val protocol = ClaudeProtocol

    private fun profile() = ProviderProfile(
        id = "p1",
        name = "claude",
        provider = ProviderKind.CLAUDE,
        baseUrl = "",
        model = "claude-sonnet-4-5",
    )

    private fun request(messages: List<LlmMessage>) = LlmRequest(
        messages = messages,
        tools = ToolCatalog.ALL,
        systemPrompt = "you are savant",
        temperature = 0.2f,
        maxOutputTokens = 512,
    )

    @Test
    fun endpointAndHeadersMatchTheApi() {
        assertEquals("https://api.anthropic.com/v1/messages", protocol.endpoint(profile(), streaming = false))
        val headers = protocol.headers(profile(), "sk-ant-123")
        assertEquals("sk-ant-123", headers["x-api-key"])
        assertEquals("2023-06-01", headers["anthropic-version"])
        // The key must never travel in the url: that is what ends up in logs.
        assertFalse(protocol.endpoint(profile(), streaming = false).contains("key"))
    }

    @Test
    fun systemPromptIsATopLevelField() {
        val body = JsonPayload.read(protocol.body(profile(), request(listOf(LlmMessage(LlmMessage.Role.USER, "hi"))), streaming = false))
        assertEquals("you are savant", body.string("system"))
        val roles = body.list("messages").map { it.string("role") }
        assertEquals(listOf("user"), roles)
        assertEquals(512L, body.long("max_tokens"))
    }

    @Test
    fun toolsUseInputSchema() {
        val body = JsonPayload.read(protocol.body(profile(), request(listOf(LlmMessage(LlmMessage.Role.USER, "hi"))), streaming = false))
        val tool = body.list("tools").first { it.string("name") == "set_field" }
        val schema = tool.obj("input_schema")!!
        assertEquals("object", schema.string("type"))
        assertTrue(schema.strings("required").contains("path"))
        // No `function` wrapper here, unlike openai.
        assertNull(tool.get("function"))
        assertEquals("auto", body.obj("tool_choice")!!.string("type"))
    }

    @Test
    fun twoToolResultsBecomeOneUserTurn() {
        val messages = listOf(
            LlmMessage(LlmMessage.Role.USER, "max the gold"),
            LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                text = "checking",
                toolCalls = listOf(
                    ToolCall("toolu_1", "set_field", mapOf("path" to "player.gold", "value" to "10")),
                    ToolCall("toolu_2", "get_field", mapOf("path" to "player.hp")),
                ),
            ),
            LlmMessage(LlmMessage.Role.TOOL, "player.gold: 5 -> 10", toolCallId = "toolu_1", toolName = "set_field"),
            LlmMessage(LlmMessage.Role.TOOL, "player.hp = 40", toolCallId = "toolu_2", toolName = "get_field"),
        )
        val turns = JsonPayload.read(protocol.body(profile(), request(messages), streaming = false)).list("messages")
        assertEquals(3, turns.size)
        val last = turns.last()
        assertEquals("user", last.string("role"))
        val blocks = (last.get("content") as dev.savant.parsers.json.Json.Arr).items
        assertEquals(2, blocks.size)
        assertEquals("tool_result", blocks[0].let { (it as dev.savant.parsers.json.Json.Obj).string("type") })
        assertEquals("toolu_1", blocks[0].let { (it as dev.savant.parsers.json.Json.Obj).string("tool_use_id") })
        assertEquals("toolu_2", blocks[1].let { (it as dev.savant.parsers.json.Json.Obj).string("tool_use_id") })

        val assistant = turns[1]
        val assistantBlocks = (assistant.get("content") as dev.savant.parsers.json.Json.Arr).items
        val toolUse = assistantBlocks[1].let { it as dev.savant.parsers.json.Json.Obj }
        assertEquals("tool_use", toolUse.string("type"))
        assertEquals("toolu_1", toolUse.string("id"))
        assertEquals("player.gold", toolUse.obj("input")!!.string("path"))
    }

    @Test
    fun aTextAnswerIsParsed() {
        val response = protocol.parse(
            """
            {"model":"claude-sonnet-4-5","stop_reason":"end_turn","content":[{"type":"text","text":"player.gold is 5000"}],
             "usage":{"input_tokens":900,"output_tokens":12}}
            """.trimIndent(),
            profile(),
        )
        assertEquals("player.gold is 5000", response.text)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals(StopReason.STOP, response.stopReason)
        assertEquals(900, response.inputTokens)
        assertEquals(12, response.outputTokens)
    }

    @Test
    fun aToolUseBlockIsParsed() {
        val response = protocol.parse(
            """
            {"stop_reason":"tool_use","content":[
              {"type":"text","text":"setting it"},
              {"type":"tool_use","id":"toolu_9","name":"set_field","input":{"path":"player.gold","value":"777"}}
            ],"usage":{"input_tokens":10,"output_tokens":20}}
            """.trimIndent(),
            profile(),
        )
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals("setting it", response.text)
        assertEquals(1, response.toolCalls.size)
        assertEquals("player.gold", response.toolCalls.first().arg("path"))
        assertEquals("777", response.toolCalls.first().arg("value"))
    }

    @Test
    fun errorsAreSentences() {
        val rejected = protocol.error(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        assertTrue(rejected.message.startsWith("the key was rejected (http 401)"))
        assertTrue(rejected.message.contains("invalid x-api-key"))
    }

    @Test
    fun aStreamedAnswerIsAssembledFromTypedEvents() {
        val reader = protocol.streamReader(profile())
        var text = ""
        listOf(
            """{"type":"message_start","message":{"usage":{"input_tokens":950,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"player."}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"gold is 5000"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":14}}""",
            """{"type":"message_stop"}""",
        ).forEach { event -> reader.feed(event)?.let { text += it.textDelta } }
        val response = reader.finish()
        assertEquals("player.gold is 5000", text)
        assertEquals(StopReason.STOP, response.stopReason)
        assertEquals(950, response.inputTokens)
        assertEquals(14, response.outputTokens)
    }

    @Test
    fun aStreamedToolCallIsAssembledFromPartialJson() {
        val reader = protocol.streamReader(profile())
        listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_3","name":"set_field"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"player."}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"gold\",\"value\":\"4\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
        ).forEach { reader.feed(it) }
        val response = reader.finish()
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals(1, response.toolCalls.size)
        assertEquals("toolu_3", response.toolCalls.first().id)
        assertEquals("player.gold", response.toolCalls.first().arg("path"))
    }

    @Test
    fun aMidStreamErrorIsReported() {
        val reader = protocol.streamReader(profile())
        val thrown = runCatching {
            reader.feed("""{"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}""")
        }.exceptionOrNull()
        assertTrue(thrown is TransportException)
        assertTrue((thrown as TransportException).message!!.contains("overloaded"))
    }
}
