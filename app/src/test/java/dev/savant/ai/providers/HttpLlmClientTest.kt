package dev.savant.ai.providers

import dev.savant.ai.LlmEvent
import dev.savant.ai.LlmMessage
import dev.savant.ai.LlmRequest
import dev.savant.ai.NetworkLog
import dev.savant.ai.PayloadNote
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderProfile
import dev.savant.ai.ToolCatalog
import dev.savant.ai.net.TransportException
import dev.savant.parsers.json.JsonPayload
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client between a profile and a dialect.
 *
 * It owns three things the rest of the app relies on: the key never going somewhere it should not,
 * a provider's own error text reaching the user unedited, and the request log telling the truth
 * about what was sent.
 */
class HttpLlmClientTest {

    private val transport = TestTransport()
    private val log = NetworkLog(capacity = 10, enabled = { true })

    private fun profile(
        kind: ProviderKind = ProviderKind.OPENAI,
        baseUrl: String = "",
        model: String = "gpt-4o-mini",
        streaming: Boolean = true,
    ) = ProviderProfile(
        id = "p1",
        name = "test",
        provider = kind,
        baseUrl = baseUrl,
        model = model,
        streaming = streaming,
    )

    private fun client(
        profile: ProviderProfile = profile(),
        key: String? = "sk-test-abcd1234",
        log: NetworkLog = this.log,
    ) = HttpLlmClient(profile, transport, apiKey = { key }, log = log)

    private fun request(note: PayloadNote = PayloadNote()) = LlmRequest(
        messages = listOf(LlmMessage(LlmMessage.Role.USER, "max the gold")),
        tools = ToolCatalog.ALL,
        systemPrompt = "you are savant",
        payloadNote = note,
    )

    // ---- the request ---------------------------------------------------------------

    @Test
    fun aRequestGoesToTheDialectEndpointWithTheKeyInItsHeader() {
        transport.willAnswer(body = """{"choices":[{"message":{"content":"player.gold is 5000"},"finish_reason":"stop"}]}""")
        val response = runBlocking { client().complete(request()) }
        assertEquals("player.gold is 5000", response.text)
        assertEquals("https://api.openai.com/v1/chat/completions", transport.last.url)
        assertEquals("Bearer sk-test-abcd1234", transport.last.headers["Authorization"])
        assertFalse(transport.last.url.contains("sk-test"))
    }

