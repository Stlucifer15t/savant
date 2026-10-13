package dev.savant.ai.provider

import dev.savant.ai.LlmMessage
import dev.savant.ai.LlmRequest
import dev.savant.ai.LlmResponse
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderProfile
import dev.savant.ai.StopReason
import dev.savant.ai.ToolCall
import dev.savant.ai.ToolCatalog
import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three wire dialects.
 *
 * These are the tests that make a provider swap safe: the request shape, the headers, and the way an
 * answer — including a tool call — comes back. No socket is involved, because a protocol is a pure
 * function over data and a provider does not have to be reachable to check that.
 */
class ProviderProtocolTest {

    private val tools = listOf(ToolCatalog.ALL.first { it.name == "set_field" })

    private fun profile(
        kind: ProviderKind,
        model: String = "test-model",
        baseUrl: String = kind.defaultBaseUrl,
        temperature: Float = 0.1f,
        maxTokens: Int = 256,
    ) = ProviderProfile(
        id = "p1",
        name = kind.label,
        provider = kind,
        baseUrl = baseUrl,
        model = model,
        temperature = temperature,
        maxOutputTokens = maxTokens,
    )

    /**
     * Generation settings are set here rather than left to the defaults, because a protocol writes
     * what the request carries: filling them from the profile is [dev.savant.ai.RemoteLlmClient]'s
     * job, and `RemoteAssistantTest.generationSettingsComeFromTheProfileNotTheCaller` pins that.
     */
    private fun request(text: String = "set gold to 999") = LlmRequest(
        messages = listOf(LlmMessage(role = LlmMessage.Role.USER, text = text)),
        tools = tools,
        systemPrompt = "savant edits game save files.",
        temperature = 0.1f,
        maxOutputTokens = 256,
    )

    // ---- openai compatible ---------------------------------------------------------

