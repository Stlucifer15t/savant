package dev.savant.parsers

import dev.savant.codecs.Adler32Checksum
import dev.savant.codecs.ByteRangeSpec
import dev.savant.codecs.ChecksumSpec
import dev.savant.codecs.Codecs
import dev.savant.codecs.Crc32Checksum
import dev.savant.codecs.GzipCodec
import dev.savant.codecs.IdentityCodec
import dev.savant.codecs.Md5Checksum
import dev.savant.codecs.Sha1Checksum
import dev.savant.codecs.XorCodec
import dev.savant.codecs.ZlibCodec
import dev.savant.domain.model.Endianness
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.SaveFormat
import dev.savant.parsers.categorize.CategoryClassifier
import dev.savant.parsers.categorize.Humanizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format sniffing, category keywords, key humanising, codecs and checksums. */
class DetectionAndNamingTest {

    // ---- detection ---------------------------------------------------------------

    @Test
    fun sniffsTextFormats() {
        assertTrue(FormatDetector.looksLikeJson("""{"a":1}""".toByteArray()))
        assertTrue(FormatDetector.looksLikeJson("""[1,2]""".toByteArray()))
        assertFalse(FormatDetector.looksLikeJson("<a/>".toByteArray()))
        assertTrue(FormatDetector.looksLikeXml("<?xml version=\"1.0\"?><a/>".toByteArray()))
        assertTrue(FormatDetector.looksLikeXml("<save><a>1</a></save>".toByteArray()))
        assertFalse(FormatDetector.looksLikeXml("""{"a":1}""".toByteArray()))
        assertTrue(FormatDetector.looksLikeIni("[player]\ngold=5\nhp=1\n".toByteArray()))
        assertFalse(FormatDetector.looksLikeIni("""{"a":1}""".toByteArray()))
        // unreal's `+Key=` lines and keys with spaces are ini too; prose is not.
        assertTrue(FormatDetector.looksLikeIni("[/Script/Engine]\n+MapsToCook=(FilePath=\"/Game/A\")\n+MapsToCook=(FilePath=\"/Game/B\")\n".toByteArray()))
        assertTrue(FormatDetector.looksLikeIni("Player Name=Aric\nPlay Time=12\n".toByteArray()))
        assertFalse(FormatDetector.looksLikeIni("dear diary\ntoday i found a save file\nit was = to nothing\n".toByteArray()))
    }

    @Test
    fun sniffsMagicBytes() {
        val gzip = GzipCodec.encode("hello".toByteArray())
        assertTrue(FormatDetector.hasGzipMagic(gzip))
        assertEquals("gzip", Codecs.sniff(gzip).first().first.id)

        val zlib = ZlibCodec.encode("hello".toByteArray())
        assertTrue(FormatDetector.hasZlibMagic(zlib))

        val sqlite = ("SQLite format 3\u0000" + "x".repeat(100)).toByteArray()
        assertTrue(FormatDetector.hasSqliteMagic(sqlite))

        val plist = """<?xml version="1.0"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "x"><plist/>"""
        assertTrue(FormatDetector.isApplePlist(plist.toByteArray()))

        val binary = byteArrayOf(0x00, 0x01, 0x02, 0xff.toByte(), 0x10, 0x00, 0x00, 0x7f)
        assertFalse(FormatDetector.looksLikeText(binary))
        assertTrue(FormatDetector.printableRatio(binary) < 0.5f)
    }

    @Test
    fun guessRanksTheBestFormatFirst() {
        val guess = FormatDetector.guess("""{"a":1}""".toByteArray(), "save.json")
        assertTrue(guess.isNotEmpty())
        assertEquals(SaveFormat.JSON, guess.first().first)

        val binaryGuess = FormatDetector.guess(ByteArray(64) { (it * 7 % 251).toByte() }, "save.dat")
        assertEquals(SaveFormat.BINARY, binaryGuess.first().first)
    }

    @Test
    fun blockerCopyIsSpecific() {
        val gzip = GzipCodec.encode("{}".toByteArray())
        assertTrue(FormatDetector.describeBlocker(gzip, "x.json.gz").contains("gzip"))
        assertTrue(FormatDetector.describeBlocker(ByteArray(32), "x.dat").contains("binary"))
        val sqlite = ("SQLite format 3\u0000" + "x".repeat(64)).toByteArray()
        assertTrue(FormatDetector.describeBlocker(sqlite, "x.db").contains("sqlite"))
    }

