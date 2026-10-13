package dev.savant.parsers.json

import dev.savant.codecs.GzipCodec
import dev.savant.domain.model.EditValidator
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.asText
import dev.savant.domain.model.displayText
import dev.savant.domain.parser.CorruptFileException
import dev.savant.domain.parser.ExportVerifier
import dev.savant.domain.parser.ParseRequest
import dev.savant.domain.parser.ParserRegistry
import dev.savant.domain.parser.SavantParserException
import dev.savant.parsers.xml.XmlSaveParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round trip contract for the json parser:
 * parse -> write -> parse equals the original, and an untouched file is byte identical.
 */
class JsonSaveParserTest {

    private val parser = JsonSaveParser()
    private val registry = ParserRegistry(listOf(parser, XmlSaveParser()))

    private val compact = """{"version":3,"player":{"name":"Aric","gold":5000,"hp":42.5,"dead":false,"tags":["a","b"]},"flags":{"tutorial":true}}"""

    private val pretty = """
        {
          "version": 3,
          "player": {
            "gold": 5000,
            "items": [
              {"id": 7, "count": 2},
              {"id": 9, "count": 1}
            ]
          }
        }
    """.trimIndent() + "\n"

    private fun parse(text: String, name: String? = "save.json") = parser.parse(ParseRequest(text.toByteArray(), name))

    // ---- detection ---------------------------------------------------------------

    @Test
    fun detectsJsonByExtensionAndStructure() {
        val confidence = parser.detect(compact.toByteArray(), "save.json")
        assertTrue("expected a high score, got ${confidence.score}", confidence.score >= 0.9f)
        assertTrue(confidence.reason.contains("json"))
    }

    @Test
    fun detectsJsonWithoutAnExtension() {
        val confidence = parser.detect("""[1,2,3]""".toByteArray(), null)
        assertTrue(confidence.score >= 0.8f)
    }

    @Test
    fun doesNotClaimXmlOrBinary() {
        assertTrue(parser.detect("<save><gold>5</gold></save>".toByteArray(), "save.xml").score < 0.3f)
        val binary = byteArrayOf(0x00, 0x01, 0x02, 0xff.toByte(), 0x7f, 0x10, 0x00, 0x00)
        assertEquals(0.0, parser.detect(binary, "save.dat").score.toDouble(), 0.001)
    }

    @Test
    fun registryPicksJsonForJsonBytes() {
        val best = registry.best(compact.toByteArray(), "save.json")
        assertNotNull(best)
        assertEquals("JSON", best!!.format.badge)
    }

    // ---- round trip --------------------------------------------------------------

    @Test
    fun compactFileRoundTripsByteForByte() {
        val result = parse(compact)
        val rebuilt = result.document.toBytes()
        assertEquals(compact, String(rebuilt))
    }

    @Test
    fun prettyFileRoundTripsByteForByte() {
        val result = parse(pretty)
        assertEquals(pretty, String(result.document.toBytes()))
    }

    @Test
    fun parseWriteParseKeepsEveryValue() {
        val first = parse(pretty).tree
        val rebuilt = parse(pretty).document.toBytes()
        val second = parse(String(rebuilt)).tree
        assertEquals(first.totalCount, second.totalCount)
        assertEquals(first.editableCount, second.editableCount)
        for (node in first.editableNodes) {
            val again = second.node(node.path)
            assertNotNull("missing ${node.path} after round trip", again)
            assertTrue("$node.path changed", node.sameValueAs(again))
        }
    }

    @Test
    fun oddFormattingSurvivesStructurally() {
        val odd = """{ "a" : 1 , "b":[ 2,3 ] ,
            "c" : { "d" : null } }"""
        val result = parse(odd)
        assertEquals(1L, (result.tree.node("a")!!.value as FieldValue.Int64).value)
        assertEquals(FieldValue.Null, result.tree.node("c.d")!!.value)
        val rebuilt = String(result.document.toBytes())
        val again = parse(rebuilt).tree
        assertEquals(result.tree.editableCount, again.editableCount)
        assertEquals(3L, (again.node("b[1]")!!.value as FieldValue.Int64).value)
    }

