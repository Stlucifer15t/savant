package dev.savant.data.history

import dev.savant.domain.chat.ChatRole
import dev.savant.domain.chat.DiffEntry
import dev.savant.domain.chat.DiffStatus
import dev.savant.domain.chat.ExportEntry
import dev.savant.domain.chat.FieldsEntry
import dev.savant.domain.chat.FileEntry
import dev.savant.domain.chat.NoticeEntry
import dev.savant.domain.chat.ParseEntry
import dev.savant.domain.chat.TextEntry
import dev.savant.domain.model.Confidence
import dev.savant.domain.model.EditSource
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldGroup
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.ImportedFile
import dev.savant.domain.model.Node
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.parser.ParseLogLine
import dev.savant.domain.parser.VerificationMismatch
import dev.savant.domain.parser.VerificationReport
import dev.savant.domain.session.ExportArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a transcript stores, and what it deliberately does not.
 *
 * These are the tests that keep history honest: the searchable text has to contain the paths and
 * both sides of a diff, the payload has to carry the verdicts, and nothing that belongs to the file
 * — a field list, rebuilt bytes — may pretend to still be live after a restart.
 */
class EntryCodecTest {

    private val sessionId = "session-1"

    @Test
    fun `text entries round trip with role and error flag`() {
        val entry = TextEntry(
            id = "t1",
            role = ChatRole.USER,
            lines = listOf("set gold to 999999", "and max the stats"),
            atMillis = 1_700_000_000_000,
        )

        val stored = EntryCodec.encode(entry, sessionId, 3)
        val back = EntryCodec.decode(stored) as TextEntry

        assertEquals(EntryKinds.TEXT, stored.kind)
        assertEquals(ChatRole.USER.name, stored.role)
        assertEquals(3, stored.position)
        assertEquals(sessionId, stored.sessionId)
        assertEquals(entry.id, back.id)
        assertEquals(entry.role, back.role)
        assertEquals(entry.lines, back.lines)
        assertEquals(entry.atMillis, back.atMillis)
        assertFalse(back.isError)
    }

    @Test
    fun `the body of a text entry is the text itself, so sql search finds it`() {
        val stored = EntryCodec.encode(
            TextEntry(id = "t2", role = ChatRole.ASSISTANT, lines = listOf("found 42 editable values")),
            sessionId,
            0,
        )
        assertEquals("found 42 editable values", stored.body)
    }

    @Test
    fun `error copy keeps its flag`() {
        val entry = TextEntry(id = "t3", role = ChatRole.ASSISTANT, lines = listOf("could not open that file"), isError = true)
        val stored = EntryCodec.encode(entry, sessionId, 1)
        assertTrue(stored.isError)
        assertEquals(true, (EntryCodec.decode(stored) as TextEntry).isError)
    }

    @Test
    fun `file entries round trip including detection`() {
        val entry = FileEntry(
            id = "f1",
            file = ImportedFile(
                uri = "content://downloads/save.json",
                name = "save.json",
                sizeBytes = 4_096,
                mimeType = "application/json",
                importedAt = 1_700_000_000_000,
                hexPreview = "7b 22 67 6c",
                origin = ImportedFile.Origin.SHARE,
            ),
            formatBadge = "json",
            confidence = Confidence(0.92f, "looks like json"),
            atMillis = 1_700_000_000_001,
        )

        val stored = EntryCodec.encode(entry, sessionId, 0)
        val back = EntryCodec.decode(stored) as FileEntry

        assertEquals(entry.file, back.file)
        assertEquals(entry.formatBadge, back.formatBadge)
        assertEquals(0.92f, back.confidence?.score ?: 0f, 0.0001f)
        assertEquals("looks like json", back.confidence?.reason)
        assertTrue(stored.body.contains("save.json"))
        assertTrue(stored.body.contains("json"))
    }

    @Test
    fun `parse entries keep every log line`() {
        val entry = ParseEntry(
            id = "p1",
            log = listOf(
                ParseLogLine("reading 4096 bytes"),
                ParseLogLine("format json · confidence 0.92"),
                ParseLogLine("indexed 42 values"),
            ),
            done = true,
        )

        val stored = EntryCodec.encode(entry, sessionId, 2)
        val back = EntryCodec.decode(stored) as ParseEntry

        assertEquals(entry.log.map { it.text }, back.log.map { it.text })
        assertTrue(back.done)
        assertTrue(stored.body.contains("indexed 42 values"))
    }

