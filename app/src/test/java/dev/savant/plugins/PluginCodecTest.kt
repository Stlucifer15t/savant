package dev.savant.plugins

import dev.savant.codecs.Checksums
import dev.savant.domain.model.Endianness
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.SaveFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plugin document: what imports, what is refused and why, and what export writes back.
 *
 * The first test uses the exact json the formats screen has been showing since step 3, because that
 * sample is a promise — a file written against the documentation has to import.
 */
class PluginCodecTest {

    private val documented = """
        {
          "gameName": "example quest",
          "fileMatch": ["save*.dat"],
          "parser": "binary",
          "codec": "zlib",
          "checksum": { "strategy": "crc32", "range": [4, -1], "storedAt": [0, 4] },
          "fields": [
            { "path": "player.gold", "offset": 16, "type": "u32" },
            { "path": "party[*].hp", "pointer": 40, "stride": 24, "type": "i16" }
          ]
        }
    """.trimIndent()

    @Test
    fun theDocumentedSampleImportsAsIs() {
        val result = PluginCodec.decode(documented)
        assertTrue("expected a plugin, got $result", result is PluginDecode.Ok)
        val plugin = (result as PluginDecode.Ok).plugin

        assertEquals("example quest", plugin.gameName)
        assertEquals(listOf("save*.dat"), plugin.fileMatch)
        assertEquals(PluginParserKind.BINARY, plugin.parser)
        assertEquals(SaveFormat.BINARY, plugin.format)
        assertEquals("zlib", plugin.codec)
        assertEquals(Endianness.LITTLE, plugin.endianness)

        val checksum = plugin.checksum!!
        assertEquals("crc32", checksum.strategy)
        assertEquals(4L, checksum.rangeStart)
        assertEquals(-1L, checksum.rangeEnd)
        assertEquals(0L, checksum.storedAt)

        assertEquals(2, plugin.fields.size)
        val gold = plugin.fields[0]
        assertEquals("player.gold", gold.path)
        assertEquals(16, gold.offset)
        assertEquals(FieldType.UINT32, gold.fieldType)
        assertEquals("gold", gold.label)

        val party = plugin.fields[1]
        assertEquals(40, party.pointer)
        assertEquals(24, party.stride)
        assertEquals(FieldType.INT16, party.fieldType)
        assertTrue(party.isArray)
    }

    @Test
    fun exportThenImportGivesTheSamePluginBack() {
        val plugin = (PluginCodec.decode(documented) as PluginDecode.Ok).plugin
        val exported = PluginCodec.encode(plugin)
        val back = (PluginCodec.decode(exported) as PluginDecode.Ok).plugin

        assertEquals(plugin.id, back.id)
        assertEquals(plugin.gameName, back.gameName)
        assertEquals(plugin.parser, back.parser)
        assertEquals(plugin.codec, back.codec)
        assertEquals(plugin.checksum, back.checksum)
        assertEquals(plugin.fields, back.fields)
        assertEquals(plugin.fileMatch, back.fileMatch)
    }

    @Test
    fun anExportedPluginIsSomethingAPersonCanRead() {
        val plugin = (PluginCodec.decode(documented) as PluginDecode.Ok).plugin
        val exported = PluginCodec.encode(plugin)

        assertTrue(exported.contains("\n"))
        assertTrue(exported.contains("  \"gameName\""))
        assertTrue(exported.endsWith("\n"))
        // And the optional keys that were never set stay out of the way.
        assertFalse(exported.contains("\"xorKey\""))
        assertFalse(exported.contains("\"pointerBase\""))
    }

    @Test
    fun aListOfPluginsImportsAndTheBadOnesAreReported() {
        val document = """
            {"schema":1,"plugins":[
              $documented,
              {"gameName":"","fields":[]}
            ]}
        """.trimIndent()

        val (good, bad) = PluginCodec.decodeAll(document)

        assertEquals(1, good.size)
        assertEquals("example quest", good.single().gameName)
        assertEquals(1, bad.size)
        assertTrue(bad.single().contains("plugin 2"))
    }

    @Test
    fun aSinglePluginDocumentIsAlsoAList() {
        val (good, bad) = PluginCodec.decodeAll(documented)
        assertEquals(1, good.size)
        assertTrue(bad.isEmpty())
    }

