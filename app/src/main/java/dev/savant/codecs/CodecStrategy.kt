package dev.savant.codecs

import dev.savant.domain.model.Confidence
import dev.savant.parsers.FormatDetector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

/** The payload could not be decoded, so the file must be opened read-only. */
class CodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Payload (de)coding hook.
 *
 * A parser wraps its byte handling in a codec so the same JSON/XML/binary logic works for plain,
 * compressed, lz-string or encrypted saves. `decode` turns file bytes into the bytes the parser
 * understands; `encode` does the inverse on export.
 *
 * Implemented now: [IdentityCodec], [GzipCodec], [ZlibCodec], [XorCodec].
 * LzStringCodec (rpg maker) and AesCodec arrive with their formats — the interface does not change.
 */
interface CodecStrategy {

    /** Stable id used in plugin json, e.g. `gzip`. */
    val id: String

    val label: String

    fun decode(bytes: ByteArray): ByteArray

    fun encode(bytes: ByteArray): ByteArray

    /** How well this codec explains the raw bytes; null when it clearly does not apply. */
    fun sniff(bytes: ByteArray): Confidence? = null

    /** Codecs that change length are risky: an unidentified one means read-only. */
    val changesLength: Boolean get() = true
}

/** No transformation. The default for every text format. */
object IdentityCodec : CodecStrategy {
    override val id = "none"
    override val label = "no codec"
    override fun decode(bytes: ByteArray) = bytes
    override fun encode(bytes: ByteArray) = bytes
    override val changesLength = false
}

object GzipCodec : CodecStrategy {
    override val id = "gzip"
    override val label = "gzip"

    override fun decode(bytes: ByteArray): ByteArray = try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    } catch (error: Exception) {
        throw CodecException("gzip payload is truncated or corrupt", error)
    }

    override fun encode(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(bytes) }
            out.toByteArray()
        }

    override fun sniff(bytes: ByteArray): Confidence? =
        if (FormatDetector.hasGzipMagic(bytes)) Confidence.certain("gzip magic 1f 8b") else null
}

object ZlibCodec : CodecStrategy {
    override val id = "zlib"
    override val label = "zlib / deflate"

    override fun decode(bytes: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw CodecException("zlib stream is truncated or needs a preset dictionary")
                }
                val read = inflater.inflate(buffer)
                if (read == 0 && inflater.needsInput()) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        } catch (error: CodecException) {
            throw error
        } catch (error: Exception) {
            throw CodecException("zlib payload could not be inflated", error)
        } finally {
            inflater.end()
        }
    }

    override fun encode(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                out.write(buffer, 0, written)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    override fun sniff(bytes: ByteArray): Confidence? =
        if (FormatDetector.hasZlibMagic(bytes)) Confidence.of(0.75f, "zlib header 78 xx") else null
}

/**
 * Repeating-key xor. Some saves obfuscate with a fixed key; the key comes from a plugin or from
 * the user, never from a model.
 */
class XorCodec(private val key: ByteArray) : CodecStrategy {
    init {
        require(key.isNotEmpty()) { "xor key must not be empty" }
    }

    override val id = "xor"
    override val label = "xor (${key.size} byte key)"

    override fun decode(bytes: ByteArray): ByteArray = apply(bytes)

    override fun encode(bytes: ByteArray): ByteArray = apply(bytes)

    override val changesLength = false

    private fun apply(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) out[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
        return out
    }

    override fun equals(other: Any?): Boolean = other is XorCodec && other.key.contentEquals(key)
    override fun hashCode(): Int = key.contentHashCode()

    companion object {
        /** Parses `1f8b` / `1f,8b` / `0x1f 0x8b` style keys from a plugin file. */
        fun parseKey(text: String): ByteArray =
            text.split(Regex("[\\s,;]+"))
                .filter { it.isNotBlank() }
                .map { token ->
                    val cleaned = token.removePrefix("0x").removePrefix("0X")
                    (cleaned.toIntOrNull(16) ?: throw CodecException("xor key byte '$token' is not hex"))
                        .toByte()
                }
                .toByteArray()
                .also { if (it.isEmpty()) throw CodecException("xor key is empty") }
    }
}

/** Registry used by plugin loading and by the settings/format screens. */
object Codecs {
    val all: List<CodecStrategy> = listOf(IdentityCodec, GzipCodec, ZlibCodec)

    fun byId(id: String?): CodecStrategy? =
        all.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: if (id.isNullOrBlank() || id == "none") IdentityCodec else null

    fun sniff(bytes: ByteArray): List<Pair<CodecStrategy, Confidence>> =
        all.mapNotNull { codec -> codec.sniff(bytes)?.let { codec to it } }
}
