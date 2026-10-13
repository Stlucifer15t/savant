package dev.savant.domain.finder

import dev.savant.domain.model.Endianness
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue

/**
 * Typed reads over raw bytes.
 *
 * Everything here is pure jvm and side effect free, because the finder's whole job is to be
 * trustworthy about *where* a number lives: an offset that is off by one, or a value read with the
 * wrong byte order, sends the user to edit the wrong thing.
 *
 * The same helpers back the binary parser in step 9, so a scan result and a parsed field agree on
 * what `i32 le` at `0x1f0` means.
 */
object BinaryValues {

    /** Types the scanner can interpret: fixed width numbers, both integer and floating point. */
    val scannable: List<FieldType> = FieldType.entries.filter { it.sizeBytes != null && it != FieldType.BOOL }

    fun sizeOf(type: FieldType): Int = type.sizeBytes ?: 0

    /**
     * Reads one typed value, or null when [type] is not fixed width or the window does not fit.
     *
     * Returning null rather than throwing matters: a scan walks every offset of a 50 MB file, and
     * most of them are not the type being looked for.
     */
    fun read(bytes: ByteArray, offset: Int, type: FieldType, endian: Endianness): FieldValue? {
        val size = type.sizeBytes ?: return null
        if (offset < 0 || size <= 0 || offset + size > bytes.size) return null
        return when (type) {
            FieldType.FLOAT -> FieldValue.Real(Float.fromBits(bits(bytes, offset, size, endian).toInt()).toDouble(), float32 = true)
            FieldType.DOUBLE -> FieldValue.Real(Double.fromBits(bits(bytes, offset, size, endian)))
            else -> {
                val raw = bits(bytes, offset, size, endian)
                // `signed` is nullable on FieldType; only an explicitly unsigned type is unsigned.
                if (type.signed == false) FieldValue.Int64(raw, unsigned = true) else FieldValue.Int64(signExtend(raw, size))
            }
        }
    }

    /** The raw bit pattern at [offset], assembled in [endian] order. */
    fun bits(bytes: ByteArray, offset: Int, size: Int, endian: Endianness): Long {
        var value = 0L
        if (endian == Endianness.BIG) {
            for (i in 0 until size) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        } else {
            for (i in size - 1 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }

    /** A 3 byte read must not report the sign of the 8 byte container it was assembled in. */
    fun signExtend(raw: Long, size: Int): Long {
        if (size >= 8) return raw
        val shift = 64 - size * 8
        return raw shl shift shr shift
    }

    /** `0x000001f0`, the way every hex editor writes it. */
    fun offsetLabel(offset: Int): String = "0x" + Integer.toHexString(offset).padStart(6, '0')

    fun hex(bytes: ByteArray, offset: Int, length: Int): String {
        val from = offset.coerceIn(0, bytes.size)
        val to = (offset + length).coerceAtMost(bytes.size)
        return buildString {
            for (i in from until to) {
                if (i > from) append(' ')
                append(((bytes[i].toInt() and 0xFF) + 0x100).toString(16).substring(1))
            }
        }
    }

    /**
     * True when every byte in the window is printable ascii.
     *
     * A number that is really part of a name — `Aric` reads as a plausible integer — is the most
     * common false positive in a byte scan, and this is what pushes it down the list.
     */
    fun looksLikeText(bytes: ByteArray, offset: Int, length: Int): Boolean {
        if (offset < 0 || offset + length > bytes.size) return false
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            if (b != 0 && (b < 0x20 || b > 0x7E)) return false
        }
        return true
    }

    /**
     * True when not one byte in the window is zero.
     *
     * Game counters are small, so they leave zero bytes behind: 999 999 gold is `3f 42 0f 00`.
     * A window where every byte is set is far more often a digest, a pointer or a timestamp, and the
     * finder ranks it down instead of pretending it is the gold counter.
     */
    fun allBytesSet(bytes: ByteArray, offset: Int, length: Int): Boolean {
        if (offset < 0 || offset + length > bytes.size) return false
        for (i in offset until offset + length) if (bytes[i].toInt() == 0) return false
        return true
    }

    /** Reads the same window from two files, so a caller can ask "did this change?". */
    fun readBoth(
        a: ByteArray,
        b: ByteArray,
        offset: Int,
        type: FieldType,
        endian: Endianness,
    ): Pair<FieldValue?, FieldValue?> = read(a, offset, type, endian) to read(b, offset, type, endian)
}
