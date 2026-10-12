package dev.savant.parsers

import dev.savant.domain.model.Confidence
import dev.savant.domain.model.SaveFormat

/**
 * Cheap, allocation-light evidence about raw bytes.
 *
 * Parsers use these helpers to score themselves, and the chat uses [guess] to describe a file
 * that no parser claimed ("looks gzip compressed — codec not identified").
 */
object FormatDetector {

    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val GZIP_MAGIC = byteArrayOf(0x1f.toByte(), 0x8b.toByte())
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    private val BPLIST_MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)
    private val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

    /** First non whitespace byte offset, skipping a utf-8 bom. */
    fun firstSignificantIndex(bytes: ByteArray, limit: Int = 512): Int {
        var i = 0
        if (bytes.size >= 3 && bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2]) i = 3
        val end = minOf(bytes.size, limit)
        while (i < end && bytes[i].isJsonWhitespace()) i++
        return i
    }

    fun startsWith(bytes: ByteArray, prefix: ByteArray, offset: Int = 0): Boolean {
        if (offset + prefix.size > bytes.size) return false
        for (i in prefix.indices) if (bytes[offset + i] != prefix[i]) return false
        return true
    }

    fun hasSqliteMagic(bytes: ByteArray): Boolean = startsWith(bytes, SQLITE_MAGIC)
    fun hasGzipMagic(bytes: ByteArray): Boolean = startsWith(bytes, GZIP_MAGIC)
    fun hasZipMagic(bytes: ByteArray): Boolean = startsWith(bytes, ZIP_MAGIC)
    fun hasBinaryPlistMagic(bytes: ByteArray): Boolean = startsWith(bytes, BPLIST_MAGIC)
    fun hasPngMagic(bytes: ByteArray): Boolean = startsWith(bytes, PNG_MAGIC)
    fun hasUtf8Bom(bytes: ByteArray): Boolean = startsWith(bytes, UTF8_BOM)

    /** zlib headers: 0x78 followed by 0x01/0x5e/0x9c/0xda (and the rare 0x2c/0x6c/0xae/0xee). */
    fun hasZlibMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 2 || bytes[0] != 0x78.toByte()) return false
        return bytes[1] in listOf(0x01, 0x5e, 0x9c, 0xda, 0x2c, 0x6c, 0xae, 0xee)
            .map { it.toByte() }
    }

    fun looksLikeJson(bytes: ByteArray): Boolean {
        val i = firstSignificantIndex(bytes)
        if (i >= bytes.size) return false
        val c = bytes[i].toInt().toChar()
        if (c != '{' && c != '[') return false
        val tail = lastSignificantChar(bytes)
        val closing = if (c == '{') '}' else ']'
        return tail == closing || tail == null
    }

    fun looksLikeXml(bytes: ByteArray): Boolean {
        val i = firstSignificantIndex(bytes)
        if (i >= bytes.size || bytes[i] != '<'.code.toByte()) return false
        val head = String(bytes, i, minOf(256, bytes.size - i), Charsets.UTF_8)
        return head.startsWith("<?xml", true) ||
            head.startsWith("<!DOCTYPE", true) ||
            head.startsWith("<!--") ||
            Regex("^<[A-Za-z_][\\w.:-]*[\\s>/]").containsMatchIn(head)
    }

    fun isApplePlist(bytes: ByteArray): Boolean {
        if (hasBinaryPlistMagic(bytes)) return true
        if (!looksLikeXml(bytes)) return false
        val head = String(bytes, 0, minOf(1024, bytes.size), Charsets.UTF_8)
        return head.contains("<!DOCTYPE plist", true) || head.contains("<plist", true)
    }

    /** `key = value` lines with optional `[section]` headers, and no xml/json structure. */
    fun looksLikeIni(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (looksLikeJson(bytes) || looksLikeXml(bytes)) return false
        val text = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith(";") && !it.startsWith("#") }.take(40).toList()
        if (lines.size < 2) return false
        val pairs = lines.count { line ->
            val eq = line.indexOf('=')
            val colon = line.indexOf(':')
            (eq > 0 && line.substring(0, eq).trim().isIniKey()) || (colon > 0 && line.substring(0, colon).trim().isIniKey())
        }
        val sections = lines.count { it.startsWith("[") && it.endsWith("]") }
        return pairs + sections >= lines.size * 3 / 4
    }

    /** Base64-ish text, which is what lz-string compressed rpg saves look like. */
    fun looksLikeBase64Text(bytes: ByteArray): Boolean {
        if (bytes.size < 32) return false
        val sample = bytes.take(minOf(bytes.size, 4096))
        val allowed = sample.count { b ->
            val c = b.toInt().toChar()
            c.isLetterOrDigit() || c == '+' || c == '/' || c == '=' || c == '-' || c == '_' || c == '\n' || c == '\r'
        }
        return allowed > sample.size * 97 / 100
    }

    /** Fraction of bytes that are printable ascii or common whitespace, 0f..1f. */
    fun printableRatio(bytes: ByteArray): Float {
        if (bytes.isEmpty()) return 0f
        val sample = if (bytes.size > 8192) bytes.copyOfRange(0, 8192) else bytes
        var printable = 0
        for (b in sample) {
            val v = b.toInt() and 0xff
            if (v in 0x20..0x7e || v == 0x09 || v == 0x0a || v == 0x0d) printable++
        }
        return printable.toFloat() / sample.size
    }

    fun looksLikeText(bytes: ByteArray): Boolean = printableRatio(bytes) >= 0.95f

    fun lastSignificantChar(bytes: ByteArray): Char? {
        var i = bytes.size - 1
        while (i >= 0 && bytes[i].isJsonWhitespace()) i--
        return if (i < 0) null else (bytes[i].toInt() and 0xff).toChar()
    }

    /**
     * Best-effort description used when nothing can be opened:
     * the chat says what it sees and offers read-only inspection instead of guessing.
     */
    fun guess(bytes: ByteArray, fileName: String?): List<Pair<SaveFormat, Confidence>> {
        val out = mutableListOf<Pair<SaveFormat, Confidence>>()
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()

        SaveFormat.fromExtension(ext)?.let { fromExt ->
            out += fromExt to Confidence.of(0.35f, "extension .$ext")
        }
        if (hasSqliteMagic(bytes)) out += SaveFormat.SQLITE to Confidence.certain("sqlite header at offset 0")
        if (isApplePlist(bytes)) out += SaveFormat.UNITY_PLIST to Confidence.of(0.9f, "apple plist container")
        if (looksLikeJson(bytes)) out += SaveFormat.JSON to Confidence.of(0.95f, "starts with a json value")
        if (looksLikeXml(bytes)) out += SaveFormat.XML to Confidence.of(0.9f, "starts with an xml declaration or tag")
        if (looksLikeIni(bytes)) out += SaveFormat.INI to Confidence.of(0.7f, "section/key=value lines")
        if (ext == "rpgsave" || ext == "rpgdata") {
            out += SaveFormat.RPGSAVE to Confidence.of(0.85f, "extension .$ext")
        }
        if (out.none { it.first != SaveFormat.UNKNOWN } && !looksLikeText(bytes)) {
            out += SaveFormat.BINARY to Confidence.of(
                0.4f,
                "not text (${(printableRatio(bytes) * 100).toInt()}% printable), needs an offset plugin",
            )
        }
        return out.sortedByDescending { it.second.score }
    }

    /** What the chat says when a file cannot be opened for editing. */
    fun describeBlocker(bytes: ByteArray, fileName: String?): String {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        return when {
            hasGzipMagic(bytes) -> "the payload is gzip compressed and savant has no codec mapping for it yet"
            hasZlibMagic(bytes) -> "the payload is zlib compressed and savant has no codec mapping for it yet"
            hasZipMagic(bytes) -> "this is a zip/archive container, not a single save file"
            hasPngMagic(bytes) -> "this is a png image, not a save file"
            hasSqliteMagic(bytes) -> "sqlite support lands in a later build; the file was not modified"
            ext == "rpgsave" -> "rpg maker support (lz-string + json) lands in a later build; the file was not modified"
            !looksLikeText(bytes) -> "this looks like raw binary. without an offset plugin savant cannot name any field safely"
            else -> "the structure is not one savant can rebuild faithfully"
        }
    }

    private fun Byte.isJsonWhitespace(): Boolean {
        val c = toInt().toChar()
        return c == ' ' || c == '\n' || c == '\r' || c == '\t'
    }

    private fun String.isIniKey(): Boolean =
        isNotEmpty() && length <= 64 && all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
}
