package dev.savant.parsers.ini

import dev.savant.codecs.GzipCodec
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.displayText
import dev.savant.domain.parser.CorruptFileException
import dev.savant.domain.parser.ExportVerifier
import dev.savant.domain.parser.ParseHints
import dev.savant.domain.parser.ParseRequest
import dev.savant.domain.parser.ParserRegistry
import dev.savant.domain.parser.SavantParserException
import dev.savant.parsers.json.JsonSaveParser
import dev.savant.parsers.xml.XmlSaveParser
import dev.savant.plugins.MappedParser
import dev.savant.plugins.PluginCodec
import dev.savant.plugins.PluginDecode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round trip contract for the ini parser.
 *
 * The sample is deliberately messy: crlf endings, a bom-less utf-8 name, both comment characters,
 * inline comments, both separators, tabs, quoted values, a duplicate key, a repeated section, an
 * empty value, a continuation line, and no newline after the last line. Every one of those has to
 * come back byte for byte when nothing was edited.
 */
class IniSaveParserTest {

    private val parser = IniSaveParser()
    private val registry = ParserRegistry(listOf(JsonSaveParser(), XmlSaveParser(), parser))

    private val save = listOf(
        "; savegame v3",
        "version = 3",
        "",
        "[Player]",
        "name = \"Aric ; the Bold\"   ; quoted, so the semicolon is part of the name",
        "gold=5000",
        "hp :\t42",
        "ratio = 12.5",
        "title = José",
        "",
        "# options",
        "[Options]",
        "Fullscreen=Yes",
        "vsync = off",
        "music = TRUE",
        "path = C:\\Games\\Quest ; install dir",
        "notes =",
        "",
        "[Hotkeys]",
        "bind = jump",
        "bind = crouch",
        "    continuation line that is not a key",
        "",
        "[Player]",
        "lives = 3",
    ).joinToString("\r\n")

    private fun parse(text: String, name: String? = "save.ini", hints: ParseHints = ParseHints()) =
        parser.parse(ParseRequest(text.toByteArray(), name, hints))

    private fun bytesOf(text: String) = text.toByteArray()

    // ---- detection ---------------------------------------------------------------

    @Test
    fun detectsIniByExtensionAndShape() {
        assertTrue(parser.detect(bytesOf(save), "save.ini").score >= 0.9f)
        assertTrue(parser.detect(bytesOf(save), "settings.cfg").score >= 0.9f)
        // Content alone is enough to be likely, not certain.
        val bare = parser.detect(bytesOf(save), "savegame")
        assertTrue(bare.reason, bare.score >= 0.6f && bare.score < 0.9f)
    }

    @Test
    fun doesNotClaimJsonOrXmlAndIsNotClaimedByThem() {
        assertTrue(parser.detect(bytesOf("""{"a":1}"""), "save.json").score < 0.3f)
        assertTrue(parser.detect(bytesOf("<save><a>1</a></save>"), "save.xml").score < 0.3f)
        assertEquals("INI", registry.best(bytesOf(save), "save.ini")!!.format.badge)
        assertEquals("JSON", registry.best(bytesOf("""{"gold":5}"""), "save.json")!!.format.badge)
        assertEquals("XML", registry.best(bytesOf("<save><gold>5</gold></save>"), "save.xml")!!.format.badge)
    }

    @Test
    fun anIniExtensionOnNonIniContentIsCapped() {
        val verdict = parser.detect(bytesOf("just some prose\nwith no structure at all\n"), "readme.ini")
        assertTrue(verdict.reason, verdict.score <= 0.25f)
    }

    // ---- round trip --------------------------------------------------------------

    @Test
    fun untouchedFileRoundTripsByteForByte() {
        assertArrayEquals(bytesOf(save), parse(save).document.toBytes())
    }

    @Test
    fun bomAndLfAndTrailingNewlineRoundTrip() {
        val text = "\uFEFF[a]\nx=1\n\n"
        assertArrayEquals(text.toByteArray(), parse(text).document.toBytes())
    }

    @Test
    fun parseWriteParseKeepsEveryValue() {
        val first = parse(save)
        val gold = first.tree.node("Player.gold")!!
        first.document.applyEdits(listOf(FieldEdit.of(gold, FieldValue.Int64(1))))
        val rebuilt = first.document.toBytes()
        val second = parse(String(rebuilt))
        // ParseResult.tree is the snapshot from before the edit; the document holds the live tree.
        val live = first.document.tree
        assertEquals(live.editableCount, second.tree.editableCount)
        for (node in live.editableNodes) {
            val again = second.tree.node(node.path)
            assertNotNull("missing ${node.path}", again)
            assertTrue(node.path, node.sameValueAs(again))
        }
        assertEquals(1L, (second.tree.node("Player.gold")!!.value as FieldValue.Int64).value)
    }

