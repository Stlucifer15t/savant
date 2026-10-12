package dev.savant.data.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.savant.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One saved copy of an imported original, kept so a bad edit is always reversible. */
data class Backup(
    val id: String,
    val fileName: String,
    val formatBadge: String,
    val sizeBytes: Long,
    val savedAt: Long,
    val note: String,
) {
    val ageLabel: String get() {
        val minutes = (System.currentTimeMillis() - savedAt) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} d ago"
        }
    }
}

/**
 * Backups of every imported original, held in internal storage.
 *
 * File based on purpose: step 3 needs one-tap restore, not queries. Room arrives with step 4 when
 * chat history and sessions need to be joined; the on-disk layout stays the same and the index
 * moves into a table then.
 *
 * Layout: `files/backups/<id>/save.bin` plus `files/backups/<id>/meta.txt` (`key=value` lines).
 */
interface BackupStore {
    val backups: Flow<List<Backup>>
    suspend fun save(fileName: String, formatBadge: String, bytes: ByteArray, note: String = ""): Backup
    suspend fun read(id: String): ByteArray?
    suspend fun delete(id: String)
    suspend fun clear()
    suspend fun refresh()
}

@Singleton
class FileBackupStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BackupStore {

    private val root: File get() = File(context.filesDir, "backups")
    private val state = MutableStateFlow<List<Backup>>(emptyList())
    override val backups: Flow<List<Backup>> = state

    init {
        // Reading a few small meta files is cheap enough to do eagerly; the first frame then shows
        // the real backup dot instead of a flicker.
        state.value = readAll()
    }

    override suspend fun save(fileName: String, formatBadge: String, bytes: ByteArray, note: String): Backup =
        withContext(io) {
            val id = UUID.randomUUID().toString().take(12)
            val dir = File(root, id).apply { mkdirs() }
            File(dir, "save.bin").writeBytes(bytes)
            File(dir, "meta.txt").writeText(
                listOf(
                    "id=$id",
                    "fileName=${oneLine(fileName)}",
                    "format=${oneLine(formatBadge)}",
                    "sizeBytes=${bytes.size}",
                    "savedAt=${System.currentTimeMillis()}",
                    "note=${oneLine(note)}",
                ).joinToString("\n"),
            )
            val backup = Backup(id, fileName, formatBadge, bytes.size.toLong(), System.currentTimeMillis(), note)
            state.value = readAll()
            backup
        }

    override suspend fun read(id: String): ByteArray? = withContext(io) {
        val file = File(File(root, sanitise(id)), "save.bin")
        if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    override suspend fun delete(id: String) = withContext(io) {
        File(root, sanitise(id)).deleteRecursively()
        state.value = readAll()
    }

    override suspend fun clear() = withContext(io) {
        root.deleteRecursively()
        state.value = emptyList()
    }

    override suspend fun refresh() = withContext(io) { state.value = readAll() }

    private fun readAll(): List<Backup> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val meta = File(dir, "meta.txt")
            if (!meta.isFile) return@mapNotNull null
            val fields = runCatching { meta.readText() }.getOrNull() ?: return@mapNotNull null
            val map = fields.lineSequence()
                .filter { it.contains('=') }
                .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
            Backup(
                id = map["id"] ?: dir.name,
                fileName = map["fileName"] ?: "save",
                formatBadge = map["format"] ?: "?",
                sizeBytes = map["sizeBytes"]?.toLongOrNull() ?: 0L,
                savedAt = map["savedAt"]?.toLongOrNull() ?: dir.lastModified(),
                note = map["note"] ?: "",
            )
        }.sortedByDescending { it.savedAt }
    }

    private fun oneLine(value: String): String = value.replace('\n', ' ').replace('\r', ' ')

    private fun sanitise(id: String): String = id.replace(Regex("[^A-Za-z0-9_\\-]"), "")
}