    @Test
    fun `a field card stores its summary but not the values`() {
        val entry = FieldsEntry(
            id = "v1",
            headline = "found 14 editable values",
            groups = listOf(
                FieldGroup(FieldCategory.CURRENCY, listOf(node("gold", 999))),
                FieldGroup(FieldCategory.STATS, listOf(node("hp", 40), node("mp", 12))),
                FieldGroup(FieldCategory.FLAGS, emptyList()),
            ),
            totalCount = 14,
            editableCount = 3,
            collapsed = setOf(FieldCategory.OTHER.name),
        )

        val stored = EntryCodec.encode(entry, sessionId, 4)
        val back = EntryCodec.decode(stored) as FieldsEntry

        assertEquals("found 14 editable values", back.headline)
        assertEquals(14, back.totalCount)
        assertEquals(3, back.editableCount)
        assertEquals(setOf(FieldCategory.OTHER.name), back.collapsed)
        assertEquals(listOf("currency 1", "stats 2", "flags 0"), back.summaryLines)

        // The values are gone on purpose: they belong to the file, not to the transcript.
        assertTrue(back.restored)
        assertTrue(back.groups.isEmpty())
        assertFalse(stored.body.contains("999"))
        assertFalse(stored.payload.contains("999"))
    }

    @Test
    fun `a diff stores both sides of every edit, searchable`() {
        val entry = DiffEntry(
            id = "d1",
            edits = listOf(
                FieldEdit(
                    path = "player.gold",
                    displayName = "Gold",
                    type = FieldType.INT32,
                    oldValue = FieldValue.Int64(120),
                    newValue = FieldValue.Int64(999999),
                    source = EditSource.USER_COMMAND,
                    max = 2147483647.0,
                ),
                FieldEdit(
                    path = "player.name",
                    displayName = "Name",
                    type = FieldType.STRING,
                    oldValue = FieldValue.Text("hero"),
                    newValue = FieldValue.Text("savant"),
                ),
            ),
            status = DiffStatus.APPLIED,
            note = "from chat",
            dropped = setOf("player.name"),
            appliedCount = 1,
        )

        val stored = EntryCodec.encode(entry, sessionId, 5)
        val back = EntryCodec.decode(stored) as DiffEntry

        assertTrue(stored.body.contains("player.gold: 120 -> 999999"))
        // Text values display quoted, which keeps a string searchable as a string.
        assertTrue(stored.body.contains("player.name: \"hero\" -> \"savant\""))

        assertEquals(entry.status, back.status)
        assertEquals(entry.note, back.note)
        assertEquals(entry.dropped, back.dropped)
        assertEquals(entry.appliedCount, back.appliedCount)
        assertEquals(2, back.edits.size)

        val gold = back.edits.first()
        assertEquals("player.gold", gold.path)
        assertEquals("Gold", gold.displayName)
        assertEquals(FieldType.INT32, gold.type)
        assertEquals(FieldValue.Int64(120), gold.oldValue)
        assertEquals(FieldValue.Int64(999999), gold.newValue)
        assertEquals(EditSource.USER_COMMAND, gold.source)
        assertEquals(2147483647.0, gold.max ?: 0.0, 0.0)
        assertNull(gold.min)

        val name = back.edits[1]
        assertEquals(FieldValue.Text("hero"), name.oldValue)
        assertEquals(FieldValue.Text("savant"), name.newValue)
    }

    @Test
    fun `text that looks like a number stays text through a diff`() {
        val edit = FieldEdit(
            path = "code",
            displayName = "Code",
            type = FieldType.STRING,
            oldValue = FieldValue.Text("007"),
            newValue = FieldValue.Text("008"),
        )
        val back = EntryCodec.decode(
            EntryCodec.encode(DiffEntry(id = "d2", edits = listOf(edit), status = DiffStatus.PENDING, note = null), sessionId, 0),
        ) as DiffEntry

        assertEquals(FieldValue.Text("007"), back.edits.first().oldValue)
        assertEquals(FieldValue.Text("008"), back.edits.first().newValue)
    }

