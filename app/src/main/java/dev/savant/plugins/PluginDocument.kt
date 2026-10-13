package dev.savant.plugins

import dev.savant.domain.model.Endianness
import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import dev.savant.parsers.json.arr
import dev.savant.parsers.json.bool
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/**
 * A game definition — the json a user imports to teach savant one game's save layout.
 *
 * The document is the whole plugin: there is no script, no expression language and no hook a
 * plugin could run. Everything here is data, which is what makes the runtime's promise true — a
 * plugin can describe a file, it can never execute anything, and the only thing it can cause is a
 * field mapping the user can see before it is accepted.
 *
 * Unknown keys are refused ([PluginReader.read] reports them) rather than ignored, so a document
 * that wanted to smuggle a `script` or an `url` fails loudly instead of silently doing less than
 * its author intended.
 */
data class PluginDocument(
    val gameName: String,
    val version: Int = CURRENT_VERSION,
    /** Globs matched against the file name during detection, e.g. `save*.dat`. */
    val fileMatch: List<String> = emptyList(),
    val parser: PluginParserKind = PluginParserKind.BINARY,
    val codec: PluginCodecKind = PluginCodecKind.NONE,
    /** Hex key for [PluginCodecKind.XOR], e.g. `1f2a3b`. */
    val xorKey: String? = null,
    val checksum: PluginChecksum? = null,
    val fields: List<PluginField> = emptyList(),
    /** Display names for cryptic paths, applied to the tree either way. */
    val rename: Map<String, String> = emptyMap(),
    val notes: String? = null,
) {

    /** Stable, file-name-safe id derived from the game name. */
    val slug: String get() = slugOf(gameName)

    val displayName: String get() = rename[gameName] ?: gameName

    /** One line for the formats screen. */
    val summary: String
        get() = buildString {
            append(parser.label)
            if (codec != PluginCodecKind.NONE) append(" · ${codec.label}")
            checksum?.let { append(" · ${it.strategy}") }
            append(" · ${fields.size} field mapping(s)")
        }

    companion object {
        const val CURRENT_VERSION = 1

        /** Hard cap: a definition this large is a mistake, not a save layout. */
        const val MAX_FIELDS = 2048

        fun slugOf(gameName: String): String =
            gameName.trim().lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(48)
                .ifEmpty { "plugin" }
    }
}

/**
 * Which reader the plugin wants.
 *
 * [supported] is about *this build*: a plugin that asks for sqlite is valid json with an honest
 * schema, and savant says so instead of pretending it can read the file. The reader installed
 * later (build step 9) is the same document, unchanged.
 */
enum class PluginParserKind(val id: String, val label: String, val supported: Boolean) {
    JSON("json", "json", true),
    XML("xml", "xml", true),
    BINARY("binary", "raw binary offsets", true),
    INI("ini", "ini", false),
    RPGSAVE("rpgsave", "rpg maker (lz-string + json)", false),
    SQLITE("sqlite", "sqlite", false),
    PLIST("plist", "plist", false),
    ;

