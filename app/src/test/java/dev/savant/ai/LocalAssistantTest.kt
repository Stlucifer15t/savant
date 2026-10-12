package dev.savant.ai

import dev.savant.domain.model.EditSource
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.ImportedFile
import dev.savant.domain.parser.ExportVerifier
import dev.savant.domain.parser.ParserRegistry
import dev.savant.domain.session.EditorSession
import dev.savant.domain.session.SaveEditor
import dev.savant.parsers.json.JsonSaveParser
import dev.savant.parsers.xml.XmlSaveParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device assistant: natural language in, proposed edits out.
 * Every value change comes back as a proposal — the diff preview is the only gate.
 */
class LocalAssistantTest {

    private val registry = ParserRegistry(listOf(JsonSaveParser(), XmlSaveParser()))
    private val editor = SaveEditor(registry, ExportVerifier(registry))
    private val assistant = LocalAssistant(ToolExecutor(editor))

    private val json = """{
      "player": {"name": "Aric", "gold": 5000, "maxHp": 40, "strength": 12, "level": 3},
      "inventory": [{"count": 2}, {"count": 1}],
      "flags": {"tutorialDone": false, "quest_started": true}
    }"""

    private fun session(): EditorSession {
        val bytes = json.toByteArray()
        val file = ImportedFile("content://test/save.json", "save.json", bytes.size.toLong(), null, 0L, ImportedFile.hexPreview(bytes))
        return editor.open(file, bytes).session!!
    }

    private fun reply(text: String, session: EditorSession? = session()): AssistantReply =
        runBlocking { assistant.respond(AssistantInput(text, session)) }

    // ---- commands that change values --------------------------------------------

    @Test
    fun setCommandProposesOneEdit() {
        val result = reply("set gold to 999999")
        assertTrue(result is AssistantReply.Proposal)
        val proposal = result as AssistantReply.Proposal
        assertEquals(1, proposal.edits.size)
        val edit = proposal.edits.first()
        assertEquals("player.gold", edit.path)
        assertEquals(999999L, (edit.newValue as FieldValue.Int64).value)
        assertEquals(5000L, (edit.oldValue as FieldValue.Int64).value)
        assertEquals(EditSource.AI_TOOL, edit.source)
    }

    @Test
    fun setCommandAcceptsOtherPhrasings() {
        listOf(
            "set gold = 12",
            "change gold to 12",
            "gold = 12",
            "make gold 12",
        ).forEach { command ->
            val result = reply(command)
            assertTrue("$command should propose an edit, got $result", result is AssistantReply.Proposal)
            assertEquals(command, 12L, ((result as AssistantReply.Proposal).edits.first().newValue as FieldValue.Int64).value)
        }
    }

    @Test
    fun maxAllStatsProposesAnEditPerStat() {
        val result = reply("max all stats")
        assertTrue(result is AssistantReply.Proposal)
        val edits = (result as AssistantReply.Proposal).edits
        val paths = edits.map { it.path }.toSet()
        assertTrue(paths.toString(), paths.contains("player.maxHp"))
        assertTrue(paths.toString(), paths.contains("player.strength"))
        assertTrue(paths.contains("player.level"))
        assertFalse("names are not stats", paths.contains("player.name"))
        assertTrue(edits.all { (it.newValue as FieldValue.Int64).value == 2147483647L })
    }

    @Test
    fun arithmeticCommandsUseTheCurrentValue() {
        val add = reply("add 10 to strength") as AssistantReply.Proposal
        assertEquals(1, add.edits.size)
        assertEquals(22L, (add.edits.first().newValue as FieldValue.Int64).value)

        val lower = reply("decrease gold by 500") as AssistantReply.Proposal
        assertEquals(4500L, (lower.edits.first().newValue as FieldValue.Int64).value)

        val doubled = reply("double level") as AssistantReply.Proposal
        assertEquals(6L, (doubled.edits.first().newValue as FieldValue.Int64).value)
    }

    @Test
    fun booleanCommandsWork() {
        val unlock = reply("unlock tutorialDone") as AssistantReply.Proposal
        assertEquals(FieldValue.Bool(true), unlock.edits.first().newValue)
        val lock = reply("set quest_started to false") as AssistantReply.Proposal
        assertEquals(FieldValue.Bool(false), lock.edits.first().newValue)
    }

    @Test
    fun ambiguousTargetsAskInsteadOfGuessing() {
        val result = reply("set count to 9")
        assertTrue(result is AssistantReply.Proposal || result is AssistantReply.Text)
        val lines = result.lines.joinToString(" ")
        assertTrue(lines, lines.contains("count"))
    }

    @Test
    fun unknownTargetsSaySo() {
        val result = reply("set dragons to 5")
        assertTrue(result is AssistantReply.Failure)
        assertTrue(result.lines.first().contains("no editable field"))
    }

    @Test
    fun invalidValuesAreRejectedWithAReason() {
        val result = reply("set maxHp to lots")
        assertTrue(result is AssistantReply.Failure)
        assertTrue(result.lines.joinToString(" ").contains("number"))
    }