    @Test
    fun `an export card keeps its verdict but not its bytes`() {
        val entry = ExportEntry(
            id = "e1",
            artifact = ExportArtifact(
                fileName = "save.edited.json",
                bytes = ByteArray(2_048) { 1 },
                report = VerificationReport(
                    ok = true,
                    format = SaveFormat.JSON,
                    sizeBytes = 2_048,
                    checkedEdits = 3,
                    mismatches = emptyList<VerificationMismatch>(),
                    warnings = listOf("unquoted key at line 4"),
                    reparsedReadOnly = false,
                ),
                builtAt = 1_700_000_000_500,
            ),
            shared = true,
            savedTo = "downloads/save.edited.json",
        )

        val stored = EntryCodec.encode(entry, sessionId, 6)
        val back = EntryCodec.decode(stored) as ExportEntry

        assertEquals("save.edited.json", back.artifact.fileName)
        assertEquals(SaveFormat.JSON, back.artifact.report.format)
        assertTrue(back.artifact.report.ok)
        assertEquals(3, back.artifact.report.checkedEdits)
        assertEquals(listOf("unquoted key at line 4"), back.artifact.report.warnings)
        assertEquals(2_048, back.artifact.report.sizeBytes)
        assertEquals(entry.artifact.builtAt, back.artifact.builtAt)
        assertTrue(back.shared)
        assertEquals("downloads/save.edited.json", back.savedTo)

        // The rebuilt bytes are not history. A restored card says so instead of offering a share.
        assertTrue(back.restored)
        assertEquals(0, back.artifact.bytes.size)
        // The verdict survives; the file does not. That is the whole point of the restored flag.
        assertTrue(back.artifact.report.ok)
        assertTrue(back.artifact.usable)
        assertTrue(stored.body.contains("verified"))
    }

    @Test
    fun `a notice round trips`() {
        val entry = NoticeEntry(id = "n1", title = "read only", lines = listOf("codec=\"xor\" is not reversible"))
        val stored = EntryCodec.encode(entry, sessionId, 7)
        val back = EntryCodec.decode(stored) as NoticeEntry

        assertEquals(entry.title, back.title)
        assertEquals(entry.lines, back.lines)
        assertTrue(stored.body.contains("read only"))
        assertTrue(stored.isError)
    }

    @Test
    fun `an unknown kind is skipped instead of crashing the transcript`() {
        val stored = StoredEntry(
            id = "x1",
            sessionId = sessionId,
            kind = "something-from-a-future-build",
            role = null,
            body = "hello",
            payload = "{}",
            isError = false,
            createdAt = 1L,
            position = 0,
        )
        assertNull(EntryCodec.decode(stored))
    }

    @Test
    fun `a corrupt payload falls back to the stored body`() {
        val stored = StoredEntry(
            id = "x2",
            sessionId = sessionId,
            kind = EntryKinds.TEXT,
            role = ChatRole.USER.name,
            body = "set gold to 5",
            payload = "not json at all",
            isError = false,
            createdAt = 2L,
            position = 1,
        )
        val back = EntryCodec.decode(stored) as TextEntry
        assertEquals(listOf("set gold to 5"), back.lines)
    }

    @Test
    fun `every kind name is distinct`() {
        val kinds = listOf(
            EntryKinds.TEXT,
            EntryKinds.FILE,
            EntryKinds.PARSE,
            EntryKinds.FIELDS,
            EntryKinds.DIFF,
            EntryKinds.EXPORT,
            EntryKinds.NOTICE,
        )
        assertEquals(kinds.size, kinds.toSet().size)
    }

    @Test
    fun `the snippet of a stored entry is one readable line`() {
        val fields = EntryCodec.encode(
            FieldsEntry(id = "s1", headline = "found 8 editable values", groups = emptyList(), totalCount = 8, editableCount = 8),
            sessionId,
            0,
        )
        assertEquals("found 8 editable values", EntryCodec.snippet(fields))

        val export = EntryCodec.encode(
            ExportEntry(
                id = "s2",
                artifact = ExportArtifact(
                    fileName = "out.bin",
                    bytes = ByteArray(0),
                    report = VerificationReport(
                        ok = false,
                        format = SaveFormat.BINARY,
                        sizeBytes = 0,
                        checkedEdits = 1,
                        mismatches = listOf(VerificationMismatch("a", "1", "2")),
                        warnings = emptyList(),
                        reparsedReadOnly = true,
                    ),
                ),
            ),
            sessionId,
            1,
        )
        assertTrue(EntryCodec.snippet(export).startsWith("out.bin"))
        assertTrue(EntryCodec.snippet(export).contains("verification failed"))
    }

    private fun node(path: String, value: Long) = Node(
        path = path,
        displayName = path,
        type = FieldType.INT32,
        value = FieldValue.Int64(value),
        category = FieldCategory.OTHER,
        editable = true,
    )
}
