package dev.savant.plugins

import dev.savant.codecs.ZlibCodec
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.Endianness
import dev.savant.domain.parser.ParseRequest
import dev.savant.domain.parser.SavantParserException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

/**
 * A binary save read through a plugin.
 *
 * The sample file is the shape the whole runtime is tested against: a magic header, a crc32 over the
 * body, a few fixed offsets of different widths, and a pointer to an array of records. Every
 * assertion is about the two things that matter — an untouched file comes back identical, and a
 * touched one changes only where the user asked, with the checksum repaired.
 */
class BinaryPluginParserTest {

    // 0..3 magic, 4..7 crc32 of 8..end, 8..11 gold u32, 16..19 hp f32, 20..21 lives i16,
    // 40..43 party pointer (48), party records of 8 bytes at 48, 56, 64.
    private fun payload(gold: Int = 120, hp: Float = 42.5f, lives: Short = 3, party: List<Short> = listOf(30, 20, 10)): ByteArray {
        val bytes = ByteArray(96)
        "SVS1".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        putU32Little(bytes, 8, gold.toLong())
        putFloatLittle(bytes, 16, hp)
        putI16Little(bytes, 20, lives)
        putU32Little(bytes, 40, 48L)
        party.forEachIndexed { index, value -> putI16Little(bytes, 48 + index * 8, value) }
        putU32Little(bytes, 4, crc(bytes, 8, bytes.size))
        return bytes
    }

    private val pluginJson = """
        {
          "gameName": "test quest",
          "fileMatch": ["quest*.sav"],
          "magic": "53 56 53 31",
          "parser": "binary",
          "checksum": { "strategy": "crc32", "range": [8, -1], "storedAt": [4, 4] },
          "fields": [
            { "path": "player.gold", "offset": 8, "type": "u32", "name": "Gold", "category": "currency", "max": 999999 },
            { "path": "player.hp", "offset": 16, "type": "f32", "name": "HP" },
            { "path": "player.lives", "offset": 20, "type": "i16" },
            { "path": "party[*].hp", "pointer": 40, "stride": 8, "type": "i16", "count": 3 }
          ]
        }
    """.trimIndent()

    private fun plugin(json: String = pluginJson): GamePlugin = (PluginCodec.decode(json) as PluginDecode.Ok).plugin

    private fun parse(bytes: ByteArray, name: String? = "quest1.sav", json: String = pluginJson) =
        BinaryPluginParser(plugin(json)).parse(ParseRequest(bytes, name))

    // ---- detection ------------------------------------------------------------------

    @Test
    fun detectionUsesTheNameTheHeaderAndBoth() {
        val parser = BinaryPluginParser(plugin())
        val bytes = payload()

        assertEquals(0.98f, parser.detect(bytes, "quest1.sav").score, 0.001f)
        assertEquals(0.8f, parser.detect(bytes, "something-else.dat").score, 0.001f)
        assertEquals(0.86f, parser.detect(ByteArray(96), "quest1.sav").score, 0.001f)
        assertFalse(parser.detect(ByteArray(96), "something-else.dat").usable)
        assertEquals("BINARY", parser.format.badge)
        assertNotNull(parser.pluginId)
    }

    @Test
    fun aPluginWithNoFileMatchAndNoMagicClaimsNothing() {
        val bare = plugin("""{"gameName":"bare","parser":"binary","fields":[{"path":"a","type":"i32","offset":0}]}""")
        val confidence = BinaryPluginParser(bare).detect(payload(), "quest1.sav")
        assertFalse(confidence.usable)
        assertTrue(confidence.reason.contains("no fileMatch"))
    }

    // ---- reading --------------------------------------------------------------------

    @Test
    fun fieldsAreReadAtTheirOffsetsWithTheirTypes() {
        val result = parse(payload())

        val gold = result.tree.node("player.gold")!!
        assertEquals(120L, (gold.value as FieldValue.Int64).value)
        assertEquals(FieldType.UINT32, gold.type)
        assertEquals("Gold", gold.label)
        assertEquals(FieldCategory.CURRENCY, gold.category)
        assertEquals(999999.0, gold.max ?: 0.0, 0.0)
        assertEquals(8L, gold.byteOffset)
        assertEquals(4, gold.byteLength)
        assertEquals(Endianness.LITTLE, gold.endianness)
        assertTrue("a plugin offset has a hard width", gold.fixedWidth)
        assertEquals("player.gold", gold.sourceKey)

        val hp = result.tree.node("player.hp")!!
        assertEquals(42.5, (hp.value as FieldValue.Real).value, 1e-6)
        assertTrue((hp.value as FieldValue.Real).float32)

        assertEquals(3L, (result.tree.node("player.lives")!!.value as FieldValue.Int64).value)
        assertEquals(6, result.tree.editableCount)
        assertTrue(result.log.any { it.text.contains("test quest") })
        assertTrue(result.log.any { it.text.contains("checksum crc32: matches") })
    }

