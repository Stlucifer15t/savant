package dev.savant.ai.providers

import dev.savant.ai.AssistantInput
import dev.savant.ai.AssistantReply
import dev.savant.ai.LlmChunk
import dev.savant.ai.LlmClient
import dev.savant.ai.LlmEvent
import dev.savant.ai.LlmMessage
import dev.savant.ai.LlmRequest
import dev.savant.ai.LlmResponse
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderPing
import dev.savant.ai.ProviderProfile
import dev.savant.ai.StopReason
import dev.savant.ai.ToolCall
import dev.savant.ai.ToolExecutor
import dev.savant.ai.net.TransportException
import dev.savant.codecs.GzipCodec
import dev.savant.data.settings.SavantSettings
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.ImportedFile
import dev.savant.domain.parser.ExportVerifier
import dev.savant.domain.parser.ParserRegistry
import dev.savant.domain.session.EditorSession
import dev.savant.domain.session.SaveEditor
import dev.savant.parsers.json.JsonSaveParser
import dev.savant.parsers.xml.XmlSaveParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider engine: a model on one side, eight tools on the other.
 *
 * The tests that matter are the ones about the boundary — a model proposes and the user confirms,
 * a model never writes, an answer that cannot be produced says why, and nothing in the file leaves
 * the device unless the user allowed it.
 */
class ProviderAssistantTest {

    private val registry = ParserRegistry(listOf(JsonSaveParser(), XmlSaveParser()))
    private val editor = SaveEditor(registry, ExportVerifier(registry))
    private val executor = ToolExecutor(editor)

    private val profile = ProviderProfile(
        id = "p1",
        name = "test provider",
        provider = ProviderKind.OPENAI,
        baseUrl = "https://example.invalid/v1",
        model = "test-model",
    )

    private val json = """{"player": {"name": "Aric", "gold": 5000, "strength": 12}}"""

    private fun session(sizeBytes: Long = json.length.toLong()): EditorSession {
        val bytes = json.toByteArray()
        val file = ImportedFile("content://test/save.json", "save.json", sizeBytes, null, 0L, ImportedFile.hexPreview(bytes))
        return editor.open(file, bytes).session!!
    }

    /** Answers from a script, and remembers every request it was given. */
    private class ScriptedClient(private val answers: List<Any>) : LlmClient {
        override val providerId = "test"
        override val label = "test provider"

        val requests = mutableListOf<LlmRequest>()

        override suspend fun complete(request: LlmRequest): LlmResponse = next(request)

        override fun events(request: LlmRequest): Flow<LlmEvent> = flow {
            val response = next(request)
            response.text?.takeIf { it.isNotEmpty() }?.let { text ->
                // Streamed in two pieces, the way a real provider would.
                val half = text.length / 2
                emit(LlmEvent.Delta(LlmChunk(text.substring(0, half))))
                emit(LlmEvent.Delta(LlmChunk(text.substring(half), done = true)))
            }
            emit(LlmEvent.Completed(response))
        }

        override suspend fun ping(): ProviderPing = ProviderPing(true, "ok", 1)

        private fun next(request: LlmRequest): LlmResponse {
            requests += request
            return when (val answer = answers.getOrElse(requests.size - 1) { error("no scripted answer left") }) {
                is LlmResponse -> answer
                is TransportException -> throw answer
                else -> error("unscripted answer")
            }
        }
    }

    private fun assistant(
        client: LlmClient,
        sendFieldValues: Boolean = true,
        maxRounds: Int = 6,
        settings: () -> SavantSettings = { SavantSettings(sendFieldValues = sendFieldValues) },
    ) = ProviderAssistant(client, executor, profile, settings = settings, maxRounds = maxRounds)

    private fun reply(
        client: LlmClient,
        text: String = "max the gold",
        session: EditorSession? = session(),
        onPartial: (String) -> Unit = {},
        assistant: ProviderAssistant = assistant(client),
    ): AssistantReply = runBlocking {
        assistant.respond(AssistantInput(text, session, onPartial = onPartial))
    }

    private fun text(lines: List<String>) = LlmResponse(text = lines.joinToString("\n"))

    private fun toolCall(name: String, vararg args: Pair<String, String>) = LlmResponse(
        text = null,
        toolCalls = listOf(ToolCall.of(name, *args)),
        stopReason = StopReason.TOOL_USE,
    )