    @Test
    fun numberLiteralsAreKeptExactly() {
        val text = """{"a":1.50,"b":1e3,"c":-0,"d":18446744073709551615}"""
        val result = parse(text)
        assertEquals(text, String(result.document.toBytes()))
        assertEquals(1.5, (result.tree.node("a")!!.value as FieldValue.Real).value, 0.0001)
        assertEquals(1000.0, (result.tree.node("b")!!.value as FieldValue.Real).value, 0.0001)
        assertEquals(FieldType.UINT64, result.tree.node("d")!!.type)
        assertEquals("18446744073709551615", result.tree.node("d")!!.value!!.displayText())
    }

    @Test
    fun unparseableNumberLiteralsAreKeptAndLeftAlone() {
        val text = """{"a":-,"b":1}"""
        val result = parse(text)
        assertTrue(result.tree.warnings.any { it.contains("could not be parsed") })
        assertFalse(result.tree.node("a")!!.editable)
        // The literal is written back untouched rather than being dropped.
        assertEquals(text, String(result.document.toBytes()))
    }

    @Test
    fun unicodeEscapesAndStringsSurvive() {
        val text = """{"name":"Aric \u00e9 \"the\" \ud83d\ude00","path":"c:\\games"}"""
        val result = parse(text)
        assertEquals(text, String(result.document.toBytes()))
        assertEquals("Aric é \"the\" \uD83D\uDE00", (result.tree.node("name")!!.value as FieldValue.Text).value)
    }

    @Test
    fun bomIsPreserved() {
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        val result = parser.parse(ParseRequest(bom + """{"a":1}""".toByteArray(), "save.json"))
        assertEquals(1L, (result.tree.node("a")!!.value as FieldValue.Int64).value)
        assertTrue(result.document.toBytes().contentEquals(bom + """{"a":1}""".toByteArray()))
    }

    // ---- editing -----------------------------------------------------------------

    @Test
    fun editChangesOnlyTheEditedValue() {
        val result = parse(compact)
        val node = result.tree.node("player.gold")
        assertNotNull(node)
        val edit = FieldEdit.of(node!!, FieldValue.Int64(999999))
        val applied = result.document.applyEdits(listOf(edit))
        assertEquals(1, applied.applied.size)
        assertTrue(applied.rejected.isEmpty())

        val rebuilt = String(result.document.toBytes())
        assertTrue(rebuilt.contains("\"gold\":999999"))
        assertFalse(rebuilt.contains("5000"))
        // Everything else is untouched.
        assertTrue(rebuilt.contains("\"name\":\"Aric\""))
        assertTrue(rebuilt.contains("\"hp\":42.5"))
        assertEquals(999999L, (result.document.tree.node("player.gold")!!.value as FieldValue.Int64).value)
    }

    @Test
    fun editInsideAnArrayUsesIndexedPaths() {
        val result = parse(pretty)
        val node = result.tree.node("player.items[1].count")
        assertNotNull(node)
        result.document.applyEdits(listOf(FieldEdit.of(node!!, FieldValue.Int64(40))))
        assertEquals(40L, (parse(String(result.document.toBytes())).tree.node("player.items[1].count")!!.value as FieldValue.Int64).value)
        assertEquals(2L, (parse(String(result.document.toBytes())).tree.node("player.items[0].count")!!.value as FieldValue.Int64).value)
    }

    @Test
    fun wideningAnIntegerIsAllowedForTextFormats() {
        val result = parse(compact)
        val node = result.tree.node("player.gold")!!
        assertEquals(FieldType.INT32, node.type)
        val outcome = EditValidator.validate(node, FieldValue.Int64(5_000_000_000L))
        assertTrue(outcome.reason ?: "", outcome.ok)
        result.document.applyEdits(listOf(FieldEdit.of(node, FieldValue.Int64(5_000_000_000L))))
        assertEquals(FieldType.INT64, result.document.tree.node("player.gold")!!.type)
        assertTrue(String(result.document.toBytes()).contains("\"gold\":5000000000"))
    }

