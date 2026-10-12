package dev.savant.parsers.xml

import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.displayText
import dev.savant.domain.parser.CorruptFileException
import dev.savant.domain.parser.ExportVerifier
import dev.savant.domain.parser.ParseRequest
import dev.savant.domain.parser.ParserRegistry
import dev.savant.parsers.json.JsonSaveParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round trip contract for the xml parser, including apple plist containers.
 */
class XmlSaveParserTest {

    private val parser = XmlSaveParser()
    private val registry = ParserRegistry(listOf(parser, JsonSaveParser()))

    private val save = """<?xml version="1.0" encoding="UTF-8"?>
<save version="3">
  <player name="Aric" hp="42">
    <gold>5000</gold>
    <items>
      <item id="7">2</item>
      <item id="9">1</item>
    </items>
  </player>
  <!-- progress -->
  <flags tutorial="true"><![CDATA[done]]></flags>
</save>
"""

    private val plist = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>gold</key>
	<integer>5000</integer>
	<key>player_name</key>
	<string>123</string>
	<key>is_dead</key>
	<true/>
	<key>ratio</key>
	<real>12.5</real>
</dict>
</plist>
"""

    private fun parse(text: String, name: String? = "save.xml") = parser.parse(ParseRequest(text.toByteArray(), name))

    // ---- detection ---------------------------------------------------------------

    @Test
    fun detectsXml() {
        assertTrue(parser.detect(save.toByteArray(), "save.xml").score >= 0.9f)
        assertTrue(parser.detect(plist.toByteArray(), "prefs.plist").score >= 0.9f)
    }

    @Test
    fun doesNotClaimJson() {
        assertTrue(parser.detect("""{"a":1}""".toByteArray(), "save.json").score < 0.3f)
        assertEquals("XML", registry.best(save.toByteArray(), "save.xml")!!.format.badge)
    }

    // ---- round trip --------------------------------------------------------------

    @Test
    fun xmlRoundTripsByteForByte() {
        assertEquals(save, String(parse(save).document.toBytes()))
    }

    @Test
    fun plistRoundTripsByteForByte() {
        assertEquals(plist, String(parse(plist, "prefs.plist").document.toBytes()))
    }

    @Test
    fun parseWriteParseKeepsEveryValue() {
        val first = parse(save).tree
        val rebuilt = parse(save).document.toBytes()
        val second = parse(String(rebuilt)).tree
        assertEquals(first.editableCount, second.editableCount)
        for (node in first.editableNodes) {
            val again = second.node(node.path)
            assertNotNull("missing ${node.path}", again)
            assertTrue(node.path, node.sameValueAs(again))
        }
    }

    // ---- structure ---------------------------------------------------------------

    @Test
    fun attributesTextAndRepeatedSiblingsAreAddressable() {
        val tree = parse(save).tree
        assertEquals("3", tree.node("save.@version")!!.value!!.displayText())
        assertEquals(42L, (tree.node("save.player.@hp")!!.value as FieldValue.Int64).value)
        assertEquals(5000L, (tree.node("save.player.gold")!!.value as FieldValue.Int64).value)
        assertEquals(2L, (tree.node("save.player.items.item[0].#text")!!.value as FieldValue.Int64).value)
        assertEquals(9L, (tree.node("save.player.items.item[1].@id")!!.value as FieldValue.Int64).value)
        assertEquals(FieldType.BOOL, tree.node("save.flags.@tutorial")!!.type)
        assertEquals("done", (tree.node("save.flags.#text")!!.value as FieldValue.Text).value)
    }

    @Test
    fun plistDictKeysBecomeTypedFields() {
        val tree = parse(plist, "prefs.plist").tree
        assertEquals(FieldType.INT64, tree.node("plist.dict.gold")!!.type)
        assertEquals(5000L, (tree.node("plist.dict.gold")!!.value as FieldValue.Int64).value)
        // A string that looks like a number stays a string.
        assertEquals(FieldType.STRING, tree.node("plist.dict.player_name")!!.type)
        assertEquals("123", (tree.node("plist.dict.player_name")!!.value as FieldValue.Text).value)
        assertEquals(FieldValue.Bool(true), tree.node("plist.dict.is_dead")!!.value)
        assertEquals(12.5, (tree.node("plist.dict.ratio")!!.value as FieldValue.Real).value, 0.0001)
        assertTrue(tree.warnings.isEmpty() || tree.warnings.none { it.contains("ambiguous") })
    }

    // ---- editing -----------------------------------------------------------------

    @Test
    fun editingTextOnlyChangesThatValue() {
        val result = parse(save)
        val applied = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("save.player.gold")!!, FieldValue.Int64(999999))),
        )
        assertEquals(1, applied.applied.size)
        val rebuilt = String(result.document.toBytes())
        assertTrue(rebuilt.contains("<gold>999999</gold>"))
        assertTrue(rebuilt.contains("""<save version="3">"""))
        assertTrue(rebuilt.contains("<!-- progress -->"))
        assertTrue(rebuilt.contains("<![CDATA[done]]>"))
    }

    @Test
    fun editingAnAttributeKeepsTheQuoteStyle() {
        val result = parse(save)
        result.document.applyEdits(listOf(FieldEdit.of(result.tree.node("save.player.@hp")!!, FieldValue.Int64(7))))
        val rebuilt = String(result.document.toBytes())
        assertTrue(rebuilt.contains("""<player name="Aric" hp="7">"""))
    }

    @Test
    fun editingASelfClosingElementOpensIt() {
        val result = parse("""<save><gold/></save>""")
        val node = result.tree.node("save.gold")
        assertNotNull(node)
        result.document.applyEdits(listOf(FieldEdit.of(node!!, FieldValue.Int64(12))))
        assertEquals("<save><gold>12</gold></save>", String(result.document.toBytes()))
    }

    @Test
    fun plistBoolFlipsTheTag() {
        val result = parse(plist, "prefs.plist")
        result.document.applyEdits(
            listOf(
                FieldEdit.of(result.tree.node("plist.dict.is_dead")!!, FieldValue.Bool(false)),
                FieldEdit.of(result.tree.node("plist.dict.gold")!!, FieldValue.Int64(1)),
            ),
        )
        val rebuilt = String(result.document.toBytes())
        assertTrue(rebuilt, rebuilt.contains("<false/>"))
        assertFalse(rebuilt.contains("<true/>"))
        assertTrue(rebuilt.contains("<integer>1</integer>"))
        // Layout around the values is untouched.
        assertTrue(rebuilt.contains("\t<key>gold</key>\n\t<integer>1</integer>"))
    }

    @Test
    fun entitiesAreDecodedForReadingAndEscapedForWriting() {
        val result = parse("""<save><name>A &amp; B</name><note>1 &lt; 2</note></save>""")
        assertEquals("A & B", (result.tree.node("save.name")!!.value as FieldValue.Text).value)
        result.document.applyEdits(listOf(FieldEdit.of(result.tree.node("save.name")!!, FieldValue.Text("C < D"))))
        assertEquals("<save><name>C &lt; D</name><note>1 &lt; 2</note></save>", String(result.document.toBytes()))
    }

    @Test
    fun unknownEntitiesSurviveVerbatim() {
        val text = """<save><note>a&nbsp;b</note></save>"""
        val result = parse(text)
        assertEquals(text, String(result.document.toBytes()))
    }

    // ---- failure modes ------------------------------------------------------------

    @Test
    fun mismatchedTagsAreReported() {
        val error = runCatching { parse("<save><gold>5</player></save>") }.exceptionOrNull()
        assertTrue(error is CorruptFileException)
        assertTrue((error as CorruptFileException).message!!.contains("mismatched"))
    }

    @Test
    fun unclosedElementIsReported() {
        val error = runCatching { parse("<save><gold>5</gold>") }.exceptionOrNull()
        assertTrue(error is CorruptFileException)
        assertTrue((error as CorruptFileException).message!!.contains("unclosed"))
    }

    @Test
    fun externalEntitiesAreNeverResolved() {
        // The doctype is kept as raw text; nothing is fetched and the entity stays literal.
        val text = """<?xml version="1.0"?><!DOCTYPE save [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><save><a>&xxe;</a></save>"""
        val result = parse(text)
        assertEquals(text, String(result.document.toBytes()))
        assertTrue(result.tree.warnings.none { it.contains("fetched") })
    }

    // ---- verification -------------------------------------------------------------

    @Test
    fun rebuiltXmlIsVerified() {
        val result = parse(save)
        val applied = result.document.applyEdits(
            listOf(
                FieldEdit.of(result.tree.node("save.player.gold")!!, FieldValue.Int64(4)),
                FieldEdit.of(result.tree.node("save.player.items.item[0].#text")!!, FieldValue.Int64(9)),
            ),
        )
        assertEquals(2, applied.applied.size)
        val report = ExportVerifier(registry).verify(result.document.toBytes(), "save.xml", applied.applied, parser)
        assertTrue(report.summary, report.ok)
    }
}
