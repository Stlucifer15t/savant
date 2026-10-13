package dev.savant.plugins

import dev.savant.codecs.ByteRangeSpec
import dev.savant.codecs.ChecksumSpec
import dev.savant.codecs.ChecksumStrategy
import dev.savant.codecs.Checksums
import dev.savant.codecs.CodecStrategy
import dev.savant.codecs.IdentityCodec
import dev.savant.codecs.XorCodec
import dev.savant.domain.model.Endianness
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.parser.SaveParser
import dev.savant.parsers.binary.BinarySaveParser

/**
 * Turns an accepted document into the pieces the parser registry needs.
 *
 * Nothing here trusts the document: a codec or checksum that cannot be resolved comes back as
 * null, and the caller refuses the document instead of reading a file with half a definition.
 */
object PluginRuntime {

    /** The codec a document declares, or null when the declaration cannot be honoured. */
    fun codecFor(document: PluginDocument): CodecStrategy? = when (document.codec) {
        PluginCodecKind.NONE -> IdentityCodec
        PluginCodecKind.GZIP -> dev.savant.codecs.GzipCodec
        PluginCodecKind.ZLIB -> dev.savant.codecs.ZlibCodec
        PluginCodecKind.XOR -> document.xorKey?.let { key -> hexToBytes(key)?.let { XorCodec(it) } }
        PluginCodecKind.LZSTRING, PluginCodecKind.AES -> null
    }

    /**
     * The checksum a document declares, addressed into the decoded payload.
     *
     * `range: [start, -1]` means "from `start` to the end", which is how a payload that carries its
     * own digest usually reads: everything after the header the digest lives in.
     */
    fun checksumFor(document: PluginDocument): ChecksumSpec? {
        val declared = document.checksum ?: return null
        val strategy: ChecksumStrategy = Checksums.byId(declared.strategy) ?: return null
        val offset = declared.storedAtOffset ?: return null
        return ChecksumSpec(
            strategy = strategy,
            range = ByteRangeSpec(start = declared.rangeStart, length = declared.rangeLength),
            storedAtOffset = offset,
            endianness = declared.endianness,
            storedWidthBytes = declared.storedWidth.coerceIn(1, strategy.widthBytes),
            storedAsHexText = declared.storedAsHexText,
        )
    }

    /**
     * The parser this document describes, or null when the runtime cannot build one.
     *
     * `binary` becomes an offset parser. `json`/`xml` do not need a new reader — the format's own
     * parser reads the file and the document contributes its `rename` map and its `fileMatch`
     * globs, which is why those two are accepted but never claim a file by themselves.
     */
    fun parserFor(document: PluginDocument): SaveParser? = when (document.parser) {
        PluginParserKind.BINARY -> {
            val codec = codecFor(document) ?: return null
            BinarySaveParser(document, codec, checksumFor(document))
        }

        PluginParserKind.JSON, PluginParserKind.XML -> null
        PluginParserKind.INI, PluginParserKind.RPGSAVE, PluginParserKind.SQLITE, PluginParserKind.PLIST -> null
    }

    /** True when the document contributes renames to an existing format's tree. */
    fun renamesFor(document: PluginDocument): Map<String, String> = document.rename

    fun formatOf(document: PluginDocument): SaveFormat = when (document.parser) {
        PluginParserKind.JSON -> SaveFormat.JSON
        PluginParserKind.XML -> SaveFormat.XML
        PluginParserKind.INI -> SaveFormat.INI
        PluginParserKind.RPGSAVE -> SaveFormat.RPGSAVE
        PluginParserKind.SQLITE -> SaveFormat.SQLITE
        PluginParserKind.PLIST -> SaveFormat.UNITY_PLIST
        PluginParserKind.BINARY -> SaveFormat.BINARY
    }

    fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.trim().removePrefix("0x").replace(" ", "").lowercase()
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        if (!clean.all { it.isDigit() || it in 'a'..'f' }) return null
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** `little`/`big` for the json writer, `le`/`be` for the data model. */
    fun endiannessOf(tag: String?): Endianness = Endianness.fromTag(tag)
}
