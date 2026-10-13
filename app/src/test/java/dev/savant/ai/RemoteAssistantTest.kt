package dev.savant.ai

import dev.savant.ai.provider.HttpCall
import dev.savant.ai.provider.HttpResult
import dev.savant.ai.provider.HttpTransport
import dev.savant.ai.provider.NetworkEvent
import dev.savant.ai.provider.NetworkPolicy
import dev.savant.ai.provider.ProfileCodec
import dev.savant.ai.provider.ProfileSet
import dev.savant.domain.parser.ExportVerifier
import dev.savant.domain.parser.ParserRegistry
import dev.savant.domain.session.SaveEditor
import dev.savant.parsers.json.JsonSaveParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remote half of the assistant: what it sends, what it refuses to send, and what it does with a
 * tool call.
 *
 * The transport is a fake, so these run on the host in milliseconds — which is the point. A tool loop
 * that only works against a live provider is a tool loop nobody can test.
 */
class RemoteAssistantTest {

    private val registry = ParserRegistry(listOf(JsonSaveParser()))
    private val executor = ToolExecutor(SaveEditor(registry, ExportVerifier(registry)))

    private class FakeClient(
        private val replies: List<LlmResponse>,
        private val throws: Throwable? = null,
    ) : LlmClient {
        val requests = mutableListOf<LlmRequest>()
        private var index = 0
        override val providerId: String = "fake"
        override val label: String = "fake provider"

        override suspend fun complete(request: LlmRequest): LlmResponse {
            requests += request
            throws?.let { throw it }
            val reply = replies[minOf(index, replies.lastIndex)]
            index++
            return reply
        }

        override suspend fun ping(): ProviderPing = ProviderPing(ok = true, detail = "reachable", latencyMillis = 1)
    }

    private class FakeTransport(private val result: HttpResult) : HttpTransport {
        val calls = mutableListOf<HttpCall>()
        override suspend fun execute(call: HttpCall, timeoutMillis: Long): HttpResult {
            calls += call
            return result
        }
    }

    private fun toolResponse(name: String, vararg args: Pair<String, String>) = LlmResponse(
        text = null,
        toolCalls = listOf(ToolCall.of(name, *args)),
        stopReason = StopReason.TOOL_USE,
    )

    private fun textResponse(text: String) = LlmResponse(text = text, stopReason = StopReason.STOP)

    // ---- the tool loop -------------------------------------------------------------

