package dev.savant.domain.finder

import dev.savant.domain.model.Endianness
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typed reads over bytes.
 *
 * These are the primitives the finder and the step 9 binary parser both stand on, so they are
 * pinned on their own: one wrong shift here and every candidate points at the wrong number.
 */
class BinaryValuesTest {

    private val little = byteArrayOf(0x78, 0x56, 0x34, 0x12, 0x00, 0x00, 0x00, 0x00)
    private val big = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x00, 0x00, 0x00, 0x00)

    @Test
    fun readsIntegersInBothByteOrders() {
        assertEquals(0x12345678L, value(little, 0, FieldType.INT32, Endianness.LITTLE))
        assertEquals(0x12345678L, value(big, 0, FieldType.INT32, Endianness.BIG))
        // The same four bytes, read the other way round, are a different number.
        assertEquals(0x78563412L, value(little, 0, FieldType.UINT32, Endianness.LITTLE))
    }

    @Test
    fun signExtendsNarrowIntegers() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1L, value(bytes, 0, FieldType.INT8, Endianness.LITTLE))
        assertEquals(-1L, value(bytes, 0, FieldType.INT16, Endianness.LITTLE))
        assertEquals(-1L, value(bytes, 0, FieldType.INT32, Endianness.LITTLE))
        assertEquals(255L, value(bytes, 0, FieldType.UINT8, Endianness.LITTLE))
        assertEquals(65535L, value(bytes, 0, FieldType.UINT16, Endianness.LITTLE))
        assertEquals(4294967295L, value(bytes, 0, FieldType.UINT32, Endianness.LITTLE))
    }

    @Test
    fun readsFloatsAndDoubles() {
        val floatBytes = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(42.5f).array()
        val read = BinaryValues.read(floatBytes, 0, FieldType.FLOAT, Endianness.LITTLE) as FieldValue.Real
        assertEquals(42.5, read.value, 1e-6)
        assertTrue("a f32 read has to stay marked as float32", read.float32)

        val doubleBytes = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.BIG_ENDIAN).putDouble(-1.25).array()
        val double = BinaryValues.read(doubleBytes, 0, FieldType.DOUBLE, Endianness.BIG) as FieldValue.Real
        assertEquals(-1.25, double.value, 1e-12)
    }

    @Test
    fun aWindowThatDoesNotFitReadsAsNull() {
        assertNull(BinaryValues.read(little, 6, FieldType.INT32, Endianness.LITTLE))
        assertNull(BinaryValues.read(little, -1, FieldType.INT16, Endianness.LITTLE))
        assertNull(BinaryValues.read(little, 0, FieldType.STRING, Endianness.LITTLE))
    }

    @Test
    fun onlyFixedWidthTypesAreScannable() {
        assertTrue(BinaryValues.scannable.contains(FieldType.INT32))
        assertTrue(BinaryValues.scannable.contains(FieldType.DOUBLE))
        assertTrue(BinaryValues.scannable.none { it.sizeBytes == null })
        assertTrue(BinaryValues.scannable.none { it == FieldType.STRING || it == FieldType.BOOL })
    }

    @Test
    fun offsetsAndBytesPrintTheWayAHexEditorDoes() {
        assertEquals("0x000008", BinaryValues.offsetLabel(8))
        assertEquals("0x0001f0", BinaryValues.offsetLabel(496))
        assertEquals("78 56 34", BinaryValues.hex(little, 0, 3))
        assertEquals("", BinaryValues.hex(little, 99, 4))
    }

    @Test
    fun textIsRecognisedSoItCanBeRankedDown() {
        val bytes = "Aric\u0000\u0000".toByteArray(Charsets.UTF_8)
        assertTrue(BinaryValues.looksLikeText(bytes, 0, 4))
        assertTrue("a zero padded name is still text", BinaryValues.looksLikeText(bytes, 0, 6))
        assertTrue(!BinaryValues.looksLikeText(byteArrayOf(0xFF.toByte(), 0x01, 0x02, 0x03), 0, 4))
    }

    @Test
    fun digestShapedWindowsAreRecognised() {
        assertTrue(BinaryValues.allBytesSet(byteArrayOf(1, 2, 3, 4), 0, 4))
        assertTrue(BinaryValues.allBytesSet(byteArrayOf(0xFF.toByte(), 0xAB.toByte()), 0, 2))
        assertTrue(!BinaryValues.allBytesSet(byteArrayOf(1, 0, 3, 4), 0, 4))
        assertTrue(!BinaryValues.allBytesSet(byteArrayOf(1, 2, 3, 4), 2, 8))
    }

    private fun value(bytes: ByteArray, offset: Int, type: FieldType, endian: Endianness): Long? =
        (BinaryValues.read(bytes, offset, type, endian) as? FieldValue.Int64)?.value
}
