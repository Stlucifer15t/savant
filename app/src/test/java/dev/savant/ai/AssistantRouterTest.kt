package dev.savant.ai

import dev.savant.ai.providers.TestTransport
import dev.savant.data.settings.SavantSettings
import dev.savant.domain.model.ImportedFile
import dev.savant.domain.parser.ExportVerifier
import dev.savant.domain.parser.ParserRegistry
import dev.savant.domain.session.EditorSession
import dev.savant.domain.session.SaveEditor
import dev.savant.parsers.json.JsonSaveParser
import dev.savant.parsers.xml.XmlSaveParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What answers the chat.
 *
 * The decision table is the feature: no profile means the on-device parser, a profile without a key
 * says so instead of sending a request that would 401, and an activated profile routes through the
 * same [ToolExecutor] the local parser uses.
 */
class AssistantRouterTest {

    private val registry = ParserRegistry(listOf(JsonSaveParser(), XmlSaveParser()))
    private val editor = SaveEditor(registry, ExportVerifier(registry))
    private val executor = ToolExecutor(editor)
    private val transport = TestTransport()
    private val log = NetworkLog(enabled = { true })

    private val json = """{"player": {"gold": 5000, "strength": 12}}"""

    private fun session(): EditorSession {
        val bytes = json.toByteArray()
        val file = ImportedFile("content://test/save.json", "save.json", bytes.size.toLong(), null, 0L, ImportedFile.hexPreview(bytes))
        return editor.open(file, bytes).session!!
    }

    private fun profile(
        id: String = "p1",
        active: Boolean = true,
        model: String = "gpt-4o-mini",
        baseUrl: String = "",
    ) = ProviderProfile(
        id = id,
        name = "work",
        provider = ProviderKind.OPENAI,
        baseUrl = baseUrl,
        model = model,
        active = active,
    )

    private fun router(
        profiles: List<ProviderProfile> = listOf(profile()),
        keys: Map<String, String> = mapOf("p1" to "sk-test-abcd1234"),
        settings: SavantSettings = SavantSettings(),
    ) = AssistantRouter(
        executor = executor,
        transport = transport,
        profiles = MutableStateFlow(profiles),
        keyOf = { profile -> keys[profile.id] },
        settings = { settings },
        log = log,
    )

    private fun ask(router: AssistantRouter, text: String, session: EditorSession? = session()): AssistantReply =
        runBlocking { router.respond(AssistantInput(text, session)) }

    // ---- no provider ---------------------------------------------------------------

    @Test
    fun withNothingActivatedTheOnDeviceParserAnswersAndNothingIsSent() {
        val engine = router(profiles = listOf(profile(active = false)))
        assertEquals("on device", engine.label)
        assertFalse(engine.isRemote)
        val reply = ask(engine, "max the gold")
        assertTrue(reply is AssistantReply.Proposal)
        assertEquals(1, (reply as AssistantReply.Proposal).edits.size)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun theLabelFollowsTheActiveProfile() {
        assertEquals("openai · gpt-4o-mini", router().label)
        assertTrue(router().isRemote)
        assertEquals("on device", router(profiles = emptyList()).label)
    }

    // ---- a profile that cannot work -------------------------------------------------

    @Test
    fun anActiveProfileWithoutAKeyAsksForOneInsteadOfSending() {
        val engine = router(keys = emptyMap())
        val reply = ask(engine, "max the gold")
        assertTrue(reply is AssistantReply.Failure)
        val lines = (reply as AssistantReply.Failure).lines
        assertTrue(lines.any { it.contains("no api key stored for work") })
        assertTrue(lines.any { it.contains("settings → ai provider") })
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun anIncompleteProfileNamesTheMissingField() {
        val engine = router(profiles = listOf(profile(model = "")))
        val lines = (ask(engine, "max the gold") as AssistantReply.Failure).lines
        assertTrue(lines.any { it.contains("incomplete: no model name") })
        assertTrue(transport.sent.isEmpty())
    }

    // ---- an activated provider -------------------------------------------------------

    @Test
    fun anActivatedProfileReachesTheProviderAndItsEditsComeBackAsAProposal() {
        transport
            .willAnswer(
                body = """
                {"choices":[{"message":{"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"set_field","arguments":"{\"path\":\"player.gold\",\"value\":\"999\"}"}}
                ]},"finish_reason":"tool_calls"}]}
                """.trimIndent(),
            )
            .willAnswer(body = """{"choices":[{"message":{"content":"proposed 999 gold"},"finish_reason":"stop"}]}""")

        val reply = ask(router(), "set gold to 999")
        assertTrue(reply is AssistantReply.Proposal)
        val proposal = reply as AssistantReply.Proposal
        assertEquals("player.gold", proposal.edits.first().path)
        assertEquals(2, transport.sent.size)
        assertEquals("https://api.openai.com/v1/chat/completions", transport.sent.first().url)
        assertTrue(proposal.lines.any { it.contains("proposed 999 gold") })
    }

    @Test
    fun aCustomBaseUrlIsUsedAsGiven() {
        transport.willAnswer(body = """{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}]}""")
        val engine = router(
            profiles = listOf(profile(baseUrl = "http://192.168.1.9:11434/v1")),
            keys = emptyMap(),
        )
        // A custom endpoint is allowed to have no key; this one is openai-shaped but self hosted.
        assertFalse(engine.isRemote.not())
        ask(engine, "hi")
        assertEquals("http://192.168.1.9:11434/v1/chat/completions", transport.sent.first().url)
    }

    @Test
    fun switchingBackToTheLocalParserStopsTalkingToTheNetwork() {
        val profilesFlow = MutableStateFlow(listOf(profile()))
        val engine = AssistantRouter(
            executor = executor,
            transport = transport,
            profiles = profilesFlow,
            keyOf = { "sk-test-abcd1234" },
            settings = { SavantSettings() },
            log = log,
        )
        transport.willAnswer(body = """{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}]}""")
        ask(engine, "hello")
        assertEquals(1, transport.sent.size)

        profilesFlow.value = listOf(profile(active = false))
        assertEquals("on device", engine.label)
        ask(engine, "list fields")
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun thePrivacySwitchTravelsToTheEngine() {
        transport.willAnswer(body = """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
        ask(router(settings = SavantSettings(sendFieldValues = false)), "what is gold")
        val body = transport.sent.first().body!!
        assertTrue(body.contains("player.gold: i32"))
        // 5000 is the value; it must not be in the payload, and the note must say so.
        assertFalse(body.contains("5000"))
        assertEquals(false, log.latest!!.valuesSent)
    }
}
