package dev.savant.data.storage

import kotlinx.coroutines.flow.Flow

/** A row on the Files screen: something savant imported and could open again. */
data class ImportRecord(
    val uri: String,
    val name: String,
    val formatBadge: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val backupId: String?,
    val opened: Boolean,
    val note: String = "",
) {
    val whenLabel: String get() = ageSince(importedAt)
}

/**
 * The recent imports list, newest first, capped at [MAX_RECORDS].
 *
 * Step 3 kept this in a flat file; step 4 keeps it in the database. The contract did not change,
 * which is why no screen noticed: [RoomImportStore] implements it and [LegacyStoreMigration] moves
 * whatever the flat file held.
 */
interface ImportIndex {
    val records: Flow<List<ImportRecord>>
    suspend fun add(record: ImportRecord)
    suspend fun remove(uri: String)
    suspend fun clear()
    suspend fun refresh()

    companion object {
        const val MAX_RECORDS = 50
    }
}