    @Test
    fun formatFromExtension() {
        assertEquals(SaveFormat.JSON, SaveFormat.fromExtension("json"))
        assertEquals(SaveFormat.SQLITE, SaveFormat.fromExtension(".SQLITE3"))
        assertEquals(SaveFormat.RPGSAVE, SaveFormat.fromExtension("rpgsave"))
        assertEquals(null, SaveFormat.fromExtension("exe"))
        assertEquals(null, SaveFormat.fromExtension(null))
    }

    // ---- categories --------------------------------------------------------------

    private fun category(path: String, key: String = path.substringAfterLast('.'), type: FieldType = FieldType.INT32) =
        CategoryClassifier.classify(path, key, FieldValue.Int64(1), type)

    @Test
    fun classifiesByKeyword() {
        assertEquals(FieldCategory.CURRENCY, category("player.gold"))
        assertEquals(FieldCategory.CURRENCY, category("player.coin_count", "coin_count"))
        assertEquals(FieldCategory.STATS, category("player.max_hp", "max_hp"))
        assertEquals(FieldCategory.STATS, category("player.level"))
        assertEquals(FieldCategory.FLAGS, category("player.tutorialDone", "tutorialDone"))
        assertEquals(FieldCategory.OTHER, category("player.v_0042", "v_0042"))
    }

    @Test
    fun classifiesByAncestorContext() {
        assertEquals(FieldCategory.INVENTORY, category("inventory.items[0].count", "count"))
        assertEquals(FieldCategory.INVENTORY, category("party[0].name", "name"))
        assertEquals(FieldCategory.CURRENCY, category("wallet.balance", "balance"))
        assertEquals(FieldCategory.FLAGS, category("quests.main.started", "started"))
    }

    @Test
    fun booleansAreFlags() {
        assertEquals(FieldCategory.FLAGS, CategoryClassifier.classify("save.seen", "seen", FieldValue.Bool(true), FieldType.BOOL))
        assertEquals(
            FieldCategory.FLAGS,
            CategoryClassifier.classify("settings.muted", "muted", FieldValue.Bool(false), FieldType.BOOL),
        )
    }

    @Test
    fun shortKeywordsDoNotOverMatch() {
        // "int" must not turn "point" into a stat, "hp" must not turn "shopping" into a stat.
        assertEquals(FieldCategory.OTHER, category("player.points_total", "points_total", FieldType.STRING))
        assertEquals(FieldCategory.OTHER, category("shop.shopping_cart", "shopping_cart", FieldType.STRING))
    }

    // ---- humanising --------------------------------------------------------------

    @Test
    fun humanizesKeys() {
        assertEquals("Max HP", Humanizer.humanize("max_hp"))
        assertEquals("Max HP", Humanizer.humanize("maxHp"))
        assertEquals("Player Gold", Humanizer.humanize("player_gold"))
        assertEquals("Gold", Humanizer.humanize("gold"))
        // A hungarian prefix is not guessed away offline; renaming that is the AI's job.
        assertEquals("B Is Dead", Humanizer.humanize("bIsDead"))
        assertEquals("Tutorial Complete", Humanizer.humanize("tutorial-complete"))
        assertEquals("Slot 3", Humanizer.humanize("slot3"))
        assertEquals("", Humanizer.humanize(""))
    }

    @Test
    fun detectsCrypticKeys() {
        assertTrue(Humanizer.isCryptic("v_0042"))
        assertTrue(Humanizer.isCryptic("f3"))
        assertTrue(Humanizer.isCryptic("x"))
        assertTrue(Humanizer.isCryptic("0042"))
        assertFalse(Humanizer.isCryptic("gold"))
        assertFalse(Humanizer.isCryptic("player_level"))
        assertFalse(Humanizer.isCryptic("inventory"))
    }

    // ---- codecs ------------------------------------------------------------------

    @Test
    fun identityCodecIsATransparentNoOp() {
        val bytes = byteArrayOf(1, 2, 3)
        assertTrue(IdentityCodec.decode(bytes).contentEquals(bytes))
        assertTrue(IdentityCodec.encode(bytes).contentEquals(bytes))
        assertFalse(IdentityCodec.changesLength)
        assertEquals(IdentityCodec, Codecs.byId(null))
        assertEquals(IdentityCodec, Codecs.byId("none"))
    }

