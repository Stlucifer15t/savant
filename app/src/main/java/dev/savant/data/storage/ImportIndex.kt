package dev.savant.data.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.savant.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
    val whenLabel: String get() {
        val minutes = (System.currentTimeMillis() - importedAt) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} d ago"
        }
    }
}

/**
 * The recent imports list, newest first, capped at [MAX_RECORDS].
 *
 * Same reasoning as [BackupStore]: a flat `key=value` file now, a Room table in step 4. Uris are
 * persisted with `takePersistableUriPermission` when the picker grants them, so a row can be
 * reopened after a restart without asking the user to browse again.
 */
@Singleton
class ImportIndex @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val file: File get() = File(context.filesDir, "imports.txt")
    private val state = MutableStateFlow<List<ImportRecord>>(emptyList())
    val records: Flow<List<ImportRecord>> = state

    init {
        state.value = read()
    }

    suspend fun add(record: ImportRecord) = withContext(io) {
        val kept = (listOf(record) + read().filterNot { it.uri == record.uri }).take(MAX_RECORDS)
        write(kept)
        state.value = kept
    }

    suspend fun remove(uri: String) = withContext(io) {
        val kept = read().filterNot { it.uri == uri }
        write(kept)
        state.value = kept
    }

    suspend fun clear() = withContext(io) {
        runCatching { file.delete() }
        state.value = emptyList()
    }

    suspend fun refresh() = withContext(io) { state.value = read() }

    private fun read(): List<ImportRecord> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return text.lineSequence()
            .filter { it.contains(SEPARATOR) }
            .mapNotNull { line ->
                val parts = line.split(SEPARATOR)
                if (parts.size < 6) return@mapNotNull null
                ImportRecord(
                    uri = parts[0],
                    name = parts[1],
                    formatBadge = parts[2],
                    sizeBytes = parts[3].toLongOrNull() ?: 0L,
                    importedAt = parts[4].toLongOrNull() ?: 0L,
                    backupId = parts[5].takeIf { it.isNotBlank() },
                    opened = parts.getOrNull(6)?.toBoolean() ?: true,
                    note = parts.getOrNull(7) ?: "",
                )
            }
            .toList()
    }

    private fun write(records: List<ImportRecord>) {
        val text = records.joinToString("\n") { record ->
            listOf(
                record.uri.replace(SEPARATOR, " "),
                oneLine(record.name),
                record.formatBadge,
                record.sizeBytes.toString(),
                record.importedAt.toString(),
                record.backupId ?: "",
                record.opened.toString(),
                oneLine(record.note),
            ).joinToString(SEPARATOR)
        }
        runCatching { file.parentFile?.mkdirs(); file.writeText(text) }
    }

    private fun oneLine(value: String): String = value.replace('\n', ' ').replace('\r', ' ')

    companion object {
        const val MAX_RECORDS = 50
        private const val SEPARATOR = "\u0001"
    }
}
