package dev.savant.plugins

import dev.savant.codecs.CodecStrategy
import dev.savant.codecs.Codecs
import dev.savant.codecs.XorCodec
import dev.savant.domain.model.Endianness
import dev.savant.domain.model.FieldType
import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import dev.savant.parsers.json.arr
import dev.savant.parsers.json.bool
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/** What came out of an import: one plugin, or the reasons it was refused. */
sealed interface PluginDecode {
    data class Ok(val plugin: GamePlugin) : PluginDecode
    data class Invalid(val reasons: List<String>) : PluginDecode {
        val headline: String get() = "that is not a savant plugin"
    }
}

/**
 * Plugin json ⇄ [GamePlugin].
 *
 * The shape is the one the formats screen has always documented, so a file written against that
 * documentation imports without surprises. Decoding is strict about the things that would produce a
 * wrong edit — an unknown type, a field with no position, a codec savant does not have — and lenient
 * about everything else, because a plugin written by hand should not need every optional key.
 *
 * Encoding is pretty printed on purpose: a plugin is a document a person reads, edits and shares.
 */
object PluginCodec {

    fun decode(text: String, idFallback: String? = null): PluginDecode {
        val json = runCatching { JsonPayload.read(text) }.getOrNull() as? Json.Obj
            ?: return PluginDecode.Invalid(listOf("the file is not a json object"))

        val reasons = mutableListOf<String>()
        val schema = json.int("schema") ?: GamePlugin.SCHEMA_VERSION
        if (schema > GamePlugin.SCHEMA_VERSION) {
            return PluginDecode.Invalid(listOf("schema $schema is newer than this savant (${GamePlugin.SCHEMA_VERSION})"))
        }

        val gameName = json.string("gameName")?.trim().orEmpty()
        if (gameName.isEmpty()) reasons += "gameName is required"

        val parserId = json.string("parser")?.trim()?.lowercase() ?: PluginParserKind.BINARY.id
        val parser = PluginParserKind.entries.firstOrNull { it.id == parserId }
        if (parser == null) {
            reasons += "parser '$parserId' is not one savant implements (${PluginParserKind.entries.joinToString { it.id }})"
        }

        val codecId = json.string("codec")?.trim()?.lowercase().orEmpty().ifEmpty { "none" }
        val xorKey = json.string("xorKey").orEmpty()
        when {
            codecId == "xor" && parseHex(xorKey).isEmpty() -> reasons += "codec xor needs a hex xorKey"
            codecId != "xor" && Codecs.byId(codecId) == null -> reasons += "codec '$codecId' is not one savant implements"
        }

        val checksum = json.obj("checksum")?.let { decodeChecksum(it, reasons) }

        val fields = json.list("fields").mapIndexed { index, item -> decodeField(item, index, parser ?: PluginParserKind.BINARY, reasons) }
        val usable = fields.filterNotNull()
        val rename = json.obj("rename")?.members?.mapNotNull { (key, value) ->
            value.asText?.takeIf { it.isNotBlank() }?.let { key to it }
        }?.toMap() ?: emptyMap()
        // A plugin that only renames keys is still a plugin: names are half of what makes a save readable.
        if (usable.isEmpty() && rename.isEmpty()) reasons += "at least one field or rename entry is required"
        val duplicates = usable.groupingBy { it.path }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) reasons += "duplicate field path(s): ${duplicates.joinToString()}"

        if (reasons.isNotEmpty()) return PluginDecode.Invalid(reasons)

