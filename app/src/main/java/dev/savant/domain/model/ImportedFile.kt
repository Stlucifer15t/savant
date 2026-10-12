package dev.savant.domain.model

/**
 * A file the user handed to savant.
 *
 * The uri is kept as a String so the whole domain layer stays pure JVM and unit testable;
 * `android.net.Uri.parse` happens in the data layer.
 */
data class ImportedFile(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val importedAt: Long,
    /** First bytes as hex pairs, drawn as the thumbnail pattern on the FileChip. */
    val hexPreview: String,
    val origin: Origin = Origin.PICKER,
) {
    enum class Origin(val label: String) {
        PICKER("attached"),
        SHARE("shared in"),
        BACKUP("restored"),
    }

    val extension: String? get() = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.lowercase()

    val sizeLabel: String get() = formatBytes(sizeBytes)

    companion object {
        /** Two rows of hex, e.g. `7b 22 76 65` / `72 73 69 6f`. */
        fun hexPreview(bytes: ByteArray, bytesPerRow: Int = 4, rows: Int = 2): String {
            if (bytes.isEmpty()) return "-- -- -- --"
            val take = bytes.copyOfRange(0, minOf(bytes.size, bytesPerRow * rows))
            return take.joinToString(" ") { "%02x".format(it) }
        }
    }
}

/** Byte sizes in the short form the UI uses: `4.2 kb`, `1.9 mb`. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    if (bytes < 1024) return "$bytes b"
    val units = listOf("kb", "mb", "gb")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val decimals = if (value >= 100) 0 else 1
    return "%.${decimals}f %s".format(value, units[unit])
}