    @Test
    fun openAiRequestCarriesModelMessagesAndTools() {
        val call = OpenAiProtocol.request(request(), profile(ProviderKind.OPENAI), "sk-test")

        assertEquals("POST", call.method)
        assertEquals("https://api.openai.com/v1/chat/completions", call.url)
        assertEquals("Bearer sk-test", call.headers.first { it.first == "authorization" }.second)

        val body = JsonPayload.read(call.body)
        assertEquals("test-model", body.string("model"))
        assertEquals(0.1, body.double("temperature") ?: 0.0, 0.0001)
        assertEquals(256, body.int("max_tokens") ?: -1)
        assertEquals(false, body.bool("stream"))

        val messages = body.list("messages").mapNotNull { it as? Json.Obj }
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].string("role"))
        assertEquals("savant edits game save files.", messages[0].string("content"))
        assertEquals("user", messages[1].string("role"))
        assertEquals("set gold to 999", messages[1].string("content"))

        val tool = body.list("tools").first() as Json.Obj
        assertEquals("function", tool.string("type"))
        val function = tool.obj("function")!!
        assertEquals("set_field", function.string("name"))
        val parameters = function.obj("parameters")!!
        assertEquals("object", parameters.string("type"))
        assertEquals("string", parameters.obj("properties")?.obj("path")?.string("type"))
        assertTrue(parameters.strings("required").contains("path"))
    }

    @Test
    fun openAiResponseWithToolCallsBecomesOneToolCall() {
        val body = """
            {"model":"test-model","usage":{"prompt_tokens":11,"completion_tokens":7},
             "choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[
                {"id":"call_1","type":"function","function":{"name":"set_field","arguments":"{\"path\":\"player.gold\",\"value\":\"999\"}"}}]}}]}
        """.trimIndent()

        val response = OpenAiProtocol.response(HttpResult(200, body))

        assertNull(response.text)
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals(11, response.inputTokens)
        assertEquals(7, response.outputTokens)
        assertEquals("test-model", response.model)
        assertEquals(
            ToolCall(id = "call_1", name = "set_field", arguments = mapOf("path" to "player.gold", "value" to "999")),
            response.toolCalls.single(),
        )
    }

    @Test
    fun openAiTextResponseHasNoToolCalls() {
        val body = """{"choices":[{"finish_reason":"stop","message":{"content":"gold is at player.gold"}}]}"""
        val response = OpenAiProtocol.response(HttpResult(200, body))

        assertEquals("gold is at player.gold", response.text)
        assertEquals(StopReason.STOP, response.stopReason)
        assertTrue(response.toolCalls.isEmpty())
        assertFalse(response.wantsToolCall)
    }

    @Test
    fun aProviderErrorBecomesAnErrorResponseNotAnException() {
        val response = OpenAiProtocol.response(HttpResult(401, """{"error":{"message":"Incorrect API key provided"}}"""))

        assertEquals(StopReason.ERROR, response.stopReason)
        assertTrue(response.text!!.contains("Incorrect API key"))
        assertTrue(response.toolCalls.isEmpty())
    }

    @Test
    fun assistantToolCallsAndToolResultsRoundTripThroughTheRequestBody() {
        val messages = listOf(
            LlmMessage(role = LlmMessage.Role.USER, text = "max the stats"),
            LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                text = "",
                toolCalls = listOf(ToolCall(id = "call_9", name = "set_field", arguments = mapOf("path" to "hp"))),
            ),
            LlmMessage(role = LlmMessage.Role.TOOL, text = "proposed hp = 999", toolCallId = "call_9", toolName = "set_field"),
        )

        val body = JsonPayload.read(OpenAiProtocol.request(LlmRequest(messages = messages), profile(ProviderKind.OPENAI), "k").body)
        val sent = body.list("messages").mapNotNull { it as? Json.Obj }

        assertEquals(3, sent.size)
        val assistant = sent[1]
        assertEquals("assistant", assistant.string("role"))
        val call = assistant.list("tool_calls").first() as Json.Obj
        assertEquals("call_9", call.string("id"))
        assertEquals("set_field", call.obj("function")?.string("name"))
        assertEquals("""{"path":"hp"}""", call.obj("function")?.string("arguments"))

        val result = sent[2]
        assertEquals("tool", result.string("role"))
        assertEquals("call_9", result.string("tool_call_id"))
        assertEquals("proposed hp = 999", result.string("content"))
    }

    @Test
    fun openRouterAsksForAttributionHeadersAndNothingElse() {
        val call = OpenAiProtocol.request(request(), profile(ProviderKind.OPENROUTER), "sk-or-test")
        assertEquals("savant", call.headers.first { it.first == "x-title" }.second)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", call.url)
        // A plain openai profile does not get them.
        assertFalse(OpenAiProtocol.request(request(), profile(ProviderKind.OPENAI), "k").headers.any { it.first == "x-title" })
    }

    // ---- anthropic -----------------------------------------------------------------

    @Test
    fun anthropicPutsTheSystemPromptTopLevelAndAuthInItsOwnHeader() {
        val call = AnthropicProtocol.request(request(), profile(ProviderKind.CLAUDE), "sk-ant-test")

        assertEquals("https://api.anthropic.com/v1/messages", call.url)
        assertEquals("sk-ant-test", call.headers.first { it.first == "x-api-key" }.second)
        assertTrue(call.headers.any { it.first == "anthropic-version" })
        // No bearer token: this dialect does not use one, and sending both would be a leak in a log.
        assertFalse(call.headers.any { it.first == "authorization" })

        val body = JsonPayload.read(call.body)
        assertEquals("savant edits game save files.", body.string("system"))
        assertEquals(256, body.int("max_tokens") ?: -1)
        assertEquals(1, body.list("messages").size)
        val first = body.list("messages").first() as Json.Obj
        assertEquals("user", first.string("role"))
        assertEquals("text", (first.list("content").first() as Json.Obj).string("type"))
        assertTrue(body.list("tools").first().let { (it as Json.Obj).obj("input_schema") != null })
    }

    @Test
    fun anthropicToolResultsTravelInsideAUserTurn() {
        val messages = listOf(
            LlmMessage(role = LlmMessage.Role.USER, text = "set gold"),
            LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                text = "on it",
                toolCalls = listOf(ToolCall(id = "tu_1", name = "set_field", arguments = mapOf("path" to "gold", "value" to "9"))),
            ),
            LlmMessage(role = LlmMessage.Role.TOOL, text = "proposed", toolCallId = "tu_1", toolName = "set_field"),
        )

        val body = JsonPayload.read(AnthropicProtocol.request(LlmRequest(messages = messages), profile(ProviderKind.CLAUDE), "k").body)
        val turns = body.list("messages").mapNotNull { it as? Json.Obj }

        assertEquals(3, turns.size)
        val assistantBlocks = turns[1].list("content").mapNotNull { it as? Json.Obj }
        assertEquals("text", assistantBlocks[0].string("type"))
        assertEquals("tool_use", assistantBlocks[1].string("type"))
        assertEquals("tu_1", assistantBlocks[1].string("id"))
        assertEquals("gold", assistantBlocks[1].obj("input")?.string("path"))

        // A tool result is a user turn here, not a role of its own.
        val resultTurn = turns[2]
        assertEquals("user", resultTurn.string("role"))
        val block = resultTurn.list("content").first() as Json.Obj
        assertEquals("tool_result", block.string("type"))
        assertEquals("tu_1", block.string("tool_use_id"))
        assertEquals("proposed", block.string("content"))
    }

    @Test
    fun anthropicResponseBlocksSplitIntoTextAndToolUse() {
        val body = """
            {"model":"claude","stop_reason":"tool_use","usage":{"input_tokens":5,"output_tokens":9},
             "content":[{"type":"text","text":"proposed one edit"},{"type":"tool_use","id":"tu_2","name":"set_field","input":{"path":"gold","value":"999"}}]}
        """.trimIndent()

        val response = AnthropicProtocol.response(HttpResult(200, body))

        assertEquals("proposed one edit", response.text)
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        assertEquals(5, response.inputTokens)
        assertEquals(9, response.outputTokens)
        assertEquals(
            ToolCall(id = "tu_2", name = "set_field", arguments = mapOf("path" to "gold", "value" to "999")),
            response.toolCalls.single(),
        )
    }

    // ---- gemini --------------------------------------------------------------------

    @Test
    fun geminiPutsTheModelInTheUrlAndTheKeyInAHeader() {
        val call = GeminiProtocol.request(request(), profile(ProviderKind.GEMINI, model = "gemini-test"), "AIza-test")

        assertTrue(call.url.endsWith("/models/gemini-test:generateContent"))
        assertEquals("AIza-test", call.headers.first { it.first == "x-goog-api-key" }.second)
        // The key never travels in the url, so a logged url cannot carry it.
        assertFalse(call.url.contains("AIza-test"))
        assertFalse(call.url.contains('?'))

        val body = JsonPayload.read(call.body)
        assertEquals("savant edits game save files.", body.obj("systemInstruction")?.list("parts")?.first()?.let { (it as Json.Obj).string("text") })
        assertEquals(0.1, body.obj("generationConfig")?.double("temperature") ?: 0.0, 0.0001)
        assertEquals(256, body.obj("generationConfig")?.int("maxOutputTokens") ?: -1)

        val contents = body.list("contents").mapNotNull { it as? Json.Obj }
        assertEquals("user", contents[0].string("role"))
        assertEquals("set gold to 999", (contents[0].list("parts").first() as Json.Obj).string("text"))
        val declaration = body.list("tools").first().let { (it as Json.Obj).list("functionDeclarations").first() } as Json.Obj
        assertEquals("set_field", declaration.string("name"))
    }

    @Test
    fun geminiUsesModelRoleAndFunctionParts() {
        val messages = listOf(
            LlmMessage(
                role = LlmMessage.Role.ASSISTANT,
                text = "",
                toolCalls = listOf(ToolCall(id = "ignored", name = "export", arguments = emptyMap())),
            ),
            LlmMessage(role = LlmMessage.Role.TOOL, text = "export ready", toolCallId = "ignored", toolName = "export"),
        )

        val body = JsonPayload.read(GeminiProtocol.request(LlmRequest(messages = messages), profile(ProviderKind.GEMINI), "k").body)
        val contents = body.list("contents").mapNotNull { it as? Json.Obj }

        assertEquals("model", contents[0].string("role"))
        assertEquals("export", (contents[0].list("parts").first() as Json.Obj).obj("functionCall")?.string("name"))

        assertEquals("user", contents[1].string("role"))
        val response = (contents[1].list("parts").first() as Json.Obj).obj("functionResponse")!!
        assertEquals("export", response.string("name"))
        assertEquals("export ready", response.obj("response")?.string("result"))
    }

    @Test
    fun geminiFunctionCallsGetAnIdBecauseTheApiDoesNotGiveOne() {
        val body = """
            {"modelVersion":"gemini-test","usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":4},
             "candidates":[{"finishReason":"STOP","content":{"parts":[
                {"text":"done"},{"functionCall":{"name":"set_field","args":{"path":"gold","value":"9"}}}]}}]}
        """.trimIndent()

        val response = GeminiProtocol.response(HttpResult(200, body))

        assertEquals("done", response.text)
        assertEquals(3, response.inputTokens)
        assertEquals(4, response.outputTokens)
        val call = response.toolCalls.single()
        assertEquals("set_field", call.name)
        assertEquals(mapOf("path" to "gold", "value" to "9"), call.arguments)
        assertTrue("savant synthesises an id so the shared shape still works: ${call.id}", call.id.startsWith("gemini_"))
    }

    // ---- shared behaviour ----------------------------------------------------------

    @Test
    fun theProtocolFollowsTheProviderKind() {
        assertTrue(ProviderProtocol.forKind(ProviderKind.CLAUDE) === AnthropicProtocol)
        assertTrue(ProviderProtocol.forKind(ProviderKind.GEMINI) === GeminiProtocol)
        assertTrue(ProviderProtocol.forKind(ProviderKind.OPENAI) === OpenAiProtocol)
        assertTrue(ProviderProtocol.forKind(ProviderKind.OPENROUTER) === OpenAiProtocol)
        assertTrue(ProviderProtocol.forKind(ProviderKind.CUSTOM) === OpenAiProtocol)
    }

    @Test
    fun aCustomBaseUrlIsJoinedWithoutDoublingTheSlash() {
        val withSlash = profile(ProviderKind.CUSTOM, baseUrl = "http://192.168.1.20:11434/v1/")
        val without = profile(ProviderKind.CUSTOM, baseUrl = "http://192.168.1.20:11434/v1")

        assertEquals("http://192.168.1.20:11434/v1/chat/completions", OpenAiProtocol.endpoint(withSlash))
        assertEquals("http://192.168.1.20:11434/v1/chat/completions", OpenAiProtocol.endpoint(without))
    }

    @Test
    fun anEmptyBaseUrlFallsBackToTheProvidersOwn() {
        val blank = profile(ProviderKind.CUSTOM, baseUrl = "   ")
        assertEquals("", blank.effectiveBaseUrl)
        val openai = profile(ProviderKind.OPENAI, baseUrl = "")
        assertEquals("https://api.openai.com/v1", openai.effectiveBaseUrl)
    }

    @Test
    fun aPingIsAGetWithNoBodyAndCarriesNoSaveData() {
        val ping = OpenAiProtocol.pingRequest(profile(ProviderKind.OPENAI), "sk-test")

        assertEquals("GET", ping.method)
        assertTrue(ping.url.endsWith("/models"))
        assertEquals("", ping.body)
        assertFalse(ping.body.contains("gold"))

        val ok = OpenAiProtocol.ping(HttpResult(200, """{"data":[]}""", latencyMillis = 42), profile(ProviderKind.OPENAI), "sk-test")
        assertTrue(ok.ok)
        assertEquals(42L, ok.latencyMillis)

        val refused = OpenAiProtocol.ping(HttpResult(401, """{"error":{"message":"bad key"}}"""), profile(ProviderKind.OPENAI), "sk-test")
        assertFalse(refused.ok)
        assertTrue(refused.detail.contains("refused"))
    }

    @Test
    fun toolArgumentsFlattenToTheStringMapSavantWorks() {
        val flat = ProviderProtocol.flatten(
            JsonPayload.read("""{"path":"player.gold","value":"999","max":true,"nested":{"a":1}}"""),
        )
        assertEquals("player.gold", flat["path"])
        assertEquals("999", flat["value"])
        assertEquals("true", flat["max"])
        assertEquals("""{"a":1}""", flat["nested"])
        assertTrue(ProviderProtocol.flatten(Json.Null).isEmpty())
    }

    @Test
    fun stopReasonsMapAcrossDialects() {
        assertEquals(StopReason.TOOL_USE, ProviderProtocol.stopReason("tool_calls", false))
        assertEquals(StopReason.TOOL_USE, ProviderProtocol.stopReason("tool_use", false))
        assertEquals(StopReason.LENGTH, ProviderProtocol.stopReason("max_tokens", false))
        assertEquals(StopReason.REFUSAL, ProviderProtocol.stopReason("SAFETY", false))
        assertEquals(StopReason.STOP, ProviderProtocol.stopReason("end_turn", false))
        // No finish reason at all: the presence of calls decides.
        assertEquals(StopReason.TOOL_USE, ProviderProtocol.stopReason(null, true))
        assertEquals(StopReason.STOP, ProviderProtocol.stopReason(null, false))
    }

    @Test
    fun aResponseIsNeverBuiltFromAnUnreadableBody() {
        val response: LlmResponse = AnthropicProtocol.response(HttpResult(200, "not json at all"))
        assertNull(response.text)
        assertTrue(response.toolCalls.isEmpty())
    }
}
