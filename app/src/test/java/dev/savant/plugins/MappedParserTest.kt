package dev.savant.plugins

import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldValue
import dev.savant.domain.parser.ParseRequest
import dev.savant.parsers.json.JsonSaveParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A json or xml save with a plugin's knowledge laid over it.
 *
 * The base parser still owns the bytes, so the contract that matters is unchanged: an untouched file
 * is written back identically, and a mapped field keeps its plugin name through an edit instead of
 * reverting to the cryptic key halfway through a session.
 */
class MappedParserTest {

    private val source = """{"player":{"gld":5000,"hp_2":40},"flags":{"tutorial":true}}"""

    private val pluginJson = """
        {
          "gameName": "example quest",
          "fileMatch": ["example*.json"],
          "parser": "json",
          "fields": [
            { "path": "player.gld", "type": "i32", "name": "Gold", "category": "currency", "max": 999999 },
            { "path": "player.hp_2", "type": "i32", "name": "HP", "category": "stats", "min": 0, "max": 999 }
          ]
        }
    """.trimIndent()

    private fun plugin(json: String = pluginJson): GamePlugin = (PluginCodec.decode(json) as PluginDecode.Ok).plugin

    private fun parse(text: String = source, name: String? = "example_save.json", json: String = pluginJson) =
        MappedParser(plugin(json), JsonSaveParser()).parse(ParseRequest(text.toByteArray(), name))

    @Test
    fun mappingsRenameCategoriseAndBoundWithoutTouchingTheBytes() {
        val result = parse()

        val gold = result.tree.node("player.gld")!!
        assertEquals("Gold", gold.label)
        assertEquals(FieldCategory.CURRENCY, gold.category)
        assertEquals(999999.0, gold.max ?: 0.0, 0.0)
        assertEquals(5000L, (gold.value as FieldValue.Int64).value)
        assertEquals("plugin example quest", gold.aiGuess?.explanation)
        assertEquals("gld", gold.sourceKey)

        val hp = result.tree.node("player.hp_2")!!
        assertEquals("HP", hp.label)
        assertEquals(FieldCategory.STATS, hp.category)
        assertEquals(0.0, hp.min ?: -1.0, 0.0)
        assertEquals(999.0, hp.max ?: 0.0, 0.0)

        // A field the plugin does not mention is exactly what the base parser made of it.
        val tutorial = result.tree.node("flags.tutorial")!!
        assertEquals(FieldCategory.FLAGS, tutorial.category)
        assertNull(tutorial.aiGuess)

        assertTrue(result.log.any { it.text.contains("plugin example quest: 2 field(s) named") })
    }

    @Test
    fun anUntouchedFileIsStillByteIdentical() {
        val result = parse()
        assertEquals(source, String(result.document.toBytes()))
    }

    @Test
    fun anEditIsWrittenAsJsonAndKeepsThePluginNames() {
        val result = parse()

        val applied = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("player.gld")!!, FieldValue.Int64(999999))),
        )
        assertEquals(1, applied.applied.size)

        assertEquals("""{"player":{"gld":999999,"hp_2":40},"flags":{"tutorial":true}}""", String(result.document.toBytes()))

        // The tree the edit handed back is the mapped one, not the base parser's.
        assertEquals("Gold", applied.tree.node("player.gld")!!.label)
        assertEquals(FieldCategory.CURRENCY, applied.tree.node("player.gld")!!.category)
        assertEquals("Gold", result.document.tree.node("player.gld")!!.label)
    }

    @Test
    fun thePluginMaximumIsEnforcedOnAMappedField() {
        val result = parse()
        val applied = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("player.gld")!!, FieldValue.Int64(1_000_000))),
        )

        assertEquals(0, applied.applied.size)
        assertTrue(applied.rejected.single().reason.contains("above maximum"))
        assertEquals(source, String(result.document.toBytes()))
    }

    @Test
    fun detectionOnlyPrefersThePluginForFilesItClaims() {
        val parser = MappedParser(plugin(), JsonSaveParser())
        val bytes = source.toByteArray()

        val claimed = parser.detect(bytes, "example_save.json")
        val other = parser.detect(bytes, "unrelated.json")

        assertTrue("the plugin's own file should outscore a plain json read: ${claimed.score}", claimed.score > 0.9f)
        assertTrue(claimed.reason.contains("example quest"))
        assertFalse(other.reason.contains("example quest"))
        assertTrue("an unclaimed json file still parses as json", other.usable)
    }

    @Test
    fun wildcardPathsMatchArrayElements() {
        val text = """{"party":[{"hp":10},{"hp":20}]}"""
        val json = """
            {"gameName":"party","parser":"json","fileMatch":["*.json"],"fields":[
              {"path":"party[*].hp","type":"i32","name":"Hit points","category":"stats","max":999}
            ]}
        """.trimIndent()

        val result = parse(text = text, json = json)

        assertEquals("Hit points", result.tree.node("party[0].hp")!!.label)
        assertEquals("Hit points", result.tree.node("party[1].hp")!!.label)
        assertEquals(999.0, result.tree.node("party[1].hp")!!.max ?: 0.0, 0.0)
        assertTrue(result.log.any { it.text.contains("2 field(s) named") })
    }

    @Test
    fun aPluginThatMatchesNothingSaysSoInsteadOfFailing() {
        val json = """
            {"gameName":"other game","parser":"json","fileMatch":["*.json"],"fields":[
              {"path":"save.slots[0].credits","type":"i32","name":"Credits"}
            ]}
        """.trimIndent()

        val result = parse(json = json)

        // The file still parses; the plugin simply had nothing to add.
        assertEquals(5000L, (result.tree.node("player.gld")!!.value as FieldValue.Int64).value)
        assertTrue(result.log.any { it.text.contains("none of its paths matched") })
    }

    @Test
    fun aTypeDisagreementIsANotNotARewrite() {
        val json = """
            {"gameName":"wrong notes","parser":"json","fileMatch":["*.json"],"fields":[
              {"path":"player.gld","type":"f32","name":"Gold"}
            ]}
        """.trimIndent()

        val result = parse(json = json)
        val gold = result.tree.node("player.gld")!!

        // The file says integer. The plugin's note is kept, at half confidence, and the type stays.
        assertEquals("Gold", gold.label)
        assertEquals(0.5f, gold.aiGuess?.confidence ?: 0f, 0.001f)
        assertTrue(gold.aiGuess!!.explanation!!.contains("type differs"))
        assertTrue(gold.value is FieldValue.Int64)
    }

    @Test
    fun aMappingCanMakeAFieldReadOnly() {
        val json = """
            {"gameName":"careful","parser":"json","fileMatch":["*.json"],"fields":[
              {"path":"player.gld","type":"i32","name":"Gold","editable":false}
            ]}
        """.trimIndent()

        val result = parse(json = json)
        assertFalse(result.tree.node("player.gld")!!.editable)

        val applied = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("player.gld")!!, FieldValue.Int64(1))),
        )
        assertEquals(0, applied.applied.size)
        assertTrue(applied.rejected.single().reason.contains("not editable"))
    }
}
