package dev.savant.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Savant's local database.
 *
 * Version 1, schema exported to `app/schemas` so every later change ships with a real migration
 * instead of a destructive fallback. What lives here: which files were opened, what the chat said
 * about them, every backup and import, and the names savant learned for cryptic fields.
 *
 * What does not: the save files themselves (backups keep their bytes on disk, originals stay where
 * the user put them) and rebuilt exports (those are staged in cache and handed straight out).
 */
@Database(
    entities = [
        SessionRow::class,
        EntryRow::class,
        BackupRow::class,
        ImportRow::class,
        NamedFieldRow::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SavantDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun entryDao(): EntryDao
    abstract fun backupDao(): BackupDao
    abstract fun importDao(): ImportDao
    abstract fun namedFieldDao(): NamedFieldDao

    companion object {
        const val NAME = "savant.db"
    }
}