    // ---- the boundary --------------------------------------------------------------

    @Test
    fun aToolCallBecomesAProposalAndTheFileIsUntouched() {
        val client = ScriptedClient(
            listOf(
                toolCall("set_field", "path" to "player.gold", "value" to "999999"),
                text(listOf("proposed 999999 for player.gold")),
            ),
        )
        val session = session()
        val result = reply(client, session = session)

        assertTrue(result is AssistantReply.Proposal)
        val proposal = result as AssistantReply.Proposal
        assertEquals(1, proposal.edits.size)
        assertEquals("player.gold", proposal.edits.first().path)
        assertEquals(999999L, (proposal.edits.first().newValue as FieldValue.Int64).value)
        assertEquals("the user sees a diff", 5000L, (session.tree.node("player.gold")!!.value as FieldValue.Int64).value)
        assertTrue(proposal.lines.any { it.contains("proposed 999999") })
    }

    @Test
    fun theToolResultGoesBackToTheModel() {
        val client = ScriptedClient(
            listOf(
                toolCall("set_field", "path" to "player.gold", "value" to "999999"),
                text(listOf("done")),
            ),
        )
        reply(client)
        val second = client.requests[1].messages
        val tool = second.last()
        assertEquals(LlmMessage.Role.TOOL, tool.role)
        assertEquals("set_field", tool.toolName)
        assertTrue(tool.text.contains("player.gold: 5000 -> 999999"))
        // The assistant turn is replayed with the call it made, or the provider rejects the turn.
        assertTrue(second.any { it.role == LlmMessage.Role.ASSISTANT && it.toolCalls.isNotEmpty() })
    }

    @Test
    fun aPlainAnswerIsJustText() {
        val client = ScriptedClient(listOf(text(listOf("player.gold is 5000"))))
        val result = reply(client, text = "what is gold")
        assertTrue(result is AssistantReply.Text)
        assertTrue((result as AssistantReply.Text).lines.any { it.contains("player.gold is 5000") })
    }

    @Test
    fun everyToolIsOfferedToTheModel() {
        val client = ScriptedClient(listOf(text(listOf("hello"))))
        reply(client)
        val names = client.requests.first().tools.map { it.name }
        assertEquals(8, names.size)
        assertTrue(names.containsAll(listOf("set_field", "set_many", "undo", "export", "search_fields")))
    }

    // ---- privacy -------------------------------------------------------------------

    @Test
    fun fieldValuesStayBehindWhenTheUserTurnsThemOff() {
        // Two answers: the second call below is a fresh turn, and a fresh turn is a fresh request.
        val client = ScriptedClient(listOf(text(listOf("ok")), text(listOf("ok"))))
        reply(client, assistant = assistant(client, sendFieldValues = false))
        val system = client.requests.first().systemPrompt!!
        assertTrue(system.contains("player.gold: i32"))
        assertFalse(system.contains("5000"))
        assertEquals(false, client.requests.first().payloadNote.values)
        assertEquals(3, client.requests.first().payloadNote.fields)

        val lines = (reply(client, assistant = assistant(client, sendFieldValues = false)) as AssistantReply.Text).lines
        assertTrue(lines.any { it.contains("sent field names and types only") })
    }

    @Test
    fun aBigFileIsTrimmedEvenWhenValuesAreAllowed() {
        val client = ScriptedClient(listOf(text(listOf("ok"))))
        reply(client, session = session(sizeBytes = 400 * 1024), assistant = assistant(client, sendFieldValues = true))
        val request = client.requests.first()
        assertFalse(request.payloadNote.values)
        assertTrue(request.payloadNote.withheld)
        assertFalse(request.systemPrompt!!.contains("5000"))
        assertTrue(request.payloadNote.withheldReason!!.contains("400 kb"))
    }

    @Test
    fun theProviderIsNamedOnceNotEveryTurn() {
        val client = ScriptedClient(listOf(text(listOf("one")), text(listOf("two"))))
        val engine = assistant(client)
        val first = reply(client, assistant = engine) as AssistantReply.Text
        val second = reply(client, assistant = engine) as AssistantReply.Text
        assertTrue(first.lines.any { it.contains("answering with") })
        assertFalse(second.lines.any { it.contains("answering with") })
    }

    // ---- failure paths -------------------------------------------------------------

