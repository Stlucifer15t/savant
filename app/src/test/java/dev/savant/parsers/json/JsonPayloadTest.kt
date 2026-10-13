package dev.savant.parsers.json

import dev.savant.domain.model.FieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Savant's own json: history payloads, plugin definitions, exported field maps.
 *
 * The reader and writer underneath are the byte faithful ones from the save parser, so these tests
 * are also the guarantee that a payload written by one build reads back on the next.
 */
class JsonPayloadTest {

    @Test
    fun `writes a nested document compactly and reads it back`() {
        val document = obj(
            "kind" to str("diff"),
            "count" to num(3),
            "ok" to bool(true),
            "ratio" to num(1.5),
            "note" to Json.Null,
            "items" to arr(str("a"), str("b")),
            "nested" to obj("x" to num(1L)),
        )

        val text = JsonPayload.write(document)

        assertEquals(
            """{"kind":"diff","count":3,"ok":true,"ratio":1.5,"note":null,"items":["a","b"],"nested":{"x":1}}""",
            text,
        )

        val back = JsonPayload.read(text)
        assertEquals("diff", back.string("kind"))
        assertEquals(3, back.int("count") ?: -1)
        assertEquals(true, back.bool("ok") ?: false)
        assertEquals(1.5, back.double("ratio") ?: 0.0, 0.0)
        assertEquals(listOf("a", "b"), back.strings("items"))
        assertEquals(1L, back.obj("nested")?.long("x") ?: -1L)
        assertTrue(back["note"] is Json.Null)
    }

    @Test
    fun `integers stay integers`() {
        // A stored edit count must not come back as 12.0, or the transcript shows the wrong thing.
        assertEquals("""{"n":12}""", JsonPayload.write(obj("n" to num(12))))
        assertEquals("""{"n":999999}""", JsonPayload.write(obj("n" to num(999999L))))
        assertEquals("""{"n":1.25}""", JsonPayload.write(obj("n" to num(1.25))))
        assertEquals(12, JsonPayload.read("""{"n":12}""").int("n") ?: -1)
    }

    @Test
    fun `builders accept a list of one subtype`() {
        val items: List<Json.Str> = listOf(str("a"), str("b"))
        val members: List<Pair<String, Json>> = listOf("items" to arr(items))

        assertEquals("""{"items":["a","b"]}""", JsonPayload.write(obj(members)))
    }

    @Test
    fun `field values survive the payload round trip`() {
        val values = listOf(
            FieldValue.Text("hello"),
            FieldValue.Text("999999"),
            FieldValue.Int64(999999),
            FieldValue.Int64(-4),
            FieldValue.Real(2.5),
            FieldValue.Bool(true),
            FieldValue.Bool(false),
            FieldValue.Null,
        )

        values.forEach { value ->
            assertEquals("round trip of $value", value, jsonToValue(jsonOf(value)))
        }
        assertEquals(FieldValue.Null, jsonToValue(null))
    }

    @Test
    fun `a number with a declared type comes back as that type`() {
        // "999999" is text in the save, and must stay text in the payload.
        val asText = jsonToValue(jsonOf(FieldValue.Text("999999")))
        assertTrue(asText is FieldValue.Text)
        assertEquals(FieldValue.Text("999999"), asText)
    }

    @Test
    fun `strings are escaped and unescaped`() {
        val awkward = """quote " backslash \ newline
tab	end"""
        val text = JsonPayload.write(obj("v" to str(awkward)))
        assertEquals(awkward, JsonPayload.read(text).string("v"))
    }

    @Test
    fun `accessors are tolerant of the wrong shape`() {
        val payload = JsonPayload.read("""{"n":5,"s":"x","b":true,"a":[1,2],"o":{"k":1}}""")
        assertNull(payload.string("n"))
        assertNull(payload.bool("s"))
        assertEquals(listOf(1, 2), payload.list("a").mapNotNull { (it as? Json.Num)?.asLong?.toInt() })
        assertEquals(emptyList<String>(), payload.strings("o"))
        assertEquals(1, payload.obj("o")?.int("k") ?: -1)
        assertEquals("x", payload.asTextOf("s"))
    }

    @Test
    fun `readOrEmpty never throws`() {
        assertEquals(Json.Null, JsonPayload.readOrEmpty(null))
        assertEquals(Json.Null, JsonPayload.readOrEmpty("   "))
        JsonPayload.readOrEmpty("{{{")
        JsonPayload.readOrEmpty("[1,")
        assertEquals("x", JsonPayload.readOrEmpty("""{"a":"x"}""").string("a"))
    }

    @Test
    fun `duplicate keys are written as they were given`() {
        val payload = Json.Obj(listOf("k" to num(1), "k" to num(2)))
        val text = JsonPayload.write(payload)
        assertEquals("""{"k":1,"k":2}""", text)

        // Which one a lenient read keeps is the reader's business; savant never writes duplicates.
        val back = JsonPayload.read(text) as Json.Obj
        assertEquals(listOf("k", "k"), back.members.map { it.first })
    }

    @Test
    fun `with replaces a member in place and keeps order`() {
        val payload = obj("a" to num(1), "b" to num(2), "c" to num(3)).with("b", num(9))
        assertEquals("""{"a":1,"b":9,"c":3}""", JsonPayload.write(payload))
    }

    /** Local helper: `asText` on a child, which is what a tolerant reader reaches for. */
    private fun Json.asTextOf(key: String): String? = (this as? Json.Obj)?.get(key)?.asText
}
