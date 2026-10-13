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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `chat completions` dialect, which openai, openrouter and every self hosted endpoint speak.
 *
 * The assertions are about the wire format: what a tool looks like, how a tool call is re-typed,
 * and that a streamed answer is identical to a non streamed one.
 */
class OpenAiProtocolTest {

    private val protocol = OpenAiProtocol

    private fun profile(
        kind: ProviderKind = ProviderKind.OPENAI,
        model: String = "gpt-4o-mini",
        baseUrl: String = "",
    ) = ProviderProfile(
        id = "p1",
        name = "openai",
        provider = kind,
        baseUrl = baseUrl,
        model = model,
    )

    private fun request(
        messages: List<LlmMessage> = listOf(LlmMessage(LlmMessage.Role.USER, "max the gold")),
        system: String? = "you are savant",
    ) = LlmRequest(
        messages = messages,
        tools = ToolCatalog.ALL,
        systemPrompt = system,
        temperature = 0.2f,
        maxOutputTokens = 512,
    )

    // ---- request shape -----------------------------------------------------------

    @Test
    fun endpointFollowsTheProviderDefault() {
        assertEquals("https://api.openai.com/v1/chat/completions", protocol.endpoint(profile(), streaming = false))
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            protocol.endpoint(profile(ProviderKind.OPENROUTER, baseUrl = "https://openrouter.ai/api/v1"), streaming = false),
        )
        assertEquals(
            "http://192.168.1.9:11434/v1/chat/completions",
            protocol.endpoint(profile(ProviderKind.CUSTOM, baseUrl = "http://192.168.1.9:11434/v1/"), streaming = false),
        )
    }

    @Test
    fun keyGoesInTheAuthorizationHeader() {
        val headers = protocol.headers(profile(), "sk-test-1234")
        assertEquals("Bearer sk-test-1234", headers["Authorization"])
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun toolsAreWrappedAsFunctions() {
        val body = JsonPayload.read(protocol.body(profile(), request(), streaming = false))
        val tools = body.list("tools")
        assertEquals(8, tools.size)
        val setField = tools.map { it.obj("function")!! }.first { it.string("name") == "set_field" }
        assertEquals("function", tools.first().string("type"))
        val parameters = setField.obj("parameters")!!
        assertEquals("object", parameters.string("type"))
        assertEquals("string", parameters.obj("properties")!!["path"]!!.let { (it as dev.savant.parsers.json.Json.Obj).string("type") })
        assertTrue(parameters.strings("required").contains("value"))
        assertEquals("auto", body.string("tool_choice"))
    }

    @Test
    fun modelNamePicksTheTokenLimitField() {
        val classic = JsonPayload.read(protocol.body(profile(model = "gpt-4o-mini"), request(), streaming = false))
        assertEquals(512L, classic.long("max_tokens"))
        assertTrue(classic["max_completion_tokens"] == null)

        val modern = JsonPayload.read(protocol.body(profile(model = "o3-mini"), request(), streaming = false))
        assertEquals(512L, modern.long("max_completion_tokens"))
        assertTrue(modern["max_tokens"] == null)
    }

    @Test
    fun assistantToolCallsBecomeTypedArguments() {
        val messages = listOf(
            LlmMessage(LlmMessage.Role.USER, "max the gold"),
            LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                text = "",
                toolCalls = listOf(ToolCall("call_1", "set_field", mapOf("path" to "player.gold", "value" to "999999"))),
            ),
            LlmMessage(LlmMessage.Role.TOOL, "player.gold: 5000 -> 999999", toolCallId = "call_1", toolName = "set_field"),
        )
        val body = JsonPayload.read(protocol.body(profile(), request(messages), streaming = false))
        val turns = body.list("messages")
        val assistant = turns.first { it.string("role") == "assistant" }
        val call = assistant.list("tool_calls").first()
        // `arguments` is a json *string* in this dialect, not an object.
        val arguments = call.obj("function")!!.string("arguments")!!
        assertTrue(arguments.startsWith("{"))
        assertEquals("player.gold", JsonPayload.read(arguments).string("path"))
        assertEquals("999999", JsonPayload.read(arguments).string("value"))

        val tool = turns.first { it.string("role") == "tool" }
        assertEquals("call_1", tool.string("tool_call_id"))
        assertEquals("player.gold: 5000 -> 999999", tool.string("content"))
    }

    // ---- response shape ----------------------------------------------------------

    @Test
    fun textAnswerIsParsed() {
        val response = protocol.parse(
            """
            {"model":"gpt-4o-mini","choices":[{"message":{"role":"assistant","content":"player.gold is 5000"},
            "finish_reason":"stop"}],"usage":{"prompt_tokens":812,"completion_tokens":11}}
            """.trimIndent(),
            profile(),
        )
        assertEquals("player.gold is 5000", response.text)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals(StopReason.STOP, response.stopReason)
        assertEquals(812, response.inputTokens)
        assertEquals(11, response.outputTokens)
        assertEquals("gpt-4o-mini", response.model)
    }

    @Test
    fun toolCallArgumentsAreReTyped() {
        val response = protocol.parse(
            """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_9","type":"function","function":{"name":"set_field","arguments":"{\"path\":\"player.gold\",\"value\":\"777\"}"}}
            ]},"finish_reason":"tool_calls"}]}
            """.trimIndent(),
            profile(),
        )
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals(1, response.toolCalls.size)
        val call = response.toolCalls.first()
        assertEquals("call_9", call.id)
        assertEquals("set_field", call.name)
        assertEquals("player.gold", call.arg("path"))
        assertEquals("777", call.arg("value"))
    }

    @Test
    fun aFailedRequestDoesNotBecomeAResponse() {
        val thrown = runCatching {
            protocol.parse("""{"error":{"message":"invalid request: messages is required"}}""", profile())
        }.exceptionOrNull()
        assertTrue(thrown is TransportException)
        assertTrue(thrown!!.message!!.contains("messages is required"))
    }

    @Test
    fun statusCodesBecomeSentences() {
        val rejected = protocol.error(401, """{"error":{"message":"Incorrect API key provided"}}""")
        assertTrue(rejected.message.startsWith("the key was rejected (http 401)"))
        assertTrue(rejected.message.contains("Incorrect API key provided"))
        assertFalse(rejected.retryable)

        val limited = protocol.error(429, """{"error":{"message":"rate limit reached"}}""")
        assertTrue(limited.retryable)

        val down = protocol.error(503, "<html>gateway</html>")
        assertTrue(down.message.startsWith("the provider is having trouble (http 503)"))
        assertTrue(down.retryable)
    }

    // ---- streaming ---------------------------------------------------------------

    @Test
    fun streamedTextMatchesTheNonStreamedAnswer() {
        val reader = protocol.streamReader(profile())
        var text = ""
        listOf(
            """{"choices":[{"delta":{"role":"assistant","content":""}}]}""",
            """{"choices":[{"delta":{"content":"player."}}]}""",
            """{"choices":[{"delta":{"content":"gold is 5000"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":800,"completion_tokens":9}}""",
            "[DONE]",
        ).forEach { event -> reader.feed(event)?.let { text += it.textDelta } }
        val response = reader.finish()
        assertEquals("player.gold is 5000", text)
        assertEquals("player.gold is 5000", response.text)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals(StopReason.STOP, response.stopReason)
        assertEquals(800, response.inputTokens)
        assertEquals(9, response.outputTokens)
    }

    @Test
    fun streamedToolCallIsAccumulatedAcrossChunks() {
        val reader = protocol.streamReader(profile())
        listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_7","function":{"name":"set_field","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"play"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"er.gold\",\"value\":\"4\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        ).forEach { reader.feed(it) }
        val response = reader.finish()
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals(1, response.toolCalls.size)
        assertEquals("call_7", response.toolCalls.first().id)
        assertEquals("player.gold", response.toolCalls.first().arg("path"))
        assertEquals("4", response.toolCalls.first().arg("value"))
    }

    @Test
    fun anErrorInsideTheStreamIsReported() {
        val reader = protocol.streamReader(profile())
        val thrown = runCatching {
            reader.feed("""{"error":{"message":"context length exceeded"}}""")
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown is TransportException)
        assertTrue((thrown as TransportException).message!!.contains("context length exceeded"))
    }
}
