package dev.savant.data.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.savant.data.db.BackupRow
import dev.savant.data.db.ImportRow
import dev.savant.data.db.SavantDatabase
import dev.savant.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Steps 1-3 kept backup and import metadata in flat files. Both stores read those files once and
 * move the rows into the database, then rename the source so the migration cannot run twice.
 *
 * Bytes are not moved: a backup stays exactly where it was, at `files/backups/<id>/save.bin`.
 */
@Singleton
class LegacyStoreMigration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SavantDatabase,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private var done = false

    suspend fun run() {
        if (done) return
        done = true
        withContext(io) {
            migrateBackups()
            migrateImports()
        }
    }

    private suspend fun migrateBackups() {
        val root = File(context.filesDir, "backups")
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
        dirs.forEach { dir ->
            val meta = File(dir, "meta.txt")
            val bin = File(dir, "save.bin")
            if (!meta.isFile || !bin.isFile) return@forEach
            val fields = runCatching { meta.readText() }.getOrNull() ?: return@forEach
            val map = fields.lineSequence()
                .filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val id = map["id"] ?: dir.name
            if (db.backupDao().byId(id) != null) return@forEach
            db.backupDao().upsert(
                BackupRow(
                    id = id,
                    sessionId = null,
                    fileName = map["fileName"] ?: "save",
                    formatBadge = map["format"] ?: "?",
                    sizeBytes = map["sizeBytes"]?.toLongOrNull() ?: bin.length(),
                    savedAt = map["savedAt"]?.toLongOrNull() ?: dir.lastModified(),
                    note = map["note"] ?: "",
                    path = bin.absolutePath,
                ),
            )
        }
        // The metadata now lives in the database; the bytes stay put.
        dirs.forEach { File(it, "meta.txt").renameTo(File(it, "meta.migrated")) }
    }

    private suspend fun migrateImports() {
        val file = File(context.filesDir, "imports.txt")
        if (!file.isFile) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        text.lineSequence()
            .filter { it.contains(SEPARATOR) }
            .forEach { line ->
                val parts = line.split(SEPARATOR)
                if (parts.size < 6) return@forEach
                db.importDao().upsert(
                    ImportRow(
                        uri = parts[0],
                        name = parts[1],
                        formatBadge = parts[2],
                        sizeBytes = parts[3].toLongOrNull() ?: 0L,
                        importedAt = parts[4].toLongOrNull() ?: 0L,
                        backupId = parts[5].takeIf { it.isNotBlank() },
                        opened = parts.getOrNull(6)?.toBoolean() ?: true,
                        note = parts.getOrNull(7) ?: "",
                    ),
                )
            }
        file.renameTo(File(context.filesDir, "imports.migrated"))
    }

    private companion object {
        const val SEPARATOR = "\u0001"
    }
}

/**
 * Backups: metadata in the database, bytes in internal storage.
 *
 * Same contract as the step-3 file store, so nothing above it changed. Restoring still reads the
 * original bytes and reopens them as a fresh session.
 */
@Singleton
class RoomBackupStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SavantDatabase,
    private val migration: LegacyStoreMigration,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BackupStore {

    private val root: File get() = File(context.filesDir, "backups")

    override val backups: Flow<List<Backup>> = db.backupDao().observe().map { rows ->
        rows.map { row ->
            Backup(
                id = row.id,
                fileName = row.fileName,
                formatBadge = row.formatBadge,
                sizeBytes = row.sizeBytes,
                savedAt = row.savedAt,
                note = row.note,
            )
        }
    }

    override suspend fun save(fileName: String, formatBadge: String, bytes: ByteArray, note: String): Backup =
        withContext(io) {
            migration.run()
            val id = UUID.randomUUID().toString().take(12)
            val dir = File(root, id).apply { mkdirs() }
            val file = File(dir, "save.bin")
            file.writeBytes(bytes)
            val savedAt = System.currentTimeMillis()
            db.backupDao().upsert(
                BackupRow(
                    id = id,
                    sessionId = null,
                    fileName = fileName,
                    formatBadge = formatBadge,
                    sizeBytes = bytes.size.toLong(),
                    savedAt = savedAt,
                    note = note,
                    path = file.absolutePath,
                ),
            )
            Backup(id, fileName, formatBadge, bytes.size.toLong(), savedAt, note)
        }

    override suspend fun read(id: String): ByteArray? = withContext(io) {
        val row = db.backupDao().byId(sanitise(id)) ?: return@withContext null
        val file = File(row.path)
        if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    override suspend fun delete(id: String) = withContext(io) {
        val clean = sanitise(id)
        db.backupDao().byId(clean)?.let { row -> File(row.path).parentFile?.deleteRecursively() }
        db.backupDao().delete(clean)
    }

    override suspend fun clear() = withContext(io) {
        // Read the rows once so the bytes can go too.
        db.backupDao().observe().first().forEach { row -> File(row.path).parentFile?.deleteRecursively() }
        db.backupDao().clear()
        root.deleteRecursively()
    }

    override suspend fun refresh() = withContext(io) { migration.run() }

    private fun sanitise(id: String): String = id.replace(Regex("[^A-Za-z0-9_\\-]"), "")
}

/**
 * Recent imports, newest first.
 *
 * Uris keep whatever persistable permission the picker granted, so a row can be reopened after a
 * restart without making the user browse again.
 */
@Singleton
class RoomImportStore @Inject constructor(
    private val db: SavantDatabase,
    private val migration: LegacyStoreMigration,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ImportIndex {

    override val records: Flow<List<ImportRecord>> = db.importDao().observe(ImportIndex.MAX_RECORDS).map { rows ->
        rows.map { row ->
            ImportRecord(
                uri = row.uri,
                name = row.name,
                formatBadge = row.formatBadge,
                sizeBytes = row.sizeBytes,
                importedAt = row.importedAt,
                backupId = row.backupId,
                opened = row.opened,
                note = row.note,
            )
        }
    }

    override suspend fun add(record: ImportRecord) = withContext(io) {
        migration.run()
        db.importDao().upsert(
            ImportRow(
                uri = record.uri,
                name = record.name,
                formatBadge = record.formatBadge,
                sizeBytes = record.sizeBytes,
                importedAt = record.importedAt,
                backupId = record.backupId,
                opened = record.opened,
                note = record.note,
            ),
        )
        trim()
    }

    override suspend fun remove(uri: String) = withContext(io) { db.importDao().delete(uri) }

    override suspend fun clear() = withContext(io) { db.importDao().clear() }

    override suspend fun refresh() = withContext(io) { migration.run() }

    private suspend fun trim() {
        // The query is already limited; this only stops the table growing forever.
        val rows = db.importDao().observe(Int.MAX_VALUE).first()
        if (rows.size > ImportIndex.MAX_RECORDS) {
            rows.drop(ImportIndex.MAX_RECORDS).forEach { db.importDao().delete(it.uri) }
        }
    }
}