    companion object {
        fun byId(id: String?): PluginParserKind? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

enum class PluginCodecKind(val id: String, val label: String, val supported: Boolean) {
    NONE("none", "no codec", true),
    GZIP("gzip", "gzip", true),
    ZLIB("zlib", "zlib / deflate", true),
    XOR("xor", "xor", true),
    LZSTRING("lzstring", "lz-string", false),
    AES("aes", "aes", false),
    ;

    companion object {
        fun byId(id: String?): PluginCodecKind? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

/** The byte types a binary mapping may declare. `str` needs a `length`. */
enum class PluginFieldType(val tag: String, val sizeBytes: Int?) {
    I8("i8", 1),
    U8("u8", 1),
    I16("i16", 2),
    U16("u16", 2),
    I32("i32", 4),
    U32("u32", 4),
    I64("i64", 8),
    U64("u64", 8),
    F32("f32", 4),
    F64("f64", 8),
    BOOL("bool", 1),
    STR("str", null),
    ;

    val signed: Boolean get() = tag.startsWith("i")

    companion object {
        fun byTag(tag: String?): PluginFieldType? =
            entries.firstOrNull { it.tag.equals(tag?.trim(), ignoreCase = true) }
    }
}

/**
 * One mapping from a path to bytes.
 *
 * For a scalar, [offset] is where the value lives. For an array (`party[*].hp`) [pointer] is the
 * offset of the `u32` that says how many elements there are, [stride] is the distance between
 * them, and the first element sits at [offset].
 */
data class PluginField(
    val path: String,
    val offset: Long? = null,
    val type: PluginFieldType = PluginFieldType.U32,
    val endianness: Endianness = Endianness.LITTLE,
    /** Byte width of a `str` field; text is written back into exactly this many bytes. */
    val length: Int? = null,
    val pointer: Long? = null,
    val stride: Int? = null,
) {
    val isArray: Boolean get() = path.contains(ELEMENT)

    /** `party[*].hp` -> path with the index filled in. */
    fun pathFor(index: Int): String = path.replace(ELEMENT, "[$index]")

    companion object {
        const val ELEMENT = "[*]"
    }
}

/**
 * Where a digest lives and what it covers.
 *
 * [range] and [storedAt] are `[start, length]` and `[offset, width]`, both addressed into the
 * *decoded* payload — the data the field mappings can reach. `-1` as the length means "to the end".
 */
data class PluginChecksum(
    val strategy: String,
    val range: List<Long> = listOf(0L, -1L),
    val storedAt: List<Long> = listOf(0L, 4L),
    val endianness: Endianness = Endianness.LITTLE,
    val storedAsHexText: Boolean = false,
) {
    val rangeStart: Long get() = range.getOrElse(0) { 0L }
    val rangeLength: Long? get() = range.getOrNull(1)?.takeIf { it >= 0 }
    val storedAtOffset: Long? get() = storedAt.getOrNull(0)
    val storedWidth: Int get() = (storedAt.getOrNull(1) ?: 4L).toInt()
}

/** What a read of a plugin json produced: the document, plus everything wrong with it. */
data class PluginRead(
    val document: PluginDocument,
    val problems: List<PluginProblem>,
) {
    val usable: Boolean get() = problems.none { it.severity == PluginSeverity.ERROR }
}

enum class PluginSeverity { ERROR, NOTE }

/** One thing wrong (or worth knowing) about a document, addressed by json path. */
data class PluginProblem(
    val path: String,
    val message: String,
    val severity: PluginSeverity = PluginSeverity.ERROR,
) {
    val text: String get() = if (path.isEmpty()) message else "$path: $message"

    companion object {
        fun of(path: String, message: String, severity: PluginSeverity = PluginSeverity.ERROR) =
            PluginProblem(path, message, severity)
    }
}

/**
 * Reads and writes the plugin json.
 *
 * Reading never throws: a document that cannot be understood comes back as a default-valued
 * [PluginDocument] with the reason in `problems`, because the import screen has to show the user
 * what was wrong with the file they picked.
 */
object PluginReader {

    private val ROOT_KEYS = setOf(
        "gameName", "version", "fileMatch", "parser", "codec", "xorKey", "checksum", "fields",
        "rename", "notes",
    )
    private val FIELD_KEYS =
        setOf("path", "offset", "type", "endianness", "length", "pointer", "stride")
    private val CHECKSUM_KEYS =
        setOf("strategy", "range", "storedAt", "endianness", "storedAsHexText")

    fun read(json: String): PluginRead {
        val problems = mutableListOf<PluginProblem>()
        val root = try {
            JsonPayload.read(json)
        } catch (error: Exception) {
            return PluginRead(
                PluginDocument(gameName = ""),
                listOf(PluginProblem.of("", "not valid json: ${error.message ?: "unreadable"}")),
            )
        }
        val obj = root as? Json.Obj
            ?: return PluginRead(
                PluginDocument(gameName = ""),
                listOf(PluginProblem.of("", "a plugin is a json object, not ${kindOf(root)}")),
            )

        obj.members.forEach { (key, _) ->
            if (key !in ROOT_KEYS) {
                problems += PluginProblem.of(
                    key,
                    "unknown key — a plugin describes data and cannot carry a list of instructions",
                )
            }
        }

        val gameName = obj.string("gameName")?.trim().orEmpty()
        if (gameName.isEmpty()) problems += PluginProblem.of("gameName", "a game name is required")

        val version = obj.long("version")?.toInt() ?: PluginDocument.CURRENT_VERSION
        if (version > PluginDocument.CURRENT_VERSION) {
            problems += PluginProblem.of(
                "version",
                "written for a newer savant (plugin v$version, this build reads v${PluginDocument.CURRENT_VERSION})",
            )
        }

        val parser = readEnum(
            obj.string("parser"),
            PluginParserKind.BINARY,
            "parser",
            problems,
        ) { PluginParserKind.byId(it) }
        val codec = readEnum(
            obj.string("codec"),
            PluginCodecKind.NONE,
            "codec",
            problems,
        ) { PluginCodecKind.byId(it) }

        val codecKnown = PluginCodecKind.byId(obj.string("codec")) != null || obj.string("codec") == null
        val xorKey = obj.string("xorKey")?.trim()?.lowercase()
        if (codec == PluginCodecKind.XOR && !codecKnown) {
            problems += PluginProblem.of("xorKey", "an xor codec needs its key as hex, e.g. \"1f2a3b\"")
        }
        if (codec != PluginCodecKind.XOR && xorKey != null) {
            problems += PluginProblem.of("xorKey", "only an xor codec uses a key", PluginSeverity.NOTE)
        }

        val fields = obj.list("fields").mapIndexedNotNull { index, raw ->
            readField(index, raw, problems)
        }
        if (fields.isEmpty() && parser == PluginParserKind.BINARY) {
            problems += PluginProblem.of(
                "fields",
                "a binary plugin needs at least one field mapping, or savant has nothing to read",
            )
        }
        if (fields.size > PluginDocument.MAX_FIELDS) {
            problems += PluginProblem.of("fields", "too many mappings (${fields.size}, max ${PluginDocument.MAX_FIELDS})")
        }

        val rename = obj.obj("rename")?.members
            ?.mapNotNull { (path, value) -> (value as? Json.Str)?.let { path to it.value } }
            ?.toMap()
            ?: emptyMap()

        return PluginRead(
            document = PluginDocument(
                gameName = gameName,
                version = version,
                fileMatch = obj.strings("fileMatch").map { it.trim() }.filter { it.isNotEmpty() },
                parser = parser,
                codec = codec,
                xorKey = xorKey,
                checksum = obj.obj("checksum")?.let { readChecksum(it, problems) },
                fields = fields,
                rename = rename,
                notes = obj.string("notes"),
            ),
            problems = problems,
        )
    }

    fun write(document: PluginDocument): String = JsonPayload.write(toJson(document))

    /** The same document, indented — what the import screen shows before anything is accepted. */
    fun writePretty(document: PluginDocument): String = JsonPayload.writePretty(toJson(document))

    private fun toJson(document: PluginDocument): Json.Obj {
        val members = mutableListOf<Pair<String, Json>>(
            "gameName" to str(document.gameName),
            "version" to num(document.version),
            "fileMatch" to arr(document.fileMatch.map { match: String -> str(match) }),
            "parser" to str(document.parser.id),
            "codec" to str(document.codec.id),
            "xorKey" to str(document.xorKey),
        )
        val checksum = document.checksum
        members += "checksum" to if (checksum == null) {
            Json.Null
        } else {
            obj(
                "strategy" to str(checksum.strategy),
                "range" to arr(checksum.range.map { part: Long -> num(part) }),
                "storedAt" to arr(checksum.storedAt.map { part: Long -> num(part) }),
                "endianness" to str(checksum.endianness.tag),
                "storedAsHexText" to bool(checksum.storedAsHexText),
            )
        }
        members += "fields" to arr(document.fields.map { field ->
            obj(
                "path" to str(field.path),
                "offset" to num(field.offset),
                "type" to str(field.type.tag),
                "endianness" to str(field.endianness.tag),
                "length" to num(field.length),
                "pointer" to num(field.pointer),
                "stride" to num(field.stride),
            )
        })
        members += "rename" to obj(document.rename.map { entry -> entry.key to str(entry.value) })
        members += "notes" to str(document.notes)
        return obj(members)
    }

    private fun readField(
        index: Int,
        raw: Json,
        problems: MutableList<PluginProblem>,
    ): PluginField? {
        val where = "fields[$index]"
        val obj = raw as? Json.Obj
        if (obj == null) {
            problems += PluginProblem.of(where, "a field mapping is a json object")
            return null
        }
        obj.members.forEach { (key, _) ->
            if (key !in FIELD_KEYS) {
                problems += PluginProblem.of("$where.$key", "unknown key in a field mapping")
            }
        }
        val path = obj.string("path")?.trim().orEmpty()
        if (path.isEmpty()) {
            problems += PluginProblem.of("$where.path", "a path is required")
            return null
        }
        val type = readEnum(obj.string("type"), PluginFieldType.U32, "$where.type", problems) {
            PluginFieldType.byTag(it)
        }
        val endianness = readEndianness(obj.string("endianness"), "$where.endianness", problems)
        val offset = obj.long("offset")
        if (offset == null) problems += PluginProblem.of("$where.offset", "a byte offset is required")
        val length = obj.long("length")?.toInt()
        if (type == PluginFieldType.STR && (length == null || length <= 0)) {
            problems += PluginProblem.of(
                "$where.length",
                "a str field needs a byte length, so an edit knows how much room it has",
            )
        }
        val isArray = path.contains(PluginField.ELEMENT)
        val pointer = obj.long("pointer")
        val stride = obj.long("stride")?.toInt()
        if (isArray) {
            if (pointer == null) {
                problems += PluginProblem.of("$where.pointer", "an array needs the offset of its element count")
            }
            if (stride == null || stride <= 0) {
                problems += PluginProblem.of("$where.stride", "an array needs a positive stride")
            }
            if (type.sizeBytes == null && length == null) {
                problems += PluginProblem.of("$where.type", "an array element needs a fixed width")
            }
        } else if (pointer != null || stride != null) {
            problems += PluginProblem.of(
                where,
                "pointer and stride only apply to an array path like \"party[*].hp\"",
            )
        }
        return PluginField(
            path = path,
            offset = offset,
            type = type,
            endianness = endianness,
            length = length,
            pointer = pointer,
            stride = stride,
        )
    }

    private fun readChecksum(obj: Json.Obj, problems: MutableList<PluginProblem>): PluginChecksum {
        obj.members.forEach { (key, _) ->
            if (key !in CHECKSUM_KEYS) {
                problems += PluginProblem.of("checksum.$key", "unknown key in a checksum")
            }
        }
        val strategy = obj.string("strategy")?.trim()?.lowercase().orEmpty()
        if (strategy.isEmpty()) problems += PluginProblem.of("checksum.strategy", "a strategy is required")
        return PluginChecksum(
            strategy = strategy,
            range = obj.list("range").mapNotNull { (it as? Json.Num)?.asLong },
            storedAt = obj.list("storedAt").mapNotNull { (it as? Json.Num)?.asLong },
            endianness = readEndianness(obj.string("endianness"), "checksum.endianness", problems),
            storedAsHexText = obj.bool("storedAsHexText") ?: false,
        )
    }

    private fun readEndianness(
        id: String?,
        path: String,
        problems: MutableList<PluginProblem>,
    ): Endianness {
        if (id == null) return Endianness.LITTLE
        return when (id.trim().lowercase()) {
            "little", "le" -> Endianness.LITTLE
            "big", "be" -> Endianness.BIG
            else -> {
                problems += PluginProblem.of(path, "endianness is \"little\" or \"big\", not \"$id\"")
                Endianness.LITTLE
            }
        }
    }

    private fun <T> readEnum(
        id: String?,
        fallback: T,
        path: String,
        problems: MutableList<PluginProblem>,
        lookup: (String) -> T?,
    ): T {
        if (id == null) return fallback
        return lookup(id) ?: run {
            problems += PluginProblem.of(path, "unknown value \"$id\"")
            fallback
        }
    }

    private fun kindOf(value: Json): String = when (value) {
        Json.Null -> "null"
        is Json.Bool -> "a boolean"
        is Json.Num -> "a number"
        is Json.Str -> "a string"
        is Json.Arr -> "an array"
        is Json.Obj -> "an object"
    }
}
