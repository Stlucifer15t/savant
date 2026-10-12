package dev.savant.codecs

import dev.savant.domain.model.Endianness
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two small sample binaries, end to end: checksum placement, checksum repair after an edit, and
 * codec round trips.
 *
 * These are the guarantees a binary parser (step 5) will lean on, so they are pinned now: whatever
 * a game stores, savant has to be able to recompute it and put it back exactly where it was.
 */
class CodecRoundTripTest {

    /**
     * Sample A: a 4 byte little endian CRC32 header followed by 20 bytes of payload.
     * The shape a lot of console-era saves use: the checksum covers everything after itself.
     */
    private val payloadA = byteArrayOf(
        0x01, 0x00, 0x00, 0x00, 0x2c, 0x01, 0x00, 0x00,
        0x41, 0x72, 0x69, 0x63, 0x00, 0x00, 0x00, 0x00,
        0xff.toByte(), 0xfe.toByte(), 0x10, 0x00,
    )

    private val specA = ChecksumSpec(
        strategy = Crc32Checksum,
        range = ByteRangeSpec(start = 4L),
        storedAtOffset = 0L,
        endianness = Endianness.LITTLE,
    )

    /** Sample B: an Adler32 trailer over an XOR obfuscated body. */
    private val bodyB = byteArrayOf(
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        0x21, 0x32, 0x43, 0x54,
    )

    private val specB = ChecksumSpec(
        strategy = Adler32Checksum,
        range = ByteRangeSpec(start = 0L, length = 20L),
        storedAtOffset = 20L,
        endianness = Endianness.BIG,
    )

    private fun fileA(): ByteArray = specA.apply(ByteArray(4) + payloadA)

    private fun fileB(): ByteArray = specB.apply(bodyB + ByteArray(4))

    // ---- checksums ---------------------------------------------------------------

    @Test
    fun buildsAFileWithACrc32HeaderThatVerifies() {
        val file = fileA()
        assertEquals(24, file.size)
        assertTrue("a freshly built file has to verify", specA.matches(file))
        val expectedHex = Crc32Checksum.compute(payloadA).joinToString("") { "%02x".format(it) }
        assertEquals(expectedHex, specA.digestHex(file))
    }

    @Test
    fun detectsTamperingInThePayload() {
        val file = fileA()
        file[9] = (file[9].toInt() xor 0x01).toByte()
        assertFalse(specA.matches(file))
    }

    @Test
    fun repairsTheChecksumAfterAnEdit() {
        val original = fileA()
        val edited = original.copyOf().also { it[12] = 0x7f }
        assertFalse(specA.matches(edited))

        val repaired = specA.apply(edited)
        assertTrue(specA.matches(repaired))
        // The payload edit survives; only the four checksum bytes moved.
        assertArrayEquals(edited.copyOfRange(4, 24), repaired.copyOfRange(4, 24))
        assertFalse("the header did change", original.copyOfRange(0, 4).contentEquals(repaired.copyOfRange(0, 4)))
    }

    @Test
    fun handlesABigEndianTrailerChecksum() {
        val file = fileB()
        assertEquals(24, file.size)
        assertTrue(specB.matches(file))
        // The trailer is the last four bytes, big endian.
        assertArrayEquals(Adler32Checksum.compute(bodyB), file.copyOfRange(20, 24))

        val edited = file.copyOf().also { it[3] = 0x00 }
        assertFalse(specB.matches(edited))
        assertTrue(specB.matches(specB.apply(edited)))
    }

    @Test
    fun storedDigestReflectsTheFileEndianness() {
        val file = fileA()
        val natural = specA.digest(file)
        // Little endian on disk: the natural digest reversed.
        assertArrayEquals(natural.reversedArray(), specA.storedDigest(file))
        assertArrayEquals(specA.storedDigest(file), file.copyOfRange(0, 4))
        assertEquals(specA.digestNumber(file), natural.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) })

        // Big endian storage keeps the natural order.
        val fileB = fileB()
        assertArrayEquals(specB.digest(fileB), specB.storedDigest(fileB))
    }

    @Test
    fun rangeSpecsSliceAndExclude() {
        val bytes = ByteArray(16) { it.toByte() }
        assertEquals(12, ByteRangeSpec(start = 4L).slice(bytes).size)
        assertEquals(6, ByteRangeSpec(start = 2L, length = 6L).slice(bytes).size)
        val withHole = ByteRangeSpec(start = 0L, length = 16L, exclude = listOf(4L..7L)).slice(bytes)
        assertEquals(12, withHole.size)
        assertFalse(withHole.contains(4))
    }

    // ---- codecs ------------------------------------------------------------------

    @Test
    fun gzipRoundTripsAWholeSave() {
        val file = fileA()
        val packed = GzipCodec.encode(file)
        assertTrue(GzipCodec.changesLength)
        assertFalse(packed.contentEquals(file))
        assertArrayEquals(file, GzipCodec.decode(packed))
        assertEquals("gzip", Codecs.sniff(packed).first().first.id)
    }

    @Test
    fun zlibRoundTripsAWholeSave() {
        val file = fileB()
        val packed = ZlibCodec.encode(file)
        assertArrayEquals(file, ZlibCodec.decode(packed))
        val sniffed = Codecs.sniff(packed).firstOrNull()
        assertNotNull(sniffed)
        assertEquals("zlib", sniffed!!.first.id)
    }

    @Test
    fun xorIsSymmetricAndKeyedPerByte() {
        val key = XorCodec.parseKey("a5 3c")
        assertEquals(2, key.size)
        val codec = XorCodec(key)
        val packed = codec.encode(bodyB)
        assertFalse(packed.contentEquals(bodyB))
        assertArrayEquals(bodyB, codec.decode(packed))
        // The same byte at two positions is masked differently once the key is longer than one.
        val repeated = byteArrayOf(0x10, 0x10, 0x10, 0x10)
        val masked = XorCodec(byteArrayOf(0xff.toByte(), 0x00)).encode(repeated)
        assertEquals(masked[0], masked[2])
        assertEquals(repeated[1], masked[1])
    }

    @Test
    fun identityIsTheDefaultAndNeverChangesLength() {
        assertArrayEquals(payloadA, IdentityCodec.decode(payloadA))
        assertFalse(IdentityCodec.changesLength)
        assertEquals(IdentityCodec, Codecs.byId(null))
        assertEquals(IdentityCodec, Codecs.byId("none"))
        assertEquals(GzipCodec, Codecs.byId("gzip"))
    }

    @Test
    fun xorIsNotAutoSniffedBecauseItNeedsAKey() {
        // Codecs.all only holds the keyless strategies: a plugin has to name xor explicitly.
        assertEquals(listOf("none", "gzip", "zlib"), Codecs.all.map { it.id })
        assertNull(Codecs.byId("xor"))
    }

    @Test
    fun aCorruptStreamFailsLoudlyInsteadOfReturningGarbage() {
        val packed = GzipCodec.encode(payloadA).also { it[12] = (it[12].toInt() xor 0x5a).toByte() }
        var threw = false
        try {
            GzipCodec.decode(packed)
        } catch (expected: CodecException) {
            threw = true
        } catch (expected: Exception) {
            threw = true
        }
        assertTrue("a truncated gzip stream must not decode silently", threw)
    }
}