    // ---- structure ---------------------------------------------------------------

    @Test
    fun sectionsKeysAndGlobalsAreAddressable() {
        val tree = parse(save).tree
        assertEquals(3L, (tree.node("version")!!.value as FieldValue.Int64).value)
        assertEquals(5000L, (tree.node("Player.gold")!!.value as FieldValue.Int64).value)
        assertEquals(42L, (tree.node("Player.hp")!!.value as FieldValue.Int64).value)
        assertEquals(12.5, (tree.node("Player.ratio")!!.value as FieldValue.Real).value, 0.0001)
        assertEquals("José", (tree.node("Player.title")!!.value as FieldValue.Text).value)
        assertEquals("C:\\Games\\Quest", (tree.node("Options.path")!!.value as FieldValue.Text).value)
        assertEquals("", (tree.node("Options.notes")!!.value as FieldValue.Text).value)
        assertEquals(FieldType.OBJECT, tree.node("Player")!!.type)
        assertFalse(tree.node("Player")!!.editable)
    }

    @Test
    fun quotedValuesAreStringsWithTheQuotesRemoved() {
        val tree = parse(save).tree
        val name = tree.node("Player.name")!!
        assertEquals(FieldType.STRING, name.type)
        assertEquals("Aric ; the Bold", (name.value as FieldValue.Text).value)
        // A quoted number is still a string: the quotes were somebody's way of saying so.
        val quoted = parse("[a]\nid = \"007\"\n").tree.node("a.id")!!
        assertEquals(FieldType.STRING, quoted.type)
        assertEquals("007", (quoted.value as FieldValue.Text).value)
    }

    @Test
    fun configBooleansAreTyped() {
        val tree = parse(save).tree
        assertEquals(FieldValue.Bool(true), tree.node("Options.Fullscreen")!!.value)
        assertEquals(FieldValue.Bool(false), tree.node("Options.vsync")!!.value)
        assertEquals(FieldValue.Bool(true), tree.node("Options.music")!!.value)
        assertEquals(FieldType.BOOL, tree.node("Options.vsync")!!.type)
        assertEquals(FieldCategory.FLAGS, tree.node("Options.Fullscreen")!!.category)
        assertEquals(FieldCategory.CURRENCY, tree.node("Player.gold")!!.category)
    }

    @Test
    fun duplicateKeysAreSuffixedAndRepeatedSectionsAreMerged() {
        val result = parse(save)
        val tree = result.tree
        assertEquals("jump", (tree.node("Hotkeys.bind")!!.value as FieldValue.Text).value)
        assertEquals("crouch", (tree.node("Hotkeys.bind#2")!!.value as FieldValue.Text).value)
        // The second [Player] block lands in the first Player node.
        assertEquals(3L, (tree.node("Player.lives")!!.value as FieldValue.Int64).value)
        assertEquals(1, tree.roots.count { it.path == "Player" })
        assertTrue(tree.warnings.any { it.contains("[Player] appears more than once") })
        // The continuation line is not a field, and nothing else claims it.
        assertNull(tree.all.firstOrNull { it.path.contains("continuation") })
    }

    @Test
    fun sectionNamesWithDotsAndSlashesAreOneSegment() {
        val tree = parse("[/Script/Engine.GameUserSettings]\nResolutionSizeX=1920\n[remote \"origin\"]\nurl = git://x\n").tree
        val node = tree.node("`/Script/Engine.GameUserSettings`.ResolutionSizeX")
        assertNotNull(node)
        assertEquals(1920L, (node!!.value as FieldValue.Int64).value)
        assertEquals("git://x", (tree.node("`remote \"origin\"`.url")!!.value as FieldValue.Text).value)
    }

    @Test
    fun nodeCapKeepsTheFileWhole() {
        val result = parse(save, hints = ParseHints(maxNodes = 3))
        assertTrue(result.tree.truncated)
        assertTrue(result.tree.warnings.any { it.contains("first 3 values") })
        assertArrayEquals(bytesOf(save), result.document.toBytes())
    }

    // ---- editing -----------------------------------------------------------------

