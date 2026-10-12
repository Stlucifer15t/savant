package dev.savant.parsers.xml

import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldValue
import dev.savant.domain.parser.CorruptSaveException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class XmlSaveParserTest {
    private val parser = XmlSaveParser()

    @Test
    fun `parse write parse preserves attributes repeated elements and values`() {
        val source = """<?xml version="1.0" encoding="UTF-8"?>
            <save version="3"><player><hp>87</hp><name><![CDATA[Mara & Co]]></name></player><item id="4">potion</item><item id="9">ether</item><flags><doorOpen>true</doorOpen></flags></save>
        """.trimIndent().encodeToByteArray()

        val first = parser.parse(source)
        val second = parser.parse(parser.write(first))

        assertEquals(snapshot(first), snapshot(second))
        assertEquals(FieldValue.IntegerValue(87), first.find("/save/player/hp/#text")?.value)
        assertEquals(FieldCategory.STATS, first.find("/save/player/hp/#text")?.category)
        assertEquals(FieldValue.StringValue("Mara & Co"), first.find("/save/player/name/#text")?.value)
        assertEquals(FieldValue.IntegerValue(4), first.find("/save/item[0]/@id")?.value)
    }

    @Test
    fun `edit survives deterministic rebuild`() {
        val original = parser.parse("<save gold=\"10\"><hp>5</hp></save>".encodeToByteArray())
        val edited = original.withValues(
            mapOf(
                "/save/@gold" to FieldValue.IntegerValue(99),
                "/save/hp/#text" to FieldValue.IntegerValue(42),
            ),
        )

        val reparsed = parser.parse(parser.write(edited))

        assertEquals(FieldValue.IntegerValue(99), reparsed.find("/save/@gold")?.value)
        assertEquals(FieldValue.IntegerValue(42), reparsed.find("/save/hp/#text")?.value)
    }

    @Test
    fun `doctype and external entities are rejected`() {
        val attack = """<?xml version="1.0"?><!DOCTYPE save [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><save>&xxe;</save>"""
        assertFailsWith<CorruptSaveException> { parser.parse(attack.encodeToByteArray()) }
    }

    @Test
    fun `detect handles declarations and elements`() {
        assertTrue(parser.detect("<?xml version=\"1.0\"?><save/>".encodeToByteArray()).score > 0.9f)
        assertTrue(parser.detect("  <save><hp>1</hp></save>".encodeToByteArray()).score > 0.8f)
    }

    private fun snapshot(tree: dev.savant.domain.model.FieldTree) = tree.allNodes().map {
        listOf(it.path, it.type, it.value, it.editable, it.category, it.metadata["xmlKind"], it.metadata["xmlName"])
    }
}
