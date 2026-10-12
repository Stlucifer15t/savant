package dev.savant.domain.session

import dev.savant.domain.model.EditSource
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.ImportedFile
import dev.savant.domain.parser.ExportVerifier
import dev.savant.domain.parser.ParseHints
import dev.savant.domain.parser.ParserRegistry
import dev.savant.parsers.json.JsonSaveParser
import dev.savant.parsers.xml.XmlSaveParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole edit flow: open, propose, apply, undo, rebuild, verify. */
class SaveEditorTest {

    private val registry = ParserRegistry(listOf(JsonSaveParser(), XmlSaveParser()))
    private val editor = SaveEditor(registry, ExportVerifier(registry))

    private val json = """{"player":{"name":"Aric","gold":5000,"maxHp":40},"flags":{"tutorialDone":false}}"""

    private fun file(name: String, bytes: ByteArray) = ImportedFile(
        uri = "content://test/$name",
        name = name,
        sizeBytes = bytes.size.toLong(),
        mimeType = null,
        importedAt = 0L,
        hexPreview = ImportedFile.hexPreview(bytes),
    )

    private fun open(text: String, name: String = "save.json"): EditorSession {
        val bytes = text.toByteArray()
        val outcome = editor.open(file(name, bytes), bytes)
        assertNotNull(outcome.failure?.chatLines?.joinToString(), outcome.session)
        return outcome.session!!
    }

    @Test
    fun opensAJsonSaveAndReportsTheFormat() {
        val session = open(json)
        assertEquals("JSON", session.formatBadge)
        assertEquals(4, session.tree.editableCount)
        assertTrue(session.detection.score > 0.9f)
        assertFalse(session.readOnly)
        assertTrue(session.log.any { it.text.contains("editable values") })
        assertEquals("7b 22 70 6c", session.file.hexPreview.take(11))
    }

    @Test
    fun refusesAnUnrecognisedFileWithAClearReason() {
        val bytes = ByteArray(256) { (it * 13 % 251).toByte() }
        val outcome = editor.open(file("mystery.dat", bytes), bytes)
        assertNull(outcome.session)
        assertNotNull(outcome.failure)
        assertTrue(outcome.failure!!.headline.contains("no parser"))
        assertTrue(outcome.failure!!.chatLines.size > 1)
        assertTrue(outcome.failure!!.detail.contains("binary"))
    }

    @Test
    fun refusesAnEmptyFile() {
        val outcome = editor.open(file("empty.json", ByteArray(0)), ByteArray(0))
        assertNull(outcome.session)
        assertTrue(outcome.failure!!.headline.contains("empty"))
    }

    @Test
    fun opensACompressedFileReadOnly() {
        val gzipped = dev.savant.codecs.GzipCodec.encode(json.toByteArray())
        val outcome = editor.open(file("save.json.gz", gzipped), gzipped)
        val session = outcome.session
        assertNotNull(session)
        assertTrue(session!!.readOnly)
        assertTrue(session.tree.readOnlyReason!!.contains("gzip"))
        // Values are still visible so the chat can report them.
        assertEquals(4, session.tree.editableCount)
        // ...but nothing can be written.
        val result = editor.apply(session, listOf(FieldEdit.of(session.tree.node("player.gold")!!, FieldValue.Int64(1))))
        assertEquals(0, result.applied.size)
        assertEquals(1, result.rejected.size)
    }

    @Test
    fun appliesAnEditAndKeepsHistory() {
        val session = open(json)
        val node = session.tree.node("player.gold")!!
        val outcome = editor.apply(session, listOf(FieldEdit.of(node, FieldValue.Int64(999999))))
        assertEquals(1, outcome.applied.size)
        assertEquals(1, outcome.session.history.size)
        assertEquals(999999L, (outcome.tree.node("player.gold")!!.value as FieldValue.Int64).value)
        // The original session object is untouched.
        assertEquals(0, session.history.size)
        assertTrue(outcome.lines.first().contains("applied 1 edit"))
    }

    @Test
    fun rejectsInvalidValuesAndReportsThem() {
        val session = open(json)
        val outcome = editor.apply(
            session,
            listOf(
                FieldEdit.of(session.tree.node("player.gold")!!, FieldValue.Text("lots")),
                FieldEdit.of(session.tree.node("flags.tutorialDone")!!, FieldValue.Int64(7)),
            ),
        )
        assertEquals(0, outcome.applied.size)
        assertEquals(2, outcome.rejected.size)
        assertTrue(outcome.rejected[0].second.contains("number"))
        assertTrue(outcome.rejected[1].second.contains("true or false"))
        assertEquals(0, outcome.session.history.size)
    }

    @Test
    fun ignoresNoOpEdits() {
        val session = open(json)
        val node = session.tree.node("player.gold")!!
        val outcome = editor.apply(session, listOf(FieldEdit.of(node, FieldValue.Int64(5000))))
        assertEquals(0, outcome.applied.size)
        assertTrue(outcome.lines.first().contains("nothing to change"))
    }

