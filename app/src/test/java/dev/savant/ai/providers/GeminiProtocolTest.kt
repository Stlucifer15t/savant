package dev.savant.ai.providers

import dev.savant.ai.LlmMessage
import dev.savant.ai.LlmRequest
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderProfile
import dev.savant.ai.StopReason
import dev.savant.ai.ToolCall
import dev.savant.ai.ToolCatalog
import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Google's `generateContent` dialect: typed parts, `functionDeclarations`, and results addressed by
 * tool *name* rather than call id — which is why the loop carries both.
 */
class GeminiProtocolTest {

    private val protocol = GeminiProtocol

    private fun profile() = ProviderProfile(
        id = "p1",
        name = "gemini",
        provider = ProviderKind.GEMINI,
        baseUrl = "",
        model = "gemini-2.0-flash",
    )

    private fun request(messages: List<LlmMessage>) = LlmRequest(
        messages = messages,
        tools = ToolCatalog.ALL,
        systemPrompt = "you are savant",
        temperature = 0.2f,
        maxOutputTokens = 512,
    )

    private fun part(obj: Json): Json.Obj = obj as Json.Obj

    @Test
    fun endpointNamesTheModelAndTheMethod() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            protocol.endpoint(profile(), streaming = false),
        )
        assertTrue(protocol.endpoint(profile(), streaming = true).endsWith(":streamGenerateContent?alt=sse"))
        // The key is a header, so it never appears in a url, a log line or an error.
        assertFalse(protocol.endpoint(profile(), streaming = false).contains("key="))
        assertEquals("AIza-test", protocol.headers(profile(), "AIza-test")["x-goog-api-key"])
    }

    @Test
    fun turnsUseUserAndModelRoles() {
        val body = JsonPayload.read(
            protocol.body(
                profile(),
                request(
                    listOf(
                        LlmMessage(LlmMessage.Role.USER, "max the gold"),
                        LlmMessage(
                            role = LlmMessage.Role.ASSISTANT,
                            text = "setting it",
                            toolCalls = listOf(ToolCall("call_1", "set_field", mapOf("path" to "player.gold", "value" to "10"))),
                        ),
                    ),
                ),
                streaming = false,
            ),
        )
        assertEquals("you are savant", part(body.obj("systemInstruction")!!.list("parts").first()).string("text"))
        val contents = body.list("contents")
        assertEquals("user", contents[0].string("role"))
        assertEquals("model", contents[1].string("role"))
        val modelParts = part(contents[1]).list("parts")
        assertEquals("setting it", part(modelParts[0]).string("text"))
        val call = part(modelParts[1]).obj("functionCall")!!
        assertEquals("set_field", call.string("name"))
        assertEquals("player.gold", call.obj("args")!!.string("path"))
        assertEquals("10", call.obj("args")!!.string("value"))
        assertEquals(0.2, body.obj("generationConfig")!!.double("temperature")!!, 0.0001)
        assertEquals(512L, body.obj("generationConfig")!!.long("maxOutputTokens"))
    }

    @Test
    fun toolsAreFunctionDeclarationsWithUpperCaseTypes() {
        val body = JsonPayload.read(protocol.body(profile(), request(listOf(LlmMessage(LlmMessage.Role.USER, "hi"))), streaming = false))
        val declarations = body.list("tools").first().list("functionDeclarations")
        assertEquals(8, declarations.size)
        val setField = part(declarations.first { part(it).string("name") == "set_field" })
        val schema = setField.obj("parameters")!!
        assertEquals("OBJECT", schema.string("type"))
        val path = part(schema.obj("properties")!!["path"]!!)
        assertEquals("STRING", path.string("type"))
        val limit = part(declarations.first { part(it).string("name") == "list_fields" })
        assertEquals("INTEGER", part(limit.obj("parameters")!!.obj("properties")!!["limit"]!!).string("type"))
    }

    @Test
    fun twoFunctionResponsesMergeIntoOneTurn() {
        val messages = listOf(
            LlmMessage(LlmMessage.Role.USER, "max the gold"),
            LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                text = "",
                toolCalls = listOf(
                    ToolCall("call_1", "set_field", mapOf("path" to "player.gold", "value" to "10")),
                    ToolCall("call_2", "get_field", mapOf("path" to "player.hp")),
                ),
            ),
            LlmMessage(LlmMessage.Role.TOOL, "player.gold: 5 -> 10", toolCallId = "call_1", toolName = "set_field"),
            LlmMessage(LlmMessage.Role.TOOL, "player.hp = 40", toolCallId = "call_2", toolName = "get_field"),
        )
        val contents = JsonPayload.read(protocol.body(profile(), request(messages), streaming = false)).list("contents")
        assertEquals(3, contents.size)
        val results = part(contents.last()).list("parts").map { part(it).obj("functionResponse")!! }
        assertEquals(2, results.size)
        // Addressed by name, not by the call id openai and anthropic use.
        assertEquals("set_field", results[0].string("name"))
        assertEquals("player.gold: 5 -> 10", results[0].obj("response")!!.string("result"))
        assertEquals("get_field", results[1].string("name"))
    }

    @Test
    fun aTextAnswerIsParsed() {
        val response = protocol.parse(
            """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"player.gold is 5000"}]},"finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":700,"candidatesTokenCount":9},"modelVersion":"gemini-2.0-flash"}
            """.trimIndent(),
            profile(),
        )
        assertEquals("player.gold is 5000", response.text)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals(StopReason.STOP, response.stopReason)
        assertEquals(700, response.inputTokens)
        assertEquals(9, response.outputTokens)
    }

    @Test
    fun aFunctionCallIsParsed() {
        val response = protocol.parse(
            """
            {"candidates":[{"content":{"role":"model","parts":[
              {"functionCall":{"name":"set_field","args":{"path":"player.gold","value":"777"}}}
            ]},"finishReason":"STOP"}]}
            """.trimIndent(),
            profile(),
        )
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals("set_field", response.toolCalls.first().name)
        assertEquals("777", response.toolCalls.first().arg("value"))
    }

    @Test
    fun aSafetyStopIsNotAnAnswer() {
        val response = protocol.parse(
            """{"candidates":[{"content":{"parts":[]},"finishReason":"SAFETY"}]}""",
            profile(),
        )
        assertEquals(StopReason.REFUSAL, response.stopReason)
    }

    @Test
    fun errorsAreSentences() {
        val rejected = protocol.error(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""")
        assertTrue(rejected.message.contains("API key not valid"))
        val limited = protocol.error(429, """{"error":{"code":429,"message":"quota exceeded"}}""")
        assertTrue(limited.retryable)
    }

    @Test
    fun aStreamedAnswerIsAssembledFromWholeResponseChunks() {
        val reader = protocol.streamReader(profile())
        var text = ""
        listOf(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"player."}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"gold is "},{"text":"5000"}]}}]}""",
            """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"get_field","args":{"path":"player.hp"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3}}""",
        ).forEach { event -> reader.feed(event)?.let { text += it.textDelta } }
        val response = reader.finish()
        assertEquals("player.gold is 5000", text)
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals(1, response.toolCalls.size)
        assertEquals("player.hp", response.toolCalls.first().arg("path"))
        assertEquals(10, response.inputTokens)
        assertEquals(3, response.outputTokens)
    }
}
