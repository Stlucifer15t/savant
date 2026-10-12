package dev.savant.parsers.json

import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.parser.CorruptSaveException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class JsonSaveParserTest {
    private val parser = JsonSaveParser()

    @Test
    fun `parse write parse preserves every typed leaf`() {
        val source = """
            {
              "player": {"name": "Mara", "hp": 87, "alive": true, "ratio": 1.25},
              "gold": 4200,
              "inventory": ["potion", null, 3],
              "odd key": {"line\\nbreak": "kept"}
            }
        """.trimIndent().encodeToByteArray()

        val first = parser.parse(source)
        val second = parser.parse(parser.write(first))

        assertEquals(snapshot(first), snapshot(second))
        assertEquals(FieldCategory.CURRENCY, first.find("$.gold")?.category)
        assertEquals(FieldCategory.STATS, first.find("$.player.hp")?.category)
        assertEquals(FieldCategory.INVENTORY, first.find("$.inventory[2]")?.category)
    }

    @Test
    fun `edited value is encoded as its original type`() {
        val tree = parser.parse("""{"gold":12,"enabled":false}""".encodeToByteArray())
            .withValues(
                mapOf(
                    "$.gold" to FieldValue.IntegerValue(999_999),
                    "$.enabled" to FieldValue.BooleanValue(true),
                ),
            )

        val reparsed = parser.parse(parser.write(tree))

        assertEquals(FieldValue.IntegerValue(999_999), reparsed.find("$.gold")?.value)
        assertEquals(FieldValue.BooleanValue(true), reparsed.find("$.enabled")?.value)
    }

    @Test
    fun `detect recognizes object and rejects arbitrary bytes`() {
        assertTrue(parser.detect("  {\"x\":1}".encodeToByteArray()).score > 0.9f)
        assertEquals(0f, parser.detect(byteArrayOf(0x00, 0x01, 0x02)).score)
    }

    @Test
    fun `malformed input reports a corrupt save`() {
        assertFailsWith<CorruptSaveException> {
            parser.parse("{not-json".encodeToByteArray())
        }
    }

    private fun snapshot(tree: dev.savant.domain.model.FieldTree) = tree.allNodes().map {
        listOf(it.path, it.type, it.value, it.editable, it.category)
    }
}