    @Test
    fun gzipAndZlibRoundTrip() {
        val payload = """{"gold":5000,"items":[1,2,3]}""".toByteArray()
        assertTrue(GzipCodec.decode(GzipCodec.encode(payload)).contentEquals(payload))
        assertTrue(ZlibCodec.decode(ZlibCodec.encode(payload)).contentEquals(payload))
        val big = ByteArray(200_000) { (it % 251).toByte() }
        assertTrue(ZlibCodec.decode(ZlibCodec.encode(big)).contentEquals(big))
    }

    @Test
    fun xorIsItsOwnInverse() {
        val codec = XorCodec(XorCodec.parseKey("1f 8b 03"))
        val payload = "secret save".toByteArray()
        val encoded = codec.encode(payload)
        assertFalse(encoded.contentEquals(payload))
        assertTrue(codec.decode(encoded).contentEquals(payload))
        assertEquals(3, XorCodec.parseKey("0x1f,0x8b,0x03").size)
    }

    // ---- checksums ---------------------------------------------------------------

    @Test
    fun checksumStrategiesMatchKnownVectors() {
        val digits = "123456789".toByteArray()
        assertEquals("cbf43926", Crc32Checksum.compute(digits).joinToString("") { "%02x".format(it) })
        assertEquals("11e60398", Adler32Checksum.compute("Wikipedia".toByteArray()).joinToString("") { "%02x".format(it) })
        assertEquals(
            "d41d8cd98f00b204e9800998ecf8427e",
            Md5Checksum.compute(ByteArray(0)).joinToString("") { "%02x".format(it) },
        )
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            Sha1Checksum.compute("abc".toByteArray()).joinToString("") { "%02x".format(it) },
        )
        assertEquals(4, Crc32Checksum.widthBytes)
        assertEquals(16, Md5Checksum.widthBytes)
        assertNotNull(dev.savant.codecs.Checksums.byId("crc32"))
        assertEquals(null, dev.savant.codecs.Checksums.byId("nope"))
    }

    @Test
    fun byteRangesCanExcludeTheStoredChecksum() {
        val bytes = ByteArray(16) { it.toByte() }
        val whole = ByteRangeSpec().slice(bytes)
        assertEquals(16, whole.size)

        val fromOffset = ByteRangeSpec(start = 4, length = 4).slice(bytes)
        assertEquals(listOf<Byte>(4, 5, 6, 7), fromOffset.toList())

        // The stored digest itself must be carved out, otherwise it feeds back into itself.
        val excluding = ByteRangeSpec(start = 0, exclude = listOf(4L..7L)).slice(bytes)
        assertEquals(12, excluding.size)
        assertEquals(listOf<Byte>(0, 1, 2, 3, 8, 9), excluding.take(6).toList())
    }

    @Test
    fun checksumSpecPatchesAndVerifiesBigEndian() {
        val payload = "123456789".toByteArray() + ByteArray(4)
        val spec = ChecksumSpec(
            strategy = Crc32Checksum,
            range = ByteRangeSpec(start = 0, length = 9),
            storedAtOffset = 9,
            endianness = Endianness.BIG,
        )
        val patched = spec.apply(payload)
        assertEquals("cbf43926", patched.copyOfRange(9, 13).joinToString("") { "%02x".format(it) })
        assertTrue(spec.matches(patched))
        assertFalse(spec.matches(payload))
    }

    @Test
    fun checksumSpecPatchesLittleEndianAndReportsText() {
        val payload = "123456789".toByteArray() + ByteArray(4)
        val spec = ChecksumSpec(
            strategy = Crc32Checksum,
            range = ByteRangeSpec(start = 0, length = 9),
            storedAtOffset = 9,
            endianness = Endianness.LITTLE,
        )
        val patched = spec.apply(payload)
        assertEquals("2639f4cb", patched.copyOfRange(9, 13).joinToString("") { "%02x".format(it) })
        assertTrue(spec.matches(patched))
        assertEquals(3421780262L, spec.digestNumber(patched))
        assertTrue(spec.digestText(patched).isNotEmpty())
    }
}