    @Test
    fun invalidEditsAreRejectedAndNeverWritten() {
        val result = parse(compact)
        val gold = result.tree.node("player.gold")!!
        val name = result.tree.node("player.name")!!
        val dead = result.tree.node("player.dead")!!

        val notANumber = EditValidator.validate(gold, FieldValue.Text("lots"))
        assertFalse(notANumber.ok)
        val wrongType = EditValidator.validate(dead, FieldValue.Int64(5))
        assertFalse(wrongType.ok)

        val applied = result.document.applyEdits(
            listOf(
                FieldEdit.of(gold, FieldValue.Text("lots")),
                FieldEdit.of(dead, FieldValue.Int64(5)),
                FieldEdit.of(name, FieldValue.Text("Brynn")),
            ),
        )
        assertEquals(1, applied.applied.size)
        assertEquals(2, applied.rejected.size)
        assertTrue(String(result.document.toBytes()).contains("\"gold\":5000"))
        assertTrue(String(result.document.toBytes()).contains("\"name\":\"Brynn\""))
    }

    @Test
    fun quickMaxUsesTheInt32CeilingForJsonIntegers() {
        val result = parse(compact)
        val gold = result.tree.node("player.gold")!!
        val max = EditValidator.quickValue(gold, EditValidator.QuickValue.MAX)
        assertEquals(2147483647L, (max as FieldValue.Int64).value)
        val doubled = EditValidator.quickValue(gold, EditValidator.QuickValue.DOUBLE)
        assertEquals(10000L, (doubled as FieldValue.Int64).value)
    }

    @Test
    fun duplicateKeysStayAddressable() {
        val result = parse("""{"a":1,"a":2,"b":{"a":3}}""")
        assertEquals(1L, (result.tree.node("a")!!.value as FieldValue.Int64).value)
        assertEquals(2L, (result.tree.node("a#2")!!.value as FieldValue.Int64).value)
        assertEquals(3L, (result.tree.node("b.a")!!.value as FieldValue.Int64).value)
        result.document.applyEdits(listOf(FieldEdit.of(result.tree.node("a#2")!!, FieldValue.Int64(9))))
        assertEquals("""{"a":1,"a":9,"b":{"a":3}}""", String(result.document.toBytes()))
    }

    // ---- categorisation and search ------------------------------------------------

    @Test
    fun valuesAreGroupedByCategory() {
        val text = """{"gold":10,"maxHp":20,"items":[{"count":2}],"tutorialDone":true,"v_0042":7}"""
        val tree = parse(text).tree
        val groups = tree.groups().associate { it.category to it.nodes.map { node -> node.path } }
        assertTrue(groups.getValue(FieldCategory.CURRENCY).contains("gold"))
        assertTrue(groups.getValue(FieldCategory.STATS).contains("maxHp"))
        assertTrue(groups.getValue(FieldCategory.INVENTORY).contains("items[0].count"))
        assertTrue(groups.getValue(FieldCategory.FLAGS).contains("tutorialDone"))
        assertTrue(groups.getValue(FieldCategory.OTHER).contains("v_0042"))
    }

    @Test
    fun searchMatchesNameValueAndCategory() {
        val tree = parse(compact).tree
        assertTrue(tree.search("gold").any { it.path == "player.gold" })
        assertTrue(tree.search("PLAYER GOLD").any { it.path == "player.gold" })
        assertTrue(tree.search("5000").any { it.path == "player.gold" })
        assertTrue(tree.search("category:currency").isNotEmpty())
        assertTrue(tree.search("type:bool").any { it.path == "player.dead" })
        assertTrue(tree.search("").isEmpty())
    }

    // ---- failure modes ------------------------------------------------------------

    @Test
    fun corruptJsonThrowsAClearError() {
        val error = runCatching { parse("""{"a":1,""") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is CorruptFileException)
        assertTrue((error as CorruptFileException).message!!.isNotEmpty())
    }

    @Test
    fun commentsAreToleratedButReported() {
        val text = """{
          // progress
          "gold": 5,
          /* block */
          "hp": 1
        }"""
        val result = parse(text)
        assertEquals(5L, (result.tree.node("gold")!!.value as FieldValue.Int64).value)
        assertTrue(result.tree.warnings.any { it.contains("comments") })
    }