    @Test
    fun aPointerArrayExpandsToIndexedPaths() {
        val result = parse(payload())

        assertEquals(30L, (result.tree.node("party[0].hp")!!.value as FieldValue.Int64).value)
        assertEquals(20L, (result.tree.node("party[1].hp")!!.value as FieldValue.Int64).value)
        assertEquals(10L, (result.tree.node("party[2].hp")!!.value as FieldValue.Int64).value)
        assertNull(result.tree.node("party[3].hp"))
        assertEquals(48L, result.tree.node("party[0].hp")!!.byteOffset)
        assertEquals(56L, result.tree.node("party[1].hp")!!.byteOffset)
    }

    @Test
    fun anArrayWithoutACountFillsWhatFitsInTheFile() {
        val json = pluginJson.replace("""{ "path": "party[*].hp", "pointer": 40, "stride": 8, "type": "i16", "count": 3 }""",
            """{ "path": "party[*].hp", "pointer": 40, "stride": 8, "type": "i16" }""")
        val result = parse(payload(), json = json)

        // 96 byte file, records start at 48 with a stride of 8: six of them fit.
        assertEquals(6, result.tree.all.count { it.path.startsWith("party[") })
        assertNotNull(result.tree.node("party[5].hp"))
        assertEquals(0L, (result.tree.node("party[5].hp")!!.value as FieldValue.Int64).value)
    }

    @Test
    fun recordOffsetReadsInsideTheRecord() {
        val json = pluginJson.replace(
            """{ "path": "party[*].hp", "pointer": 40, "stride": 8, "type": "i16", "count": 3 }""",
            """{ "path": "party[*].mp", "pointer": 40, "stride": 8, "type": "i16", "count": 3, "recordOffset": 4 }""",
        )
        val bytes = payload()
        putI16Little(bytes, 52, 77)

        val result = parse(bytes, json = json)
        assertEquals(77L, (result.tree.node("party[0].mp")!!.value as FieldValue.Int64).value)
        assertNull(result.tree.node("party[0].hp"))
    }

    // ---- writing --------------------------------------------------------------------

    @Test
    fun anUntouchedFileComesBackByteForByte() {
        val original = payload()
        val result = parse(original)

        assertArrayEquals(original, result.document.toBytes())
    }

