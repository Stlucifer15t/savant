package dev.savant.data.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.savant.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A file read off a SAF uri, ready for the parser. */
data class LoadedFile(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is LoadedFile && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()
}

sealed interface FileLoadResult {
    data class Loaded(val name: String, val mimeType: String?, val bytes: ByteArray) : FileLoadResult
    /** [sizeBytes] is what the provider reported; -1 when it would not say. */
    data class TooLarge(val sizeBytes: Long, val limitBytes: Long) : FileLoadResult
    data class Failed(val reason: String) : FileLoadResult
}

/**
 * Reads and writes save files through the Storage Access Framework.
 *
 * No READ/WRITE_EXTERNAL_STORAGE anywhere: a file only enters through the picker or the share
 * sheet, and only leaves through ACTION_CREATE_DOCUMENT or a FileProvider share of a staged copy.
 * The user's original is never modified in place.
 */
@Singleton
class SaveFileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun load(uri: Uri, limitBytes: Long): FileLoadResult = withContext(io) {
        val resolver = context.contentResolver
        val declared = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L }.getOrDefault(-1L)
        if (declared > limitBytes) return@withContext FileLoadResult.TooLarge(declared, limitBytes)

        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return@withContext FileLoadResult.Failed("could not read that file")
        if (bytes.size.toLong() > limitBytes) {
            return@withContext FileLoadResult.TooLarge(bytes.size.toLong(), limitBytes)
        }
        FileLoadResult.Loaded(displayName(uri), resolver.getType(uri), bytes)
    }

    /** Writes a rebuilt file to a uri the user chose with ACTION_CREATE_DOCUMENT. */
    suspend fun writeTo(uri: Uri, bytes: ByteArray): Boolean = withContext(io) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    /**
     * Stages a rebuilt file in cache and returns a content uri for the share sheet.
     * The cache copy is disposable; the user's chosen destination is not.
     */
    suspend fun stageForShare(fileName: String, bytes: ByteArray): Uri? = withContext(io) {
        runCatching {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, sanitise(fileName))
            target.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        }.getOrNull()
    }

    fun displayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "save file"
    }

    private fun sanitise(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._\\- ]"), "_").ifBlank { "save.bin" }
}
