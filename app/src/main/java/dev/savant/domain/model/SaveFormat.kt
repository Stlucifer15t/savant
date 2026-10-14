package dev.savant.domain.model

/**
 * File formats savant knows about.
 *
 * Every entry exists from day one so the detector, the format badges and the plugin
 * schema stay stable, but only the formats with a registered [dev.savant.domain.parser.SaveParser]
 * can actually be opened. `implemented` drives the Formats screen.
 */
enum class SaveFormat(
    val label: String,
    val badge: String,
    val extensions: List<String>,
    val implemented: Boolean,
    val notes: String,
) {
    JSON(
        label = "json",
        badge = "JSON",
        extensions = listOf("json", "json5", "sav"),
        implemented = true,
        notes = "objects, arrays, numbers, strings, booleans, null",
    ),
    XML(
        label = "xml",
        badge = "XML",
        extensions = listOf("xml", "plist", "config", "sav"),
        implemented = true,
        notes = "elements, attributes, text nodes, cdata. apple plist dicts are mapped to named fields",
    ),
    INI(
        label = "ini / cfg",
        badge = "INI",
        extensions = listOf("ini", "cfg", "conf", "properties"),
        implemented = true,
        notes = "sections and key=value lines. comments, spacing, quoting and line endings survive an edit",
    ),
    RPGSAVE(
        label = "rpg maker mv / mz",
        badge = "RPGSAVE",
        extensions = listOf("rpgsave", "rpgdata", "rpgmvp"),
        implemented = false,
        notes = "lz-string compressed base64 wrapping a json payload",
    ),
    SQLITE(
        label = "sqlite",
        badge = "SQLITE",
        extensions = listOf("db", "sqlite", "sqlite3", "db3"),
        implemented = false,
        notes = "tables and rows, edited through the page structure",
    ),
    UNITY_PLIST(
        label = "unity playerprefs",
        badge = "PLIST",
        extensions = listOf("plist"),
        implemented = false,
        notes = "unity playerprefs on ios / osx style plist containers",
    ),
    BINARY(
        label = "raw binary",
        badge = "BINARY",
        extensions = emptyList(),
        implemented = false,
        notes = "offset plugin driven: typed values at fixed offsets, optional pointer/stride records",
    ),
    UNKNOWN(
        label = "unknown",
        badge = "?",
        extensions = emptyList(),
        implemented = false,
        notes = "no parser claimed this file",
    ),
    ;

    val isTextFormat: Boolean
        get() = this == JSON || this == XML || this == INI

    companion object {
        fun fromExtension(extension: String?): SaveFormat? {
            if (extension.isNullOrBlank()) return null
            val ext = extension.lowercase().removePrefix(".")
            return entries.firstOrNull { ext in it.extensions }
        }

        fun implementedFormats(): List<SaveFormat> = entries.filter { it.implemented }
    }
}