    @Test
    fun gzipPayloadOpensReadOnly() {
        val gzipped = GzipCodec.encode(compact.toByteArray())
        val result = parser.parse(ParseRequest(gzipped, "save.json.gz"))
        assertTrue(result.tree.readOnly)
        assertTrue(result.tree.readOnlyReason!!.contains("gzip"))
        // Fields are still visible so the chat can report them.
        assertEquals(5000L, (result.tree.node("player.gold")!!.value as FieldValue.Int64).value)
        val rejected = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("player.gold")!!, FieldValue.Int64(1))),
        )
        assertEquals(1, rejected.rejected.size)
        assertEquals(0, rejected.applied.size)
    }

    @Test
    fun oversizedFileIsRefused() {
        val huge = ByteArray((51 * 1024 * 1024))
        val error = runCatching { parser.parse(ParseRequest(huge, "big.json")) }.exceptionOrNull()
        assertTrue(error is SavantParserException)
    }

    @Test
    fun nodeBudgetTruncatesTheTreeButNotTheFile() {
        val fields = (1..60).joinToString(",") { "\"f$it\":$it" }
        val text = "{$fields}"
        val result = parser.parse(ParseRequest(text.toByteArray(), "save.json", dev.savant.domain.parser.ParseHints(maxNodes = 10)))
        assertTrue(result.tree.truncated)
        assertTrue(result.tree.editableCount <= 10)
        assertTrue(result.tree.warnings.any { it.contains("first 10 values") })
        // Nothing is lost: the untouched document writes back identically.
        assertEquals(text, String(result.document.toBytes()))
    }

    // ---- export verification ------------------------------------------------------

    @Test
    fun rebuiltFileIsReParsedAndVerified() {
        val result = parse(compact)
        val edits = listOf(
            FieldEdit.of(result.tree.node("player.gold")!!, FieldValue.Int64(999999)),
            FieldEdit.of(result.tree.node("player.hp")!!, FieldValue.Real(100.0)),
        )
        val applied = result.document.applyEdits(edits)
        assertEquals(2, applied.applied.size)

        val rebuilt = result.document.toBytes()
        val report = ExportVerifier(registry).verify(rebuilt, "save.json", applied.applied, parser)
        assertTrue(report.summary, report.ok)
        assertEquals(2, report.checkedEdits)
        assertTrue(report.mismatches.isEmpty())
    }

    @Test
    fun verificationFailsWhenTheRebuildLosesAnEdit() {
        val result = parse(compact)
        val applied = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("player.gold")!!, FieldValue.Int64(7))),
        )
        // Verify against bytes that were never rebuilt.
        val report = ExportVerifier(registry).verify(compact.toByteArray(), "save.json", applied.applied, parser)
        assertFalse(report.ok)
        assertEquals(1, report.mismatches.size)
        assertEquals("player.gold", report.mismatches[0].path)
    }

    // ---- non ascii text ----------------------------------------------------------

    @Test
    fun utf8ValuesReadAsTextNotAsBytes() {
        val source = "{\"player\":{\"name\":\"José Ñandú — 日本語\",\"gold\":10}}"
        val result = parse(source)

        // One char per byte would read this as mojibake and re-encode it wrong on the next edit.
        assertEquals(FieldValue.Text("José Ñandú — 日本語"), result.tree.node("player.name")?.value)
        assertEquals("José Ñandú — 日本語", result.tree.node("player.name")!!.value!!.asText())
    }

    @Test
    fun editingAnotherFieldLeavesUtf8BytesAlone() {
        val source = "{\"player\":{\"name\":\"José\",\"gold\":10}}"
        val result = parse(source)

        val applied = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("player.gold")!!, FieldValue.Int64(20))),
        )
        assertEquals(1, applied.applied.size)

        val rebuilt = String(result.document.toBytes(), Charsets.UTF_8)
        assertEquals("{\"player\":{\"name\":\"José\",\"gold\":20}}", rebuilt)
    }

    @Test
    fun writingAUtf8FieldEncodesUtf8() {
        val source = "{\"name\":\"José\",\"gold\":1}"
        val result = parse(source)

        result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("name")!!, FieldValue.Text("Renée · 日本語"))),
        )

        val rebuilt = String(result.document.toBytes(), Charsets.UTF_8)
        assertEquals("{\"name\":\"Renée · 日本語\",\"gold\":1}", rebuilt)
    }

    @Test
    fun aTruncatedUtf8SequenceStillReads() {
        // 0xC3 with no continuation byte: not text anybody meant, but the save must still open.
        val bytes = "{\"a\":\"".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0xC3.toByte()) +
            "\"}".toByteArray(Charsets.UTF_8)
        val result = parser.parse(ParseRequest(bytes, "save.json"))
        assertEquals(1, result.tree.totalCount)
    }
}