    @Test
    fun noFileOpenIsAFailureNotARequest() {
        val client = ScriptedClient(listOf(text(listOf("unused"))))
        val result = reply(client, session = null)
        assertTrue(result is AssistantReply.Failure)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun aReadOnlyFileBlocksTheAssistantBeforeItAsks() {
        // A gzip stream nobody declared a codec for opens readable but not writable.
        val gzipped = GzipCodec.encode(json.toByteArray())
        val file = ImportedFile(
            "content://test/save.json.gz",
            "save.json.gz",
            gzipped.size.toLong(),
            null,
            0L,
            ImportedFile.hexPreview(gzipped),
        )
        val readOnly = editor.open(file, gzipped).session!!
        assertTrue(readOnly.readOnly)

        val client = ScriptedClient(listOf(text(listOf("unused"))))
        val result = reply(client, session = readOnly)
        assertTrue(result is AssistantReply.Failure)
        assertTrue((result as AssistantReply.Failure).lines.any { it.contains("gzip") })
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun aTransportFailureIsASentenceWithAHint() {
        val client = ScriptedClient(listOf(TransportException("connection refused", retryable = true)))
        val result = reply(client)
        assertTrue(result is AssistantReply.Failure)
        val lines = (result as AssistantReply.Failure).lines
        assertTrue(lines.any { it.contains("connection refused") })
        assertTrue(lines.any { it.contains("transient") })
    }

    @Test
    fun proposalsSurviveAFailureInALaterRound() {
        val client = ScriptedClient(
            listOf(
                toolCall("set_field", "path" to "player.gold", "value" to "777"),
                TransportException("stream closed", retryable = true),
            ),
        )
        val result = reply(client)
        assertTrue(result is AssistantReply.Proposal)
        val proposal = result as AssistantReply.Proposal
        assertEquals(1, proposal.edits.size)
        assertTrue(proposal.lines.any { it.contains("proposed change") && it.contains("below") })
        assertTrue(proposal.lines.any { it.contains("stream closed") })
    }

    @Test
    fun aRefusalIsReportedRatherThanTurnedIntoAnEmptyAnswer() {
        val client = ScriptedClient(listOf(LlmResponse(text = "", toolCalls = emptyList(), stopReason = StopReason.REFUSAL)))
        val lines = (reply(client) as AssistantReply.Text).lines
        assertTrue(lines.any { it.contains("declined") })
    }

    @Test
    fun aLengthStopIsExplained() {
        val client = ScriptedClient(listOf(LlmResponse(text = "the gold is", toolCalls = emptyList(), stopReason = StopReason.LENGTH)))
        val lines = (reply(client) as AssistantReply.Text).lines
        assertTrue(lines.any { it.contains("output token limit") })
    }

    @Test
    fun aRunawayLoopIsStopped() {
        val loop = toolCall("list_fields")
        val client = ScriptedClient(List(12) { loop })
        val result = reply(client, assistant = assistant(client, maxRounds = 3))
        assertTrue(result is AssistantReply.Proposal)
        assertTrue((result as AssistantReply.Proposal).lines.any { it.contains("stopped after 3 tool rounds") })
        assertEquals(4, client.requests.size)
    }

    @Test
    fun anUnknownToolIsToldToTheModelInsteadOfCrashing() {
        val client = ScriptedClient(
            listOf(
                toolCall("delete_everything"),
                text(listOf("i cannot do that")),
            ),
        )
        val result = reply(client)
        assertTrue(result is AssistantReply.Text)
        val toolResult = client.requests[1].messages.last()
        assertTrue(toolResult.text.contains("unknown tool"))
    }

    // ---- streaming -----------------------------------------------------------------

    @Test
    fun partialTextIsReportedAsItArrives() {
        val client = ScriptedClient(listOf(text(listOf("player.gold is 5000"))))
        val seen = mutableListOf<String>()
        reply(client, text = "what is gold", onPartial = { seen += it })
        assertEquals(2, seen.size)
        assertTrue(seen.last().endsWith("player.gold is 5000"))
        assertTrue(seen.first().length < seen.last().length)
    }

    @Test
    fun theSystemPromptCarriesTheRulesAndTheListing() {
        val client = ScriptedClient(listOf(text(listOf("ok"))))
        reply(client)
        val system = client.requests.first().systemPrompt!!
        assertTrue(system.contains("you cannot write a file"))
        assertTrue(system.contains("json save"))
        assertTrue(system.contains("player.name: str"))
    }
}