    @Test
    fun aHostedProviderWithoutAKeyNeverSendsAnything() {
        val thrown = runCatching { runBlocking { client(key = null).complete(request()) } }.exceptionOrNull()
        assertTrue(thrown is TransportException)
        assertTrue(thrown!!.message!!.contains("no api key for openai"))
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun aSelfHostedEndpointNeedsNoKey() {
        transport.willAnswer(body = """{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}]}""")
        val selfHosted = profile(kind = ProviderKind.CUSTOM, baseUrl = "http://192.168.1.9:11434/v1")
        val response = runBlocking { client(selfHosted, key = null).complete(request()) }
        assertEquals("hi", response.text)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun aProviderErrorBecomesTheSentenceTheUserReads() {
        transport.willAnswer(status = 429, body = """{"error":{"message":"rate limit reached for gpt-4o-mini"}}""")
        val thrown = runCatching { runBlocking { client().complete(request()) } }.exceptionOrNull()
        assertTrue(thrown is TransportException)
        val failure = thrown as TransportException
        assertEquals(429, failure.status)
        assertTrue(failure.retryable)
        assertTrue(failure.message!!.contains("rate limited"))
        assertTrue(failure.message!!.contains("rate limit reached for gpt-4o-mini"))
    }

    // ---- privacy -------------------------------------------------------------------

    @Test
    fun theRequestLogRecordsWhatLeftTheDevice() {
        transport.willAnswer(
            body = """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":900,"completion_tokens":4}}""",
        )
        runBlocking {
            client().complete(
                request(PayloadNote(fields = 42, values = false, withheldReason = "send field values is off in settings")),
            )
        }
        val record = log.latest!!
        assertEquals("openai", record.provider)
        assertEquals("api.openai.com/v1/chat/completions", record.endpoint)
        assertEquals(200, record.status)
        assertTrue(record.bytesSent > 0)
        assertEquals(42, record.fieldsSent)
        assertFalse(record.valuesSent)
        assertEquals("****1234", record.keyMask)
        assertEquals(900, record.inputTokens)
        assertEquals(4, record.outputTokens)
        assertTrue(record.summary.contains("values withheld"))
        // The key itself is nowhere in the record.
        assertFalse(record.toString().contains("sk-test"))
    }

    @Test
    fun aFailureIsLoggedWithARedactedReason() {
        transport.willFail("connection refused to https://api.openai.com", retryable = true)
        runCatching { runBlocking { client().complete(request()) } }
        val record = log.latest!!
        assertEquals(null, record.status)
        assertTrue(record.detail!!.contains("connection refused"))
        assertFalse(record.ok)
        assertTrue(record.summary.startsWith("failed"))
    }

    @Test
    fun nothingIsLoggedWhenTheSwitchIsOff() {
        val quiet = NetworkLog(enabled = { false })
        transport.willAnswer(body = """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
        runBlocking { client(log = quiet).complete(request()) }
        assertEquals(0, quiet.records.value.size)
        assertEquals(1, transport.sent.size)
    }

    // ---- streaming -----------------------------------------------------------------

    @Test
    fun aStreamedAnswerEmitsDeltasThenTheFinishedResponse() {
        transport.willStream(
            """{"choices":[{"delta":{"content":"player."}}]}""",
            """{"choices":[{"delta":{"content":"gold is 5000"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "[DONE]",
        )
        val events = runBlocking { client().events(request()).toList() }
        val deltas = events.filterIsInstance<LlmEvent.Delta>().map { it.chunk.textDelta }
        assertEquals(listOf("player.", "gold is 5000"), deltas)
        val completed = events.filterIsInstance<LlmEvent.Completed>().single().response
        assertEquals("player.gold is 5000", completed.text)
        assertEquals("text/event-stream", transport.last.accept)
    }

    @Test
    fun aProfileWithoutStreamingStillAnswersTheSameWay() {
        transport.willAnswer(body = """{"choices":[{"message":{"content":"player.gold is 5000"},"finish_reason":"stop"}]}""")
        val events = runBlocking { client(profile(streaming = false)).events(request()).toList() }
        assertEquals(1, events.filterIsInstance<LlmEvent.Delta>().size)
        assertEquals("player.gold is 5000", events.filterIsInstance<LlmEvent.Completed>().single().response.text)
    }

    @Test
    fun aStreamThatFailsIsLoggedAndReported() {
        transport.willFail("the stream stopped", retryable = true)
        val thrown = runCatching { runBlocking { client().events(request()).toList() } }.exceptionOrNull()
        assertTrue(thrown is TransportException)
        assertNotNull(log.latest)
    }

    // ---- ping ----------------------------------------------------------------------

    @Test
    fun pingSendsATinyRequestWithNoFileDataInIt() {
        transport.willAnswer(body = """{"model":"gpt-4o-mini","choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
        val ping = runBlocking { client().ping() }
        assertTrue(ping.ok)
        assertEquals("ok", ping.detail)
        assertEquals("api.openai.com/v1/chat/completions", ping.endpoint)
        assertTrue(ping.latencyMillis >= 0)
        val body = transport.last.body!!
        assertTrue(body.contains("connectivity check"))
        assertFalse(body.contains("editable values"))
        assertFalse(body.contains("player"))
        assertTrue(JsonPayload.read(body).long("max_tokens")!! <= 32)
    }

    @Test
    fun pingReportsARejectionWithoutEchoingTheKey() {
        transport.willAnswer(status = 401, body = """{"error":{"message":"Incorrect API key provided: sk-test-abcd1234"}}""")
        val ping = runBlocking { client().ping() }
        assertFalse(ping.ok)
        assertTrue(ping.detail.contains("the key was rejected"))
        assertFalse(ping.detail.contains("sk-test-abcd1234"))
        assertTrue(ping.detail.contains("****"))
    }
}
