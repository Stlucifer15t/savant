package dev.savant.data.storage

import kotlinx.coroutines.flow.Flow

/** One saved copy of an imported original, kept so a bad edit is always reversible. */
data class Backup(
    val id: String,
    val fileName: String,
    val formatBadge: String,
    val sizeBytes: Long,
    val savedAt: Long,
    val note: String,
) {
    val ageLabel: String get() = ageSince(savedAt)
}

/**
 * Backups of every imported original.
 *
 * The contract step 3 established, now served by [RoomBackupStore]: metadata in the database, bytes
 * in internal storage, restore reopens the untouched file.
 */
interface BackupStore {
    val backups: Flow<List<Backup>>
    suspend fun save(fileName: String, formatBadge: String, bytes: ByteArray, note: String = ""): Backup
    suspend fun read(id: String): ByteArray?
    suspend fun delete(id: String)
    suspend fun clear()
    suspend fun refresh()
}

/** "just now", "12 min ago", "3 h ago", "2 d ago" — the same wording everywhere it appears. */
internal fun ageSince(atMillis: Long): String {
    val minutes = (System.currentTimeMillis() - atMillis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}