    // ---- read only commands ------------------------------------------------------

    @Test
    fun searchReportsMatches() {
        val result = reply("search hp")
        assertTrue(result is AssistantReply.Text)
        assertTrue(result.lines.joinToString("\n").contains("player.maxHp"))
    }

    @Test
    fun listFieldsSummarisesCategories() {
        val result = reply("list fields")
        val text = result.lines.joinToString("\n")
        assertTrue(text, text.contains("editable values"))
        assertTrue(text.contains("currency"))
    }

    @Test
    fun whatIsExplainsAField() {
        val result = reply("what is gold")
        val text = result.lines.joinToString("\n")
        assertTrue(text, text.contains("player.gold"))
        assertTrue(text.contains("i32"))
        assertTrue(text.contains("currency"))
    }

    @Test
    fun explainIsMarkedAsAGuess() {
        val result = reply("explain strength")
        assertTrue(result.lines.joinToString("\n").contains("guess:"))
    }

    // ---- session level commands --------------------------------------------------

    @Test
    fun undoProposesTheReverseEdits() {
        val session = session()
        val proposal = reply("set gold to 1", session) as AssistantReply.Proposal
        val applied = editor.apply(session, proposal.edits, note = "command")
        val result = reply("undo", applied.session)
        assertTrue(result is AssistantReply.Proposal)
        val edits = (result as AssistantReply.Proposal).edits
        assertEquals(1, edits.size)
        assertEquals(5000L, (edits.first().newValue as FieldValue.Int64).value)
        assertEquals(EditSource.UNDO, edits.first().source)
    }

    @Test
    fun exportAsksForARebuild() {
        val result = reply("export")
        assertTrue(result is AssistantReply.Proposal)
        assertTrue((result as AssistantReply.Proposal).requestExport)
    }

    @Test
    fun withoutAFileItSaysSo() {
        val result = reply("set gold to 5", null)
        assertTrue(result is AssistantReply.Failure)
        assertTrue(result.lines.first().contains("no save file"))
    }

    @Test
    fun gibberishFallsBackToHelpAndSuggestions() {
        val result = reply("bananas")
        assertTrue(result is AssistantReply.Text)
        val text = result.lines.joinToString("\n")
        assertTrue(text, text.contains("not a command"))
        assertTrue(text.contains("set gold to 999999"))
    }

    @Test
    fun nearMissesSuggestTheClosestField() {
        val result = reply("golds")
        val text = result.lines.joinToString("\n")
        assertTrue(text, text.contains("player.gold"))
    }

    // ---- tool layer --------------------------------------------------------------

    @Test
    fun toolCatalogCoversTheDocumentedTools() {
        val names = ToolCatalog.ALL.map { it.name }
        assertEquals(
            listOf("list_fields", "search_fields", "get_field", "set_field", "set_many", "undo", "export", "explain_field"),
            names,
        )
        assertTrue(ToolCatalog.ALL.all { it.description.isNotBlank() })
        assertNotNull(ToolCatalog.byName("set_many"))
        assertTrue(ToolCatalog.VALUE_CHANGING.contains(ToolCatalog.SET_FIELD))
    }

    @Test
    fun unknownToolsFailCleanly() {
        val outcome = ToolExecutor(editor).execute(ToolCall.of("drop_table"), session())
        assertFalse(outcome.ok)
        assertTrue(outcome.text.contains("unknown tool"))
    }

    @Test
    fun get_fieldDescribesSiblings() {
        val outcome = ToolExecutor(editor).execute(ToolCall.of("get_field", "path" to "player.gold"), session())
        assertTrue(outcome.ok)
        assertTrue(outcome.text.contains("player.gold"))
        assertTrue(outcome.text.contains("siblings"))
    }

    // ---- privacy ----------------------------------------------------------------

    @Test
    fun bigFilesAreTrimmedBeforeTheyReachAProvider() {
        val tree = session().tree
        assertTrue(AiPayload.mustTrim(300 * 1024))
        assertFalse(AiPayload.mustTrim(10 * 1024))
        val trimmed = AiPayload.fieldSummary(tree, includeValues = false)
        assertTrue(trimmed.contains("player.gold: i32"))
        assertFalse(trimmed.contains("5000"))
        val withValues = AiPayload.fieldSummary(tree, includeValues = true)
        assertTrue(withValues.contains("player.gold: i32 = 5000"))
    }

    @Test
    fun keysAreRedactedEverywhere() {
        assertEquals("****cdef", AiPayload.redactKey("sk-ant-api03-abcdef"))
        assertEquals("****", AiPayload.redactKey("abc"))
        assertEquals("", AiPayload.redactKey(null))
        val redacted = AiPayload.redactText("Authorization: Bearer sk-ant-api03-1234567890abcdef")
        assertFalse(redacted.contains("1234567890abcdef"))
        assertTrue(redacted.contains("****"))
        assertTrue(AiPayload.redactText("api_key=\"abcd1234efgh\"").contains("****"))
    }
}
