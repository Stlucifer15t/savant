package dev.savant.plugins

import dev.savant.domain.finder.BinaryValues
import dev.savant.domain.model.Endianness
import dev.savant.domain.model.FieldType

/**
 * A plugin built from what the finder already learned.
 *
 * Step 5 stores a name against an offset, a type and a byte order. That is three quarters of a
 * binary plugin, so the last quarter is written here: group the named offsets under a game name, and
 * savant can open that game's saves properly instead of re-scanning them every time.
 *
 * The offsets are the user's own, from their own saves. Nothing is downloaded and nothing is shared.
 */
object PluginDraft {

    /** One named offset: `0x000008:i32 le` plus the name the user gave it. */
    data class NamedOffset(val name: String, val key: String)

    /** `0x000008:i32 le` → offset 8, i32, little endian. Null when the key is not one savant wrote. */
    fun parseKey(key: String): ParsedOffset? {
        val parts = key.split(':')
        if (parts.size != 2) return null
        val offset = parts[0].removePrefix("0x").removePrefix("0X").toLongOrNull(16)?.toInt() ?: return null
        val typeAndEndian = parts[1].trim().split(' ')
        val type = FieldType.fromTag(typeAndEndian.getOrNull(0)) ?: return null
        if (type.sizeBytes == null) return null
        val endianness = Endianness.fromTag(typeAndEndian.getOrNull(1))
        return ParsedOffset(offset, type, endianness)
    }

    data class ParsedOffset(val offset: Int, val type: FieldType, val endianness: Endianness)

    /**
     * Builds a draft plugin. Offerts that do not parse are skipped and reported, because a half
     * understood mapping is worse than no mapping: it would edit the wrong bytes.
     */
    fun fromNamedOffsets(
        gameName: String,
        offsets: List<NamedOffset>,
        fileNamePattern: String = "*.bin",
    ): Pair<GamePlugin, List<String>> {
        val fields = mutableListOf<FieldMapping>()
        val skipped = mutableListOf<String>()
        for (entry in offsets) {
            val parsed = parseKey(entry.key)
            if (parsed == null) {
                skipped += "${entry.name}: '${entry.key}' is not an offset savant wrote"
                continue
            }
            fields += FieldMapping(
                path = entry.name.trim().lowercase().replace(Regex("[^a-z0-9]+"), ".").trim('.'),
                type = parsed.type.tag,
                displayName = entry.name.trim(),
                offset = parsed.offset,
                endianness = parsed.endianness,
            )
        }
        val plugin = GamePlugin(
            id = PluginCodec.newId(gameName),
            gameName = gameName.trim().ifBlank { "unnamed game" },
            parser = PluginParserKind.BINARY,
            fileMatch = listOf(fileNamePattern),
            endianness = Endianness.LITTLE,
            fields = fields,
            source = PluginSource.FROM_FINDER,
            notes = "built from ${fields.size} offset(s) named in the finder",
        )
        return plugin to skipped
    }

    /** The offset label a candidate row shows, so a draft reads the same as the finder did. */
    fun label(offset: Int): String = BinaryValues.offsetLabel(offset)
}