    @Test
    fun anEditChangesThoseBytesAndRepairsTheChecksum() {
        val original = payload()
        val result = parse(original)

        val applied = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("player.gold")!!, FieldValue.Int64(999_999))),
        )
        assertEquals(1, applied.applied.size)
        assertTrue(applied.rejected.isEmpty())

        val written = result.document.toBytes()
        assertEquals(96, written.size)
        // The gold bytes changed, and so did the checksum that covers them.
        assertEquals(999_999L, readU32(written, 8))
        assertFalse("the crc32 has to move with the payload", crc(original, 4, 8) == crc(written, 4, 8))
        // Everything else is untouched.
        assertArrayEquals(original.copyOfRange(0, 4), written.copyOfRange(0, 4))
        assertArrayEquals(original.copyOfRange(12, 40), written.copyOfRange(12, 40))
        assertArrayEquals(original.copyOfRange(48, 96), written.copyOfRange(48, 96))

        // And the rebuilt file parses again, with a valid checksum.
        val again = parse(written)
        assertEquals(999_999L, (again.tree.node("player.gold")!!.value as FieldValue.Int64).value)
        assertTrue(again.log.any { it.text.contains("checksum crc32: matches") })
        assertTrue(again.tree.warnings.isEmpty())
    }

    @Test
    fun oneArrayElementCanBeEditedWithoutTouchingTheOthers() {
        val original = payload()
        val result = parse(original)

        result.document.applyEdits(listOf(FieldEdit.of(result.tree.node("party[1].hp")!!, FieldValue.Int64(99))))
        val written = result.document.toBytes()

        assertEquals(30L, readI16(written, 48))
        assertEquals(99L, readI16(written, 56))
        assertEquals(10L, readI16(written, 64))
    }

    @Test
    fun thePluginMaximumIsAHardLimit() {
        val original = payload()
        val result = parse(original)

        val applied = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("player.gold")!!, FieldValue.Int64(1_000_000))),
        )

        assertEquals(0, applied.applied.size)
        assertEquals(1, applied.rejected.size)
        assertTrue(applied.rejected.single().reason.contains("above maximum"))
        assertArrayEquals(original, result.document.toBytes())
    }

    @Test
    fun aTypeWidthIsAHardLimitToo() {
        val result = parse(payload())
        val applied = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("player.lives")!!, FieldValue.Int64(70_000))),
        )
        assertEquals(0, applied.applied.size)
        assertTrue(applied.rejected.single().reason.contains("out of range"))
    }

    @Test
    fun anUnknownPathIsRejectedRatherThanGuessedAt() {
        val result = parse(payload())
        val applied = result.document.applyEdits(
            listOf(
                FieldEdit(
                    path = "player.silver",
                    displayName = "silver",
                    type = FieldType.INT32,
                    oldValue = FieldValue.Int64(0),
                    newValue = FieldValue.Int64(5),
                ),
            ),
        )
        assertEquals(0, applied.applied.size)
        assertTrue(applied.rejected.single().reason.contains("no such field"))
    }

    // ---- codecs and failures ---------------------------------------------------------

    @Test
    fun aZlibWrappedSaveRoundTrips() {
        val json = pluginJson.replaceFirst("\"parser\": \"binary\"", "\"parser\": \"binary\",\n  \"codec\": \"zlib\"")
        val wrapped = ZlibCodec.encode(payload())
        val result = parse(wrapped, json = json)

        assertEquals(120L, (result.tree.node("player.gold")!!.value as FieldValue.Int64).value)
        assertTrue(result.log.any { it.text.contains("decoded 96 bytes with zlib") })

        result.document.applyEdits(listOf(FieldEdit.of(result.tree.node("player.gold")!!, FieldValue.Int64(7))))
        val written = result.document.toBytes()
        assertEquals(0x78, written[0].toInt() and 0xFF)

        val again = parse(written, json = json)
        assertEquals(7L, (again.tree.node("player.gold")!!.value as FieldValue.Int64).value)
    }

    @Test
    fun aPayloadTheCodecCannotReadFailsLoudly() {
        val json = pluginJson.replaceFirst("\"parser\": \"binary\"", "\"parser\": \"binary\",\n  \"codec\": \"zlib\"")
        val garbage = ByteArray(96) { (it * 7).toByte() }

        val error = runCatching { parse(garbage, json = json) }.exceptionOrNull()
        assertTrue("expected a parser exception, got $error", error is SavantParserException)
        assertTrue(error!!.message!!.contains("zlib"))
    }

    @Test
    fun aFieldThatDoesNotFitIsSkippedWithAWarning() {
        val json = pluginJson.replace(
            """{ "path": "player.lives", "offset": 20, "type": "i16" }""",
            """{ "path": "player.lives", "offset": 5000, "type": "i16" }""",
        )
        val result = parse(payload(), json = json)

        assertNull(result.tree.node("player.lives"))
        assertTrue(result.tree.warnings.any { it.contains("does not fit") })
        // The rest of the plugin still works.
        assertEquals(120L, (result.tree.node("player.gold")!!.value as FieldValue.Int64).value)
    }

    @Test
    fun aPluginThatFindsNoneOfItsFieldsIsNotAParse() {
        val json = """
            {"gameName":"wrong game","fileMatch":["quest*.sav"],"parser":"binary",
             "fields":[{"path":"a","type":"i32","offset":9000},{"path":"b","type":"i32","offset":9100}]}
        """.trimIndent()
        val error = runCatching { parse(payload(), json = json) }.exceptionOrNull()
        assertTrue(error is SavantParserException)
        assertTrue(error!!.message!!.contains("found none of its fields"))
    }

    @Test
    fun aChecksumSavantCannotComputeOpensTheFileReadOnly() {
        val json = pluginJson.replace("\"crc32\"", "\"fnv1a\"")
        val result = parse(payload(), json = json)

        assertTrue(result.tree.readOnly)
        assertTrue(result.tree.readOnlyReason!!.contains("does not implement"))
        val applied = result.document.applyEdits(
            listOf(FieldEdit.of(result.tree.node("player.gold")!!, FieldValue.Int64(5))),
        )
        assertEquals(0, applied.applied.size)
        assertTrue(applied.rejected.single().reason.contains("does not implement"))
    }

    @Test
    fun aStoredChecksumThatDoesNotMatchIsReportedAndStillRepaired() {
        val bytes = payload()
        bytes[4] = (bytes[4].toInt() xor 0xFF).toByte() // corrupt the stored digest

        val result = parse(bytes)

        assertTrue(result.tree.warnings.any { it.contains("does not match") })
        assertFalse(result.tree.readOnly)
        result.document.applyEdits(listOf(FieldEdit.of(result.tree.node("player.gold")!!, FieldValue.Int64(500))))
        val repaired = parse(result.document.toBytes())
        assertTrue(repaired.tree.warnings.isEmpty())
        assertEquals(500L, (repaired.tree.node("player.gold")!!.value as FieldValue.Int64).value)
    }

    // ---- helpers --------------------------------------------------------------------

    private fun putU32Little(bytes: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) bytes[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun putI16Little(bytes: ByteArray, at: Int, value: Short) {
        bytes[at] = (value.toInt() and 0xFF).toByte()
        bytes[at + 1] = ((value.toInt() ushr 8) and 0xFF).toByte()
    }

    private fun putFloatLittle(bytes: ByteArray, at: Int, value: Float) =
        putU32Little(bytes, at, java.lang.Float.floatToRawIntBits(value).toLong() and 0xFFFFFFFFL)

    private fun readU32(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }

    private fun readI16(bytes: ByteArray, at: Int): Long {
        val raw = (bytes[at].toLong() and 0xFF) or ((bytes[at + 1].toLong() and 0xFF) shl 8)
        return if (raw > 32767) raw - 65536 else raw
    }

    private fun crc(bytes: ByteArray, from: Int, to: Int): Long {
        val digest = CRC32()
        digest.update(bytes, from, to - from)
        return digest.value
    }
}