    @Test
    fun aTextAnswerBecomesATextReply() = runBlocking {
        val client = FakeClient(listOf(textResponse("gold is at player.gold")))
        val reply = RemoteAssistant(client, executor).respond(AssistantInput("where is gold", null))

        assertTrue(reply is AssistantReply.Text)
        assertEquals(listOf("gold is at player.gold"), reply.lines)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun aToolCallWithNoFileOpenReportsItAndKeepsGoing() = runBlocking {
        val client = FakeClient(
            listOf(
                toolResponse("list_fields"),
                textResponse("attach a save file first"),
            ),
        )

        val reply = RemoteAssistant(client, executor).respond(AssistantInput("max the stats", null))

        // The tool ran, said there is no file, and its answer went back to the model as a tool turn.
        assertEquals(2, client.requests.size)
        val second = client.requests[1]
        val toolTurn = second.messages.last()
        assertEquals(LlmMessage.Role.TOOL, toolTurn.role)
        assertTrue(toolTurn.text.contains("no save file is open"))
        assertTrue(reply.lines.any { it.contains("no save file is open") })
        assertTrue(reply.lines.any { it.contains("attach a save file first") })
    }

    @Test
    fun theSystemPromptCarriesTheRulesAndTheFileState() = runBlocking {
        val client = FakeClient(listOf(textResponse("ok")))
        RemoteAssistant(client, executor).respond(AssistantInput("hello", null))

        val system = client.requests.single().systemPrompt!!
        assertTrue(system.contains("never invent a path"))
        assertTrue(system.contains("you never write bytes"))
        assertTrue(system.contains("not attached"))
        assertTrue(system.contains("the file"))
    }

    @Test
    fun aProviderErrorIsAFailureNotAnException() = runBlocking {
        val client = FakeClient(listOf(LlmResponse(text = "http 401: bad key", stopReason = StopReason.ERROR)))
        val reply = RemoteAssistant(client, executor).respond(AssistantInput("hello", null))

        assertTrue(reply is AssistantReply.Failure)
        assertTrue(reply.lines.any { it.contains("bad key") })
    }

    @Test
    fun aRefusalIsSaidPlainly() = runBlocking {
        val client = FakeClient(listOf(LlmResponse(text = null, stopReason = StopReason.REFUSAL)))
        val reply = RemoteAssistant(client, executor).respond(AssistantInput("hello", null))

        assertTrue(reply is AssistantReply.Failure)
        assertTrue(reply.lines.any { it.contains("declined") })
    }

    @Test
    fun aTransportThatThrowsBecomesAFailure() = runBlocking {
        val client = FakeClient(listOf(textResponse("never reached")), throws = java.io.IOException("connect timed out"))
        val reply = RemoteAssistant(client, executor).respond(AssistantInput("hello", null))

        assertTrue(reply is AssistantReply.Failure)
        assertTrue(reply.lines.any { it.contains("the provider call failed") })
        assertTrue(reply.lines.any { it.contains("connect timed out") })
    }

    @Test
    fun theLoopIsBoundedSoAModelCannotSpin() = runBlocking {
        val client = FakeClient(listOf(toolResponse("list_fields")))
        val reply = RemoteAssistant(client, executor, maxRounds = 3).respond(AssistantInput("max everything", null))

        assertEquals(3, client.requests.size)
        assertTrue(reply.lines.any { it.contains("stopped after 3 tool rounds") })
    }

    @Test
    fun anEmptyAnswerIsAFailureRatherThanASilentNothing() = runBlocking {
        val client = FakeClient(listOf(LlmResponse(text = "   ", stopReason = StopReason.STOP)))
        val reply = RemoteAssistant(client, executor).respond(AssistantInput("hello", null))
        assertTrue(reply is AssistantReply.Failure)
    }

    // ---- the client: what leaves the phone -----------------------------------------

    private fun profile(
        kind: ProviderKind = ProviderKind.OPENAI,
        baseUrl: String = kind.defaultBaseUrl,
        model: String = "test-model",
    ) = ProviderProfile(id = "p1", name = "test", provider = kind, baseUrl = baseUrl, model = model, temperature = 0.1f, maxOutputTokens = 256)

    private val okResult = HttpResult(
        status = 200,
        body = """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}],"usage":{"prompt_tokens":4,"completion_tokens":2}}""",
        latencyMillis = 12,
    )

    @Test
    fun cleartextToARemoteHostIsRefusedBeforeAnythingIsSent() = runBlocking {
        val transport = FakeTransport(okResult)
        val client = RemoteLlmClient(profile(baseUrl = "http://api.example.com/v1"), { "sk-test" }, transport)

        val response = client.complete(LlmRequest(messages = listOf(LlmMessage(LlmMessage.Role.USER, "hello"))))

        assertEquals(StopReason.ERROR, response.stopReason)
        assertTrue(response.text!!.contains("not allowed"))
        assertTrue("nothing may reach the network", transport.calls.isEmpty())
    }

    @Test
    fun cleartextToAMachineOnYourOwnNetworkIsAllowed() = runBlocking {
        val transport = FakeTransport(okResult)
        val events = mutableListOf<NetworkEvent>()
        val client = RemoteLlmClient(
            profile(kind = ProviderKind.CUSTOM, baseUrl = "http://192.168.1.20:11434/v1"),
            { "local-key" },
            transport,
            onEvent = { events += it },
        )

        val response = client.complete(LlmRequest(messages = listOf(LlmMessage(LlmMessage.Role.USER, "hello"))))

        assertEquals("ok", response.text)
        assertEquals(1, transport.calls.size)
        assertTrue(events.single().cleartext)
    }

    @Test
    fun theLogCarriesTheShapeOfTheCallAndNotItsContents() = runBlocking {
        val transport = FakeTransport(okResult)
        val events = mutableListOf<NetworkEvent>()
        val client = RemoteLlmClient(profile(), { "sk-ant-abcdef" }, transport, onEvent = { events += it })

        client.complete(
            LlmRequest(
                messages = listOf(LlmMessage(LlmMessage.Role.USER, "set player.gold to 999999")),
                systemPrompt = "savant edits game save files.",
            ),
        )

        val event = events.single()
        assertEquals("****cdef", event.keyMask)
        assertEquals("https://api.openai.com/v1/chat/completions", event.url)
        assertEquals(200, event.status)
        assertEquals(12L, event.latencyMillis)
        assertEquals(4, event.tokensIn)
        assertEquals(2, event.tokensOut)
        assertEquals(transport.calls.single().body.length, event.requestBytes)
        assertNull(event.error)

        // The prompt, the field path and the value are nowhere in the entry.
        val flat = event.toString()
        assertFalse(flat.contains("999999"))
        assertFalse(flat.contains("player.gold"))
        assertFalse(flat.contains("sk-ant-abcdef"))
    }

    @Test
    fun generationSettingsComeFromTheProfileNotTheCaller() = runBlocking {
        val transport = FakeTransport(okResult)
        val client = RemoteLlmClient(profile(), { "k" }, transport)

        client.complete(
            LlmRequest(
                messages = listOf(LlmMessage(LlmMessage.Role.USER, "hello")),
                temperature = 0.9f,
                maxOutputTokens = 4096,
            ),
        )

        val body = dev.savant.parsers.json.JsonPayload.read(transport.calls.single().body)
        assertEquals(0.1, body.double("temperature") ?: 0.0, 0.0001)
        assertEquals(256, body.int("max_tokens") ?: -1)
    }

    @Test
    fun aPingOnARefusedUrlNeverTouchesTheNetwork() = runBlocking {
        val transport = FakeTransport(okResult)
        val client = RemoteLlmClient(profile(baseUrl = "http://api.example.com/v1"), { "k" }, transport)

        val ping = client.ping()

        assertFalse(ping.ok)
        assertTrue(ping.detail.contains("not allowed"))
        assertTrue(transport.calls.isEmpty())
    }

    // ---- network policy ------------------------------------------------------------

    @Test
    fun keysAreReducedToFourCharacters() {
        assertEquals("****cdef", NetworkPolicy.redactKey("sk-ant-abcdef"))
        assertEquals("****", NetworkPolicy.redactKey("abc"))
        assertEquals("", NetworkPolicy.redactKey(null))
        assertEquals("", NetworkPolicy.redactKey("   "))
    }

    @Test
    fun aLoggedUrlLosesItsQueryString() {
        assertEquals("https://api.example.com/v1/models?…", NetworkPolicy.redactUrl("https://api.example.com/v1/models?key=secret"))
        assertEquals("https://api.example.com/v1/models", NetworkPolicy.redactUrl("https://api.example.com/v1/models"))
    }

    @Test
    fun localMeansLoopbackLinkLocalAndTheThreePrivateRanges() {
        assertTrue(NetworkPolicy.isLocal("http://localhost:11434/v1"))
        assertTrue(NetworkPolicy.isLocal("http://127.0.0.1:8080/v1"))
        assertTrue(NetworkPolicy.isLocal("http://10.0.0.7/v1"))
        assertTrue(NetworkPolicy.isLocal("http://192.168.1.20:11434/v1"))
        assertTrue(NetworkPolicy.isLocal("http://172.16.0.4/v1"))
        assertTrue(NetworkPolicy.isLocal("http://172.31.255.1/v1"))
        assertTrue(NetworkPolicy.isLocal("http://169.254.1.1/v1"))
        assertTrue(NetworkPolicy.isLocal("http://10.0.2.2:1234/v1"))

        assertFalse(NetworkPolicy.isLocal("https://api.openai.com/v1"))
        assertFalse(NetworkPolicy.isLocal("http://172.32.0.1/v1"))
        assertFalse(NetworkPolicy.isLocal("http://8.8.8.8/v1"))
        assertFalse(NetworkPolicy.isLocal("http://93.184.216.34/v1"))
    }

    @Test
    fun httpsIsSecureLocalHttpIsAllowedWithAWarningAndRemoteHttpIsRefused() {
        assertTrue(NetworkPolicy.assess("https://api.openai.com/v1") is NetworkPolicy.Verdict.Secure)

        val local = NetworkPolicy.assess("http://192.168.1.20:11434/v1")
        assertTrue(local is NetworkPolicy.Verdict.LocalCleartext)
        assertTrue(local.allowed)
        assertTrue((local as NetworkPolicy.Verdict.LocalCleartext).warning.contains("cleartext"))

        val remote = NetworkPolicy.assess("http://api.openai.com/v1")
        assertTrue(remote is NetworkPolicy.Verdict.Refused)
        assertFalse(remote.allowed)
        assertTrue((remote as NetworkPolicy.Verdict.Refused).reason.contains("https"))

        val nonsense = NetworkPolicy.assess("ftp://example.com/v1")
        assertTrue(nonsense is NetworkPolicy.Verdict.Refused)
    }

    // ---- profiles ------------------------------------------------------------------

    @Test
    fun profilesRoundTripThroughThePreferencesDocument() {
        val set = ProfileSet(
            profiles = listOf(
                ProviderProfile(
                    id = "a",
                    name = "work",
                    provider = ProviderKind.CLAUDE,
                    baseUrl = "",
                    model = "a-model",
                    temperature = 0.4f,
                    maxOutputTokens = 2048,
                    systemPromptOverride = "be terse",
                ),
                ProviderProfile(
                    id = "b",
                    name = "local box",
                    provider = ProviderKind.CUSTOM,
                    baseUrl = "http://192.168.1.20:11434/v1",
                    model = "qwen",
                ),
            ),
            activeId = "b",
        )

        val back = ProfileCodec.decode(ProfileCodec.encode(set))

        assertEquals(set.profiles, back.profiles)
        assertEquals("b", back.activeId)
        assertEquals("local box", back.active?.name)
        // The key is not part of a profile, so it cannot leak into the document.
        assertFalse(ProfileCodec.encode(set).contains("key"))
    }

    @Test
    fun anActiveIdThatPointsAtNothingIsDropped() {
        val encoded = """{"activeId":"gone","profiles":[{"id":"a","name":"a","provider":"openai","baseUrl":"","model":"m"}]}"""
        val back = ProfileCodec.decode(encoded)

        assertEquals(1, back.profiles.size)
        assertNull(back.activeId)
        assertFalse(back.isRemote)
    }

    @Test
    fun aCorruptOrMissingDocumentDegradesToOnDevice() {
        assertEquals(ProfileSet(), ProfileCodec.decode(null))
        assertEquals(ProfileSet(), ProfileCodec.decode(""))
        assertEquals(ProfileSet(), ProfileCodec.decode("{not json"))
        assertFalse(ProfileCodec.decode("{not json").isRemote)
    }

    @Test
    fun deletingTheActiveProfileFallsBackToOnDevice() {
        val draft = ProfileSet.draft(ProviderKind.OPENAI, id = "a")
        val set = ProfileSet().withProfile(draft).withActive("a")
        assertTrue(set.isRemote)

        val after = set.withoutProfile("a")
        assertNull(after.activeId)
        assertFalse(after.isRemote)
        assertTrue(after.profiles.isEmpty())
    }

    @Test
    fun aProfileSaysWhatIsMissingBeforeItIsUsed() {
        val draft = ProfileSet.draft(ProviderKind.GEMINI, id = "g")
        assertEquals("no model name", draft.missingKey(hasKey = true))
        assertEquals("no api key stored", draft.copy(model = "a-model").missingKey(hasKey = false))
        assertNull(draft.copy(model = "a-model").missingKey(hasKey = true))
    }

    @Test
    fun aDraftStartsOnTheProvidersOwnEndpointWithNoKeyAndNoModel() {
        val draft = ProfileSet.draft(ProviderKind.CLAUDE, id = "c1")

        assertEquals(ProviderKind.CLAUDE, draft.provider)
        assertEquals("https://api.anthropic.com/v1", draft.baseUrl)
        assertEquals("", draft.model)
        assertEquals(0.2f, draft.temperature, 0.0001f)
        assertEquals(1024, draft.maxOutputTokens)
        assertFalse(draft.active)
        assertTrue(ProfileSet.Companion.MODEL_HINT.containsKey(ProviderKind.CLAUDE))
    }
}