    @Test
    fun thingsThatAreNotPluginsAreRefusedWithReasons() {
        assertRefused("not json at all") { it.contains("not a json object") }
        assertRefused("""{"fields":[]}""") { it.contains("gameName") }
        assertRefused("""{"gameName":"x","fields":[]}""") { it.contains("at least one field") }
        assertRefused("""{"gameName":"x","fields":[{"path":"a","type":"i32","offset":0}],"parser":"lua"}""") {
            it.contains("parser 'lua'")
        }
        assertRefused("""{"gameName":"x","fields":[{"path":"a","type":"i32","offset":0}],"codec":"aes"}""") {
            it.contains("codec 'aes'")
        }
        assertRefused("""{"gameName":"x","schema":9,"fields":[{"path":"a","type":"i32","offset":0}]}""") {
            it.contains("newer than this savant")
        }
    }

    @Test
    fun fieldProblemsAreNamedByTheirPath() {
        assertRefused("""{"gameName":"x","fields":[{"path":"gold","type":"i99","offset":0}]}""") {
            it.contains("'gold' has type 'i99'")
        }
        assertRefused("""{"gameName":"x","fields":[{"path":"gold","type":"i32"}]}""") {
            it.contains("'gold' needs an offset")
        }
        assertRefused("""{"gameName":"x","fields":[{"path":"gold","type":"i32","pointer":4}]}""") {
            it.contains("'gold' has a pointer but no stride")
        }
        assertRefused(
            """{"gameName":"x","fields":[{"path":"gold","type":"i32","offset":0},{"path":"gold","type":"i16","offset":4}]}""",
        ) { it.contains("duplicate field path") }
    }

    @Test
    fun aTextPluginMapsPathsAndMayNotCarryOffsets() {
        val json = """
            {"gameName":"example","parser":"json","fileMatch":["example*.json"],"fields":[
              {"path":"player.gld","type":"i32","name":"Gold","category":"currency","max":999999}
            ]}
        """.trimIndent()
        val plugin = (PluginCodec.decode(json) as PluginDecode.Ok).plugin

        assertEquals(PluginParserKind.JSON, plugin.parser)
        assertEquals(SaveFormat.JSON, plugin.format)
        assertEquals("Gold", plugin.fields.single().displayName)
        assertEquals(dev.savant.domain.model.FieldCategory.CURRENCY, plugin.fields.single().declaredCategory)
        assertEquals(999999.0, plugin.fields.single().max ?: 0.0, 0.0)

        assertRefused("""{"gameName":"x","parser":"json","fields":[{"path":"a","type":"i32","offset":4}]}""") {
            it.contains("cannot also carry an offset")
        }
    }

    @Test
    fun xorNeedsAKeySavantCanActuallyUse() {
        assertRefused("""{"gameName":"x","codec":"xor","fields":[{"path":"a","type":"i32","offset":0}]}""") {
            it.contains("xor needs a hex xorKey")
        }
        val ok = PluginCodec.decode(
            """{"gameName":"x","codec":"xor","xorKey":"1F 2E","fields":[{"path":"a","type":"i32","offset":0}]}""",
        )
        assertTrue(ok is PluginDecode.Ok)
        val (codec, problem) = PluginCodec.codecFor((ok as PluginDecode.Ok).plugin)
        assertNull(problem)
        assertEquals("xor", codec?.id)
    }

    @Test
    fun hexKeysAndMagicParseInTheShapesPeopleWrite() {
        assertEquals(listOf(0x53, 0x56, 0x53, 0x31), PluginCodec.parseHex("53 56 53 31").map { it.toInt() })
        assertEquals(listOf(0x53, 0x56), PluginCodec.parseHex("0x53,0x56").map { it.toInt() })
        assertEquals(listOf(0x53, 0x56), PluginCodec.parseHex("5356").map { it.toInt() })
        assertEquals(0, PluginCodec.parseHex("535").size)
        assertEquals(0, PluginCodec.parseHex("").size)
        assertEquals("53 56", PluginCodec.hex(PluginCodec.parseHex("5356")))
    }