        return PluginDecode.Ok(
            GamePlugin(
                id = json.string("id")?.takeIf { it.isNotBlank() } ?: idFallback ?: newId(gameName),
                gameName = gameName,
                parser = parser ?: PluginParserKind.BINARY,
                version = schema,
                notes = json.string("notes").orEmpty(),
                fileMatch = json.strings("fileMatch").filter { it.isNotBlank() },
                magic = json.string("magic").orEmpty(),
                codec = codecId,
                xorKey = xorKey,
                endianness = Endianness.fromTag(json.string("endianness")),
                checksum = checksum,
                rename = rename,
                fields = usable,
                source = PluginSource.fromName(json.string("source")),
                installedAt = json.long("installedAt") ?: System.currentTimeMillis(),
            ),
        )
    }

    /** Decodes a list document, keeping the plugins that are valid and reporting the rest. */
    fun decodeAll(text: String?): Pair<List<GamePlugin>, List<String>> {
        if (text.isNullOrBlank()) return emptyList<GamePlugin>() to emptyList<String>()
        val json = runCatching { JsonPayload.read(text) }.getOrNull()
        val items: List<Json> = when (json) {
            is Json.Arr -> json.items
            is Json.Obj -> json.list("plugins").ifEmpty { listOf<Json>(json) }
            else -> emptyList()
        }
        if (items.isEmpty()) return emptyList<GamePlugin>() to listOf("no plugin found in that file")
        val good = mutableListOf<GamePlugin>()
        val bad = mutableListOf<String>()
        items.forEachIndexed { index, item ->
            when (val result = decode(JsonPayload.write(item))) {
                is PluginDecode.Ok -> good += result.plugin
                is PluginDecode.Invalid -> bad += "plugin ${index + 1}: ${result.reasons.joinToString("; ")}"
            }
        }
        return good to bad
    }

    fun encode(plugin: GamePlugin): String = JsonPayload.writePretty(encodePlugin(plugin))

    fun encodeAll(plugins: List<GamePlugin>): String =
        JsonPayload.writePretty(obj("schema" to num(GamePlugin.SCHEMA_VERSION), "plugins" to arr(plugins.map { encodePlugin(it) })))

    fun encodePlugin(plugin: GamePlugin): Json.Obj {
        val base = obj(
            "schema" to num(plugin.version),
            "id" to str(plugin.id),
            "gameName" to str(plugin.gameName),
            "parser" to str(plugin.parser.id),
            "fileMatch" to arr(plugin.fileMatch.map { str(it) }),
            "fields" to arr(plugin.fields.map { encodeField(it) }),
        )
        var out = base
        if (plugin.codec != "none") out = out.with("codec", str(plugin.codec))
        if (plugin.codec == "xor") out = out.with("xorKey", str(plugin.xorKey))
        if (plugin.endianness != Endianness.LITTLE) out = out.with("endianness", str(plugin.endianness.tag))
        if (plugin.magic.isNotBlank()) out = out.with("magic", str(plugin.magic))
        if (plugin.notes.isNotBlank()) out = out.with("notes", str(plugin.notes))
        if (plugin.source != PluginSource.IMPORTED) out = out.with("source", str(plugin.source.name))
        plugin.checksum?.let { out = out.with("checksum", encodeChecksum(it)) }
        if (plugin.rename.isNotEmpty()) {
            out = out.with("rename", obj(plugin.rename.map { (key, value) -> key to str(value) }))
        }
        return out
    }

    private fun encodeChecksum(checksum: PluginChecksum): Json.Obj {
        val base = obj(
            "strategy" to str(checksum.strategy),
            "range" to arr(num(checksum.rangeStart), num(checksum.rangeLength)),
            "storedAt" to arr(num(checksum.storedAt), num(checksum.storedWidth ?: 0)),
        )
        var out = base
        if (checksum.endianness != null) out = out.with("endianness", str(checksum.endianness.tag))
        if (checksum.asHexText) out = out.with("asHexText", bool(true))
        if (checksum.seed != 0L) out = out.with("seed", num(checksum.seed))
        return out
    }

    private fun decodeChecksum(json: Json.Obj, reasons: MutableList<String>): PluginChecksum? {
        val strategy = json.string("strategy")?.trim()?.lowercase().orEmpty()
        if (strategy.isEmpty()) {
            reasons += "checksum.strategy is required"
            return null
        }
        val range = json.list("range").mapNotNull { (it as? Json.Num)?.asLong }
        val storedAt = json.list("storedAt").mapNotNull { (it as? Json.Num)?.asLong }
        val offset = storedAt.getOrNull(0) ?: json.long("storedAt")
        if (offset == null) {
            reasons += "checksum.storedAt needs an offset"
            return null
        }
        val width = storedAt.getOrNull(1)?.takeIf { it > 0 }?.toInt() ?: json.int("width")
        return PluginChecksum(
            strategy = strategy,
            rangeStart = range.getOrNull(0) ?: 0L,
            rangeLength = range.getOrNull(1) ?: -1L,
            storedAt = offset,
            storedWidth = width,
            endianness = json.string("endianness")?.let { Endianness.fromTag(it) },
            asHexText = json.bool("asHexText") ?: false,
            seed = json.long("seed") ?: 0L,
        )
    }

    private fun encodeField(field: FieldMapping): Json.Obj {
        var out = obj("path" to str(field.path), "type" to str(field.type))
        field.offset?.let { out = out.with("offset", num(it)) }
        field.pointer?.let { out = out.with("pointer", num(it)) }
        field.stride?.let { out = out.with("stride", num(it)) }
        field.count?.let { out = out.with("count", num(it)) }
        if (field.recordOffset != 0) out = out.with("recordOffset", num(field.recordOffset))
        if (field.pointerType != "u32") out = out.with("pointerType", str(field.pointerType))
        if (field.pointerBase != 0L) out = out.with("pointerBase", num(field.pointerBase))
        field.displayName?.takeIf { it.isNotBlank() }?.let { out = out.with("name", str(it)) }
        field.category?.takeIf { it.isNotBlank() }?.let { out = out.with("category", str(it)) }
        field.endianness?.let { out = out.with("endianness", str(it.tag)) }
        field.min?.let { out = out.with("min", num(it)) }
        field.max?.let { out = out.with("max", num(it)) }
        if (!field.editable) out = out.with("editable", bool(false))
        if (field.notes.isNotBlank()) out = out.with("notes", str(field.notes))
        return out
    }

    private fun decodeField(json: Json, index: Int, parser: PluginParserKind, reasons: MutableList<String>): FieldMapping? {
        val item = json as? Json.Obj ?: run {
            reasons += "field ${index + 1} is not an object"
            return null
        }
        val path = item.string("path")?.trim().orEmpty()
        if (path.isEmpty()) {
            reasons += "field ${index + 1} has no path"
            return null
        }
        val type = item.string("type")?.trim()?.lowercase().orEmpty()
        if (FieldType.fromTag(type) == null) {
            reasons += "field '$path' has type '$type', which savant does not know"
            return null
        }
        val offset = item.int("offset")
        val length = item.int("length")
        val pointer = item.int("pointer")
        if (parser == PluginParserKind.BINARY && type == FieldType.STRING.tag && (length == null || length <= 0)) {
            reasons += "field '$path' is a string and needs a length in bytes"
            return null
        }
        if (parser == PluginParserKind.BINARY && offset == null && pointer == null) {
            reasons += "field '$path' needs an offset, or a pointer and stride"
            return null
        }
        if (parser != PluginParserKind.BINARY && (offset != null || pointer != null)) {
            reasons += "field '$path' is mapped by path in a ${parser.id} plugin, so it cannot also carry an offset"
            return null
        }
        if (pointer != null && item.int("stride") == null) {
            reasons += "field '$path' has a pointer but no stride"
            return null
        }
        return FieldMapping(
            path = path,
            type = type,
            displayName = item.string("name")?.takeIf { it.isNotBlank() },
            category = item.string("category")?.takeIf { it.isNotBlank() },
            offset = offset,
            length = length,
            pointer = pointer,
            stride = item.int("stride"),
            count = item.int("count"),
            recordOffset = item.int("recordOffset") ?: 0,
            pointerType = item.string("pointerType")?.trim()?.lowercase()?.ifBlank { null } ?: "u32",
            pointerBase = item.long("pointerBase") ?: 0L,
            endianness = item.string("endianness")?.let { Endianness.fromTag(it) },
            min = item.double("min"),
            max = item.double("max"),
            editable = item.bool("editable") ?: true,
            notes = item.string("notes").orEmpty(),
        )
    }

    /** `53 56 53 31`, `53565331` and `0x53,0x56` all mean the same four bytes. */
    fun parseHex(text: String): ByteArray {
        val cleaned = text.lowercase().replace("0x", "").replace(Regex("[^0-9a-f]"), "")
        if (cleaned.length % 2 != 0) return ByteArray(0)
        return ByteArray(cleaned.length / 2) { index ->
            cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    fun newId(gameName: String): String {
        val slug = gameName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        return slug.ifBlank { "plugin" } + "-" + System.currentTimeMillis().toString(36).takeLast(4)
    }

    /** The codec a plugin asks for, or null with a reason when savant cannot honour it. */
    fun codecFor(plugin: GamePlugin): Pair<CodecStrategy?, String?> {
        val id = plugin.codec.lowercase()
        if (id == "xor") {
            val key = parseHex(plugin.xorKey)
            if (key.isEmpty()) return null to "codec xor needs a hex key"
            return runCatching { XorCodec(key) as CodecStrategy }
                .fold({ it to null }, { error -> null to (error.message ?: "xor codec failed") })
        }
        val codec = Codecs.byId(id)
        return if (codec == null) null to "codec '$id' is not one savant implements" else codec to null
    }
}