    @Test
    fun editingAValueChangesOnlyThatValue() {
        val result = parse(save)
        val applied = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("Player.gold")!!, FieldValue.Int64(999999))),
        )
        assertEquals(1, applied.applied.size)
        val expected = save.replace("gold=5000", "gold=999999")
        assertArrayEquals(bytesOf(expected), result.document.toBytes())
    }

    @Test
    fun separatorSpacingTabsAndInlineCommentsSurvive() {
        val result = parse(save)
        result.document.applyEdits(
            listOf(
                FieldEdit.of(result.tree.node("Player.hp")!!, FieldValue.Int64(7)),
                FieldEdit.of(result.tree.node("Options.path")!!, FieldValue.Text("D:\\Quest")),
                FieldEdit.of(result.tree.node("Options.notes")!!, FieldValue.Text("hello")),
            ),
        )
        val rebuilt = String(result.document.toBytes())
        assertTrue(rebuilt, rebuilt.contains("hp :\t7\r\n"))
        assertTrue(rebuilt, rebuilt.contains("path = D:\\Quest ; install dir\r\n"))
        assertTrue(rebuilt, rebuilt.contains("notes =hello\r\n"))
    }

    @Test
    fun editingAQuotedValueKeepsTheQuotes() {
        val result = parse(save)
        result.document.applyEdits(listOf(FieldEdit.of(result.tree.node("Player.name")!!, FieldValue.Text("Bea"))))
        val rebuilt = String(result.document.toBytes())
        assertTrue(rebuilt, rebuilt.contains("name = \"Bea\"   ; quoted, so the semicolon is part of the name"))
    }

    @Test
    fun booleansAreWrittenInTheFilesOwnSpelling() {
        val result = parse(save)
        result.document.applyEdits(
            listOf(
                FieldEdit.of(result.tree.node("Options.Fullscreen")!!, FieldValue.Bool(false)),
                FieldEdit.of(result.tree.node("Options.vsync")!!, FieldValue.Bool(true)),
                FieldEdit.of(result.tree.node("Options.music")!!, FieldValue.Bool(false)),
            ),
        )
        val rebuilt = String(result.document.toBytes())
        assertTrue(rebuilt, rebuilt.contains("Fullscreen=No\r\n"))
        assertTrue(rebuilt, rebuilt.contains("vsync = on\r\n"))
        assertTrue(rebuilt, rebuilt.contains("music = FALSE\r\n"))
    }

    @Test
    fun aValueThatWouldReadBackDifferentlyIsQuoted() {
        val result = parse("[a]\nx = plain\n")
        result.document.applyEdits(listOf(FieldEdit.of(result.tree.node("a.x")!!, FieldValue.Text("left ; right"))))
        val rebuilt = String(result.document.toBytes())
        assertEquals("[a]\nx = \"left ; right\"\n", rebuilt)
        // And it reads back as exactly what was written.
        assertEquals("left ; right", (parse(rebuilt).tree.node("a.x")!!.value as FieldValue.Text).value)
    }

    @Test
    fun aLineBreakInAValueIsRefused() {
        val result = parse(save)
        val applied = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("Player.title")!!, FieldValue.Text("two\nlines"))),
        )
        assertEquals(0, applied.applied.size)
        assertTrue(applied.rejected.single().reason.contains("line break"))
        assertArrayEquals(bytesOf(save), result.document.toBytes())
    }

    @Test
    fun aQuoteInsideAQuotedValueIsRefused() {
        val result = parse(save)
        val applied = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("Player.name")!!, FieldValue.Text("say \"hi\""))),
        )
        assertEquals(0, applied.applied.size)
        assertTrue(applied.rejected.single().reason.contains("quote"))
    }

    @Test
    fun typeRulesApply() {
        val result = parse(save)
        val applied = result.document.applyEdits(
            listOf(
                FieldEdit.of(result.tree.node("Player.gold")!!, FieldValue.Text("lots")),
                FieldEdit.of(result.tree.node("Options.vsync")!!, FieldValue.Int64(1)),
                FieldEdit.of(result.tree.node("Player.gold")!!, FieldValue.Int64(1L shl 40)),
            ),
        )
        assertEquals(2, applied.rejected.size)
        // A text format widens: i32 -> a value past int32 is a legal edit.
        assertEquals(1, applied.applied.size)
        assertTrue(String(result.document.toBytes()).contains("gold=${1L shl 40}"))
    }

    @Test
    fun utf8ValuesKeepTheirBytesWhenSomethingElseIsEdited() {
        val result = parse(save)
        result.document.applyEdits(listOf(FieldEdit.of(result.tree.node("version")!!, FieldValue.Int64(4))))
        val rebuilt = result.document.toBytes()
        val expected = bytesOf(save.replace("version = 3", "version = 4"))
        assertArrayEquals(expected, rebuilt)
    }

    // ---- codecs and failure modes --------------------------------------------------

    @Test
    fun aSniffedCodecOpensReadOnly() {
        val packed = GzipCodec.encode(bytesOf(save))
        val result = parser.parse(ParseRequest(packed, "save.ini"))
        assertTrue(result.readOnly)
        assertTrue(result.tree.readOnlyReason!!.contains("gzip"))
        assertEquals(5000L, (result.tree.node("Player.gold")!!.value as FieldValue.Int64).value)
        val applied = result.document.applyEdits(listOf(FieldEdit.of(result.tree.node("Player.gold")!!, FieldValue.Int64(1))))
        assertEquals(0, applied.applied.size)
    }

    @Test
    fun aDeclaredCodecIsWritable() {
        val packed = GzipCodec.encode(bytesOf(save))
        val result = parser.parse(ParseRequest(packed, "save.ini", ParseHints(codec = GzipCodec)))
        assertFalse(result.readOnly)
        result.document.applyEdits(listOf(FieldEdit.of(result.tree.node("Player.gold")!!, FieldValue.Int64(1))))
        val unpacked = GzipCodec.decode(result.document.toBytes())
        assertArrayEquals(bytesOf(save.replace("gold=5000", "gold=1")), unpacked)
    }

    @Test
    fun emptyAndKeylessFilesFailLoudly() {
        assertTrue(runCatching { parse("") }.exceptionOrNull() is CorruptFileException)
        val error = runCatching { parse("; only a comment\n\n[section]\n") }.exceptionOrNull()
        assertTrue(error is SavantParserException)
        assertTrue(error!!.message!!.contains("nothing to edit"))
    }

    @Test
    fun unknownPathsAreRejectedNotWritten() {
        val result = parse(save)
        val edit = FieldEdit.of(result.tree.node("Player.gold")!!, FieldValue.Int64(1)).copy(path = "Player.nope")
        val applied = result.document.applyEdits(listOf(edit))
        assertEquals("no such field: Player.nope", applied.rejected.single().reason)
    }

    // ---- verification and plugins -----------------------------------------------------

    @Test
    fun rebuiltIniIsVerified() {
        val result = parse(save)
        val applied = result.document.applyEdits(
            listOf(
                FieldEdit.of(result.tree.node("Player.gold")!!, FieldValue.Int64(4)),
                FieldEdit.of(result.tree.node("Options.Fullscreen")!!, FieldValue.Bool(false)),
                FieldEdit.of(result.tree.node("Hotkeys.bind#2")!!, FieldValue.Text("slide")),
            ),
        )
        assertEquals(3, applied.applied.size)
        val report = ExportVerifier(registry).verify(result.document.toBytes(), "save.ini", applied.applied, parser)
        assertTrue(report.summary, report.ok)
    }

    @Test
    fun anIniPluginLaysNamesAndLimitsOverTheFile() {
        val decoded = PluginCodec.decode(
            """
            {
              "gameName": "example quest",
              "fileMatch": ["save*.ini"],
              "parser": "ini",
              "fields": [
                { "path": "Player.gold", "type": "i32", "name": "Gold", "category": "currency", "max": 9999 },
                { "path": "Player.hp", "type": "i32", "name": "Health", "category": "stats" }
              ]
            }
            """.trimIndent(),
        )
        assertTrue(decoded.toString(), decoded is PluginDecode.Ok)
        val mapped = MappedParser((decoded as PluginDecode.Ok).plugin, parser)
        val result = mapped.parse(ParseRequest(bytesOf(save), "save.ini"))
        assertEquals("Gold", result.tree.node("Player.gold")!!.label)
        assertEquals("Health", result.tree.node("Player.hp")!!.label)
        val applied = result.document.applyEdits(listOf(FieldEdit.of(result.tree.node("Player.gold")!!, FieldValue.Int64(10000))))
        assertEquals(0, applied.applied.size)
        assertTrue(applied.rejected.single().reason.contains("maximum"))
        // The plugin prefers the file it claims and leaves the rest to the plain parser.
        assertTrue(mapped.detect(bytesOf(save), "save.ini").score > parser.detect(bytesOf(save), "save.ini").score ||
            mapped.detect(bytesOf(save), "save.ini").score >= 0.99f)
        assertEquals(parser.detect(bytesOf(save), "other.cfg").score, mapped.detect(bytesOf(save), "other.cfg").score, 0.0001f)
    }

    @Test
    fun anIniPluginRefusesOffsets() {
        val decoded = PluginCodec.decode(
            """{ "gameName": "x", "fileMatch": ["*.ini"], "parser": "ini", "fields": [ { "path": "a.b", "offset": 4, "type": "i32" } ] }""",
        )
        assertTrue(decoded is PluginDecode.Invalid)
    }

    @Test
    fun displayTextMatchesTheFile() {
        val tree = parse(save).tree
        assertEquals("12.5", tree.node("Player.ratio")!!.value!!.displayText())
        assertEquals("5000", tree.node("Player.gold")!!.value!!.displayText())
    }
}