    @Test
    fun undoRestoresThePreviousValues() {
        val session = open(json)
        val applied = editor.apply(
            session,
            listOf(
                FieldEdit.of(session.tree.node("player.gold")!!, FieldValue.Int64(1)),
                FieldEdit.of(session.tree.node("player.maxHp")!!, FieldValue.Int64(2)),
            ),
            note = "max all stats",
        )
        assertEquals(2, applied.applied.size)
        val undone = editor.undo(applied.session)
        assertEquals(2, undone.applied.size)
        assertEquals(0, undone.session.history.size)
        assertEquals(5000L, (undone.tree.node("player.gold")!!.value as FieldValue.Int64).value)
        assertEquals(40L, (undone.tree.node("player.maxHp")!!.value as FieldValue.Int64).value)
        assertTrue(undone.applied.all { it.source == EditSource.UNDO })
        // Undoing twice is a no-op, not a crash.
        val again = editor.undo(undone.session)
        assertEquals(0, again.applied.size)
        assertTrue(again.lines.first().contains("nothing"))
        assertEquals(5000L, (again.tree.node("player.gold")!!.value as FieldValue.Int64).value)
    }

    @Test
    fun rebuildProducesANewVerifiedFile() {
        val session = open(json)
        val applied = editor.apply(
            session,
            listOf(FieldEdit.of(session.tree.node("player.gold")!!, FieldValue.Int64(999999))),
        )
        val artifact = editor.rebuild(applied.session)
        assertEquals("save.edited.json", artifact.fileName)
        assertTrue(artifact.usable)
        assertTrue(artifact.report.summary, artifact.report.ok)
        assertEquals(1, artifact.report.checkedEdits)
        val text = String(artifact.bytes)
        assertTrue(text.contains("\"gold\":999999"))
        assertFalse(text.contains("5000"))
        // The untouched parts are byte identical to the source.
        assertTrue(text.contains("\"name\":\"Aric\""))
    }

    @Test
    fun rebuildWithoutEditsReproducesTheOriginal() {
        val session = open(json)
        val artifact = editor.rebuild(session)
        assertEquals(json, String(artifact.bytes))
        assertTrue(artifact.usable)
        assertEquals(0, artifact.report.checkedEdits)
    }

    @Test
    fun exportNamesNeverOverwriteTheOriginal() {
        assertEquals("save.edited.json", editor.exportNameFor("save.json"))
        assertEquals("slot 1.edited.rpgsave", editor.exportNameFor("slot 1.rpgsave"))
        assertEquals("noext.edited", editor.exportNameFor("noext"))
        assertEquals("a.b.edited.json", editor.exportNameFor("a.b.json"))
    }

    @Test
    fun proposeValidatesBeforeBuildingAnEdit() {
        val session = open(json)
        val gold = session.tree.node("player.gold")!!
        val (edit, problem) = editor.propose(gold, "999999", EditSource.USER_COMMAND)
        assertNotNull(edit)
        assertNull(problem)
        assertEquals(EditSource.USER_COMMAND, edit!!.source)

        val (bad, reason) = editor.propose(gold, "lots", EditSource.USER_COMMAND)
        assertNull(bad)
        assertNotNull(reason)

        val (same, sameReason) = editor.propose(gold, "5000", EditSource.USER_COMMAND)
        assertNull(same)
        assertTrue(sameReason!!.contains("already"))
    }

    @Test
    fun xmlSavesGoThroughTheSameFlow() {
        val xml = """<?xml version="1.0"?><save><gold>5</gold><hp>1</hp></save>"""
        val session = open(xml, "save.xml")
        assertEquals("XML", session.formatBadge)
        val applied = editor.apply(
            session,
            listOf(FieldEdit.of(session.tree.node("save.gold")!!, FieldValue.Int64(500))),
        )
        assertEquals(1, applied.applied.size)
        val artifact = editor.rebuild(applied.session)
        assertTrue(artifact.usable)
        assertEquals("""<?xml version="1.0"?><save><gold>500</gold><hp>1</hp></save>""", String(artifact.bytes))
    }

    @Test
    fun hintsCanForceAFormat() {
        val bytes = json.toByteArray()
        val outcome = editor.open(file("save.dat", bytes), bytes, ParseHints(), dev.savant.domain.model.SaveFormat.JSON)
        assertNotNull(outcome.session)
        assertEquals("JSON", outcome.session!!.formatBadge)
    }

    @Test
    fun groupsAreAvailableForTheChatSummary() {
        val session = open(json)
        val groups = session.tree.groups()
        assertTrue(groups.any { it.category == FieldCategory.CURRENCY && it.count == 1 })
        assertTrue(groups.any { it.category == FieldCategory.FLAGS })
    }
}
