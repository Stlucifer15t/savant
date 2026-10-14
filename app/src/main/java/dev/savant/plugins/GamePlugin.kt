package dev.savant.plugins

import dev.savant.codecs.ByteRangeSpec
import dev.savant.codecs.ChecksumSpec
import dev.savant.codecs.Checksums
import dev.savant.domain.model.Endianness
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.SaveFormat

/** How a plugin reads a file: raw offsets, or paths inside a format savant already parses. */
enum class PluginParserKind(val id: String, val label: String) {
    /** Offsets into the decoded payload. The only way to reach a format savant cannot parse. */
    BINARY("binary", "binary offsets"),

    /** Paths inside a json file, mapped onto names, categories and limits. */
    JSON("json", "json paths"),

    /** Paths inside an xml or plist file. */
    XML("xml", "xml paths"),

    /** `section.key` paths inside an ini, cfg or properties file. */
    INI("ini", "ini keys"),
    ;

    val baseFormat: SaveFormat
        get() = when (this) {
            BINARY -> SaveFormat.BINARY
            JSON -> SaveFormat.JSON
            XML -> SaveFormat.XML
            INI -> SaveFormat.INI
        }

    companion object {
        fun fromId(id: String?): PluginParserKind =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: BINARY
    }
}

/** Where a plugin came from, which decides whether savant may overwrite it without asking. */
enum class PluginSource(val label: String) {
    /** Imported by the user from a json file. */
    IMPORTED("imported"),

    /** Built from offsets named in the finder. Editable, and exported like any other. */
    FROM_FINDER("from finder"),

    /** Shipped with savant. */
    BUILTIN("built in"),
    ;

    companion object {
        fun fromName(name: String?): PluginSource =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: IMPORTED
    }
}

/**
 * A checksum a plugin describes.
 *
 * Mirrors the documented json: `{ "strategy": "crc32", "range": [4, -1], "storedAt": [0, 4] }`,
 * where `range` is `[start, length]` and a negative length means "to the end of the payload", and
 * `storedAt` is `[offset, width]`. The stored bytes are excluded from the range automatically when
 * they fall inside it, which is what almost every format does.
 */
data class PluginChecksum(
    val strategy: String,
    val rangeStart: Long = 0L,
    /** Length of the covered range; negative means "to the end of the payload". */
    val rangeLength: Long = -1L,
    val storedAt: Long = 0L,
    val storedWidth: Int? = null,
    val endianness: Endianness? = null,
    val asHexText: Boolean = false,
    val seed: Long = 0L,
) {
    /** Null when the strategy is not one savant implements, which the codec reports as a warning. */
    fun toSpec(defaultEndianness: Endianness): ChecksumSpec? {
        val algorithm = Checksums.byId(strategy) ?: return null
        val width = (storedWidth ?: algorithm.widthBytes).coerceIn(1, algorithm.widthBytes)
        val length = if (rangeLength < 0) null else rangeLength
        val storedRange = storedAt until storedAt + width
        val insideRange = storedAt >= rangeStart && (length == null || storedAt < rangeStart + length)
        val exclude = if (insideRange) {
            listOf(storedRange)
        } else {
            emptyList()
        }
        return ChecksumSpec(
            strategy = algorithm,
            range = ByteRangeSpec(start = rangeStart, length = length, exclude = exclude, seed = seed),
            storedAtOffset = storedAt,
            endianness = endianness ?: defaultEndianness,
            storedWidthBytes = width,
            storedAsHexText = asHexText,
        )
    }

    val label: String
        get() = buildString {
            append(strategy)
            if (rangeStart != 0L || rangeLength >= 0) append(" over [$rangeStart, $rangeLength]")
            append(" stored at $storedAt")
        }
}

/**
 * One field a plugin knows about.
 *
 * Three shapes, and a mapping has to be exactly one of them:
 * - `offset` — a fixed position in the decoded payload;
 * - `pointer` + `stride` — an array of records: read a base offset at `pointer`, then walk `count`
 *   records of `stride` bytes, taking the field at `recordOffset` inside each. `[*]` in the path is
 *   replaced by the index;
 * - `path` with a text parser kind — the field is looked up by path in the parsed tree, and the
 *   mapping only adds a name, a category and limits.
 */
data class FieldMapping(
    val path: String,
    val type: String,
    val displayName: String? = null,
    val category: String? = null,
    val offset: Int? = null,
    /** Byte width of a `str` field, and of a `bool` that is not one byte. Required for strings. */
    val length: Int? = null,
    val pointer: Int? = null,
    val stride: Int? = null,
    val count: Int? = null,
    val recordOffset: Int = 0,
    val pointerType: String = "u32",
    /** Subtract this from the value read at [pointer] when a game stores a load address, not an offset. */
    val pointerBase: Long = 0L,
    val endianness: Endianness? = null,
    val min: Double? = null,
    val max: Double? = null,
    val editable: Boolean = true,
    val notes: String = "",
) {
    val fieldType: FieldType? get() = FieldType.fromTag(type)

    val declaredCategory: FieldCategory? get() = FieldCategory.entries.firstOrNull {
        it.name.equals(category?.trim(), ignoreCase = true) || it.label.equals(category?.trim(), ignoreCase = true)
    }

    val isArray: Boolean get() = pointer != null

    /** The name shown in the field list: what the plugin said, or the last path segment tidied up. */
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('.')

    val isPositional: Boolean get() = offset != null || pointer != null
}

/**
 * One game, described in json.
 *
 * A plugin is data and nothing else: no code, no script, no download. It says which files it claims,
 * how the payload is wrapped, where the checksum lives, and which bytes are which fields — and savant
 * does the reading and writing with the same deterministic code it uses for its own parsers.
 */
data class GamePlugin(
    val id: String,
    val gameName: String,
    val parser: PluginParserKind = PluginParserKind.BINARY,
    val version: Int = SCHEMA_VERSION,
    val notes: String = "",
    val fileMatch: List<String> = emptyList(),
    /** Hex bytes the payload has to start with, e.g. `53 56 53 31`. Optional. */
    val magic: String = "",
    val codec: String = "none",
    /** Hex key, only for `codec: "xor"`. */
    val xorKey: String = "",
    val endianness: Endianness = Endianness.LITTLE,
    val checksum: PluginChecksum? = null,
    /**
     * Display names for cryptic keys, applied by path mappings: `{"gld": "Gold"}`.
     *
     * The cheap half of a plugin — no offsets, no types, just "this key means this". Savant marks the
     * result as a guess, because it is somebody's note and not the file's own claim.
     */
    val rename: Map<String, String> = emptyMap(),
    val fields: List<FieldMapping> = emptyList(),
    val source: PluginSource = PluginSource.IMPORTED,
    val installedAt: Long = System.currentTimeMillis(),
) {
    val format: SaveFormat get() = parser.baseFormat

    /** How many fields a parse will produce, counting `[*]` as one until the file says otherwise. */
    val mappingCount: Int get() = fields.size

    val positionalFields: List<FieldMapping> get() = fields.filter { it.isPositional }

    val summary: String
        get() = buildString {
            append(parser.id)
            append(" · ")
            append(fields.size)
            append(" field")
            if (fields.size != 1) append("s")
            if (codec != "none") append(" · $codec")
            if (checksum != null) append(" · ${checksum.strategy}")
            if (fileMatch.isNotEmpty()) append(" · ${fileMatch.joinToString(" ")}")
        }

    companion object {
        /** Bumped when the json shape changes. A higher version in a file is refused, not guessed at. */
        const val SCHEMA_VERSION = 1
    }
}
