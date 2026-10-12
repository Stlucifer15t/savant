package dev.savant.codecs

import dev.savant.domain.model.Endianness
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.CRC32

/** The stored checksum does not match, or the range is outside the file. */
class ChecksumException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Algorithm hook: CRC32, Adler32, MD5, SHA1 and anything a plugin describes as a
 * "custom byte range" (see [ByteRangeSpec]).
 */
interface ChecksumStrategy {
    val id: String
    val label: String

    /** Digest width in bytes: 4 for crc32/adler32, 16 for md5, 20 for sha1. */
    val widthBytes: Int

    /** Digest over [payload], optionally mixed with a game specific [seed]. */
    fun compute(payload: ByteArray, seed: Long = 0L): ByteArray
}

object Crc32Checksum : ChecksumStrategy {
    override val id = "crc32"
    override val label = "crc32"
    override val widthBytes = 4

    override fun compute(payload: ByteArray, seed: Long): ByteArray {
        val crc = CRC32()
        if (seed != 0L) crc.update(leBytes(seed))
        crc.update(payload)
        return beBytes(crc.value)
    }
}

object Adler32Checksum : ChecksumStrategy {
    override val id = "adler32"
    override val label = "adler32"
    override val widthBytes = 4

    override fun compute(payload: ByteArray, seed: Long): ByteArray {
        val adler = Adler32()
        if (seed != 0L) adler.update(leBytes(seed))
        adler.update(payload)
        return beBytes(adler.value)
    }
}

class MessageDigestChecksum(
    override val id: String,
    override val label: String,
    override val widthBytes: Int,
    private val algorithm: String,
) : ChecksumStrategy {
    override fun compute(payload: ByteArray, seed: Long): ByteArray {
        val digest = MessageDigest.getInstance(algorithm)
        if (seed != 0L) digest.update(leBytes(seed))
        return digest.digest(payload)
    }
}

val Md5Checksum: ChecksumStrategy = MessageDigestChecksum("md5", "md5", 16, "MD5")
val Sha1Checksum: ChecksumStrategy = MessageDigestChecksum("sha1", "sha1", 20, "SHA-1")
val Sha256Checksum: ChecksumStrategy = MessageDigestChecksum("sha256", "sha256", 32, "SHA-256")

/**
 * Which bytes the digest covers.
 *
 * [start] is inclusive; [length] null means "to the end of the file". [exclude] carves out the
 * stored checksum itself, which is what most formats do.
 */
data class ByteRangeSpec(
    val start: Long = 0L,
    val length: Long? = null,
    val exclude: List<LongRange> = emptyList(),
    val seed: Long = 0L,
) {
    fun slice(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val from = start.coerceIn(0L, bytes.size.toLong()).toInt()
        val to = length?.let { (from + it).coerceAtMost(bytes.size.toLong()) }?.toInt() ?: bytes.size
        if (to <= from) return ByteArray(0)
        if (exclude.isEmpty()) return bytes.copyOfRange(from, to)

        val out = java.io.ByteArrayOutputStream(to - from)
        var index = from.toLong()
        while (index < to) {
            val skipped = exclude.firstOrNull { index in it }
            if (skipped != null) {
                index = skipped.last + 1
            } else {
                out.write(bytes[index.toInt()].toInt())
                index++
            }
        }
        return out.toByteArray()
    }
}

/**
 * A concrete checksum in a concrete file: algorithm + covered range + where the digest is stored.
 *
 * Binary formats store it at [storedAtOffset]; text formats store it in a field, so they use
 * [storedAtPath] and let the document write the value (the parser keeps the literal formatting).
 */
data class ChecksumSpec(
    val strategy: ChecksumStrategy,
    val range: ByteRangeSpec = ByteRangeSpec(),
    val storedAtOffset: Long? = null,
    val storedAtPath: String? = null,
    val endianness: Endianness = Endianness.LITTLE,
    /** Take only the first [storedWidthBytes] of the digest, e.g. 4 of an md5. */
    val storedWidthBytes: Int = strategy.widthBytes,
    /** Some formats store the digest as hex text instead of raw bytes. */
    val storedAsHexText: Boolean = false,
) {
    init {
        require(storedAtOffset != null || storedAtPath != null) {
            "a checksum needs either a byte offset or a field path"
        }
        require(storedWidthBytes in 1..strategy.widthBytes) {
            "stored width must be 1..${strategy.widthBytes}"
        }
    }

    /** Digest in the algorithm's natural (big endian) byte order, trimmed to the stored width. */
    fun digest(bytes: ByteArray): ByteArray {
        val payload = range.slice(bytes)
        val full = strategy.compute(payload, range.seed)
        return if (storedWidthBytes == strategy.widthBytes) full else full.copyOf(storedWidthBytes)
    }

    /** The digest as it sits in the file: byte swapped when the format stores it little endian. */
    fun storedDigest(bytes: ByteArray): ByteArray {
        val digest = digest(bytes)
        return if (endianness == Endianness.LITTLE && digest.size <= 8) digest.reversedArray() else digest
    }

    /** Natural-order hex, e.g. `cbf43926` for crc32 of "123456789". */
    fun digestHex(bytes: ByteArray): String = digest(bytes).joinToString("") { "%02x".format(it) }

    /** Numeric form of the digest (up to 8 bytes, big endian, unsigned). */
    fun digestNumber(bytes: ByteArray): Long {
        var value = 0L
        for (byte in digest(bytes).take(8)) value = (value shl 8) or (byte.toLong() and 0xff)
        return value
    }

    /** The value a text format should write into its checksum field. */
    fun digestText(bytes: ByteArray): String = if (storedAsHexText) digestHex(bytes) else digestNumber(bytes).toString()

    /** Returns [bytes] with the digest written at [storedAtOffset]. */
    fun apply(bytes: ByteArray): ByteArray {
        val offset = storedAtOffset ?: return bytes
        val stored = storedDigest(bytes)
        if (offset < 0 || offset + stored.size > bytes.size) {
            throw ChecksumException("checksum offset $offset is outside the ${bytes.size} byte file")
        }
        val out = bytes.copyOf()
        stored.forEachIndexed { i, byte -> out[(offset + i).toInt()] = byte }
        return out
    }

    /** True when the digest already stored in the file matches the payload it covers. */
    fun matches(bytes: ByteArray): Boolean {
        val offset = storedAtOffset ?: return true
        val stored = storedDigest(bytes)
        if (offset < 0 || offset + stored.size > bytes.size) return false
        for (i in stored.indices) {
            if (bytes[(offset + i).toInt()] != stored[i]) return false
        }
        return true
    }

    companion object {
        fun byId(id: String?): ChecksumStrategy? = Checksums.byId(id)
    }
}

object Checksums {
    val all: List<ChecksumStrategy> = listOf(Crc32Checksum, Adler32Checksum, Md5Checksum, Sha1Checksum, Sha256Checksum)

    fun byId(id: String?): ChecksumStrategy? = all.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
}

private fun beBytes(value: Long): ByteArray = byteArrayOf(
    ((value ushr 24) and 0xff).toByte(),
    ((value ushr 16) and 0xff).toByte(),
    ((value ushr 8) and 0xff).toByte(),
    (value and 0xff).toByte(),
)

private fun leBytes(value: Long): ByteArray = byteArrayOf(
    (value and 0xff).toByte(),
    ((value ushr 8) and 0xff).toByte(),
    ((value ushr 16) and 0xff).toByte(),
    ((value ushr 24) and 0xff).toByte(),
)