    @Test
    fun aChecksumBecomesARealSpecAndExcludesItsOwnBytes() {
        val plugin = (PluginCodec.decode(documented) as PluginDecode.Ok).plugin
        val spec = plugin.checksum!!.toSpec(plugin.endianness)!!

        assertEquals(Checksums.byId("crc32"), spec.strategy)
        assertEquals(0L, spec.storedAtOffset)
        assertEquals(4, spec.storedWidthBytes)
        assertEquals(Endianness.LITTLE, spec.endianness)
        assertEquals(4L, spec.range.start)
        assertNull("a negative range end means to the end of the file", spec.range.length)
        // The digest sits at 0..3 and the range starts at 4, so there is nothing to carve out.
        assertTrue(spec.range.exclude.isEmpty())

        // When the digest is inside the range it covers, it has to be excluded or nothing validates.
        val overlapping = PluginChecksum(strategy = "crc32", rangeStart = 0, rangeLength = -1, storedAt = 8, storedWidth = 4)
            .toSpec(Endianness.LITTLE)!!
        assertTrue(overlapping.range.exclude.any { it.first == 8L && it.last == 11L })
    }

    @Test
    fun aRangeIsAStartAndALengthNotAStartAndAnEnd() {
        val spec = PluginChecksum(strategy = "adler32", rangeStart = 4, rangeLength = 16, storedAt = 0, storedWidth = 4)
            .toSpec(Endianness.BIG)!!

        assertEquals(4L, spec.range.start)
        assertEquals(16L, spec.range.length)
        assertEquals(Endianness.BIG, spec.endianness)
        // The digest at 0..3 sits before the range it covers, so nothing is carved out.
        assertTrue(spec.range.exclude.isEmpty())
    }

    @Test
    fun aChecksumStrategySavantDoesNotImplementProducesNoSpec() {
        val checksum = PluginChecksum(strategy = "fnv1a", storedAt = 0)
        assertNull(checksum.toSpec(Endianness.LITTLE))
        assertTrue(checksum.label.contains("fnv1a"))
    }

    // ---- drafts built from the finder's named offsets -------------------------------

    @Test
    fun anOffsetKeyFromTheFinderParsesBackIntoOffsetTypeAndByteOrder() {
        val parsed = PluginDraft.parseKey("0x000008:i32 le")!!
        assertEquals(8, parsed.offset)
        assertEquals(FieldType.INT32, parsed.type)
        assertEquals(Endianness.LITTLE, parsed.endianness)

        val big = PluginDraft.parseKey("0x0001f0:f32 be")!!
        assertEquals(496, big.offset)
        assertEquals(FieldType.FLOAT, big.type)
        assertEquals(Endianness.BIG, big.endianness)

        assertNull(PluginDraft.parseKey("player.gold"))
        assertNull(PluginDraft.parseKey("0xzz:i32 le"))
        assertNull(PluginDraft.parseKey("0x000008:str le"))
    }

    @Test
    fun namedOffsetsBecomeABinaryPlugin() {
        val (plugin, skipped) = PluginDraft.fromNamedOffsets(
            gameName = "Example Quest",
            offsets = listOf(
                PluginDraft.NamedOffset("Gold", "0x000008:i32 le"),
                PluginDraft.NamedOffset("Party HP", "0x000030:i16 le"),
                PluginDraft.NamedOffset("not an offset", "player.gold"),
            ),
            fileNamePattern = "save*.dat",
        )

        assertEquals("Example Quest", plugin.gameName)
        assertEquals(PluginParserKind.BINARY, plugin.parser)
        assertEquals(PluginSource.FROM_FINDER, plugin.source)
        assertEquals(listOf("save*.dat"), plugin.fileMatch)
        assertEquals(2, plugin.fields.size)
        assertEquals("gold", plugin.fields[0].path)
        assertEquals("Gold", plugin.fields[0].displayName)
        assertEquals(8, plugin.fields[0].offset)
        assertEquals("party.hp", plugin.fields[1].path)
        assertEquals(1, skipped.size)
        assertTrue(skipped.single().contains("not an offset"))

        // The draft is a plugin like any other: it survives the document round trip.
        val back = (PluginCodec.decode(PluginCodec.encode(plugin)) as PluginDecode.Ok).plugin
        assertEquals(plugin.fields, back.fields)
    }

    private fun assertRefused(document: String, expect: (String) -> Boolean) {
        val result = PluginCodec.decode(document)
        assertTrue("expected a refusal for $document, got $result", result is PluginDecode.Invalid)
        val reasons = (result as PluginDecode.Invalid).reasons.joinToString("; ")
        assertTrue("expected a reason matching the refusal, got: $reasons", expect(reasons))
    }
}
