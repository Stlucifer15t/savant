package dev.savant.data.file

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_FILE_BYTES = 50L * 1024L * 1024L

data class SourceFileMetadata(
    val name: String,
    val size: Long?,
    val mimeType: String?,
)

data class SessionBackup(
    val path: String,
    val sha256: String,
)

class SaveFileTooLargeException(size: Long?) : IllegalArgumentException(
    if (size == null) "file exceeds the 50 MB limit" else "file is ${size / (1024 * 1024)} MB; limit is 50 MB",
)

@Singleton
class SaveFileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun metadata(uri: Uri): SourceFileMetadata = withContext(Dispatchers.IO) {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
        }
        SourceFileMetadata(
            name = name?.takeIf(String::isNotBlank) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "save-file",
            size = size,
            mimeType = resolver.getType(uri),
        )
    }

    suspend fun read(uri: Uri, expectedSize: Long? = null): ByteArray = withContext(Dispatchers.IO) {
        if (expectedSize != null && expectedSize > MAX_FILE_BYTES) throw SaveFileTooLargeException(expectedSize)
        val stream = resolver.openInputStream(uri)
            ?: throw IllegalArgumentException("the selected file could not be opened")
        stream.use { input ->
            val initialSize = expectedSize?.coerceAtMost(MAX_FILE_BYTES)?.toInt() ?: DEFAULT_BUFFER_SIZE
            val output = ByteArrayOutputStream(initialSize)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_FILE_BYTES) throw SaveFileTooLargeException(total)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    suspend fun createSessionBackup(fileName: String, bytes: ByteArray): SessionBackup =
        withContext(Dispatchers.IO) {
            val digest = bytes.sha256()
            val directory = File(context.filesDir, "session-originals").apply { mkdirs() }
            val safeName = fileName.safeFileName()
            val target = File(directory, "$digest-$safeName")
            if (!target.exists()) {
                val temporary = File(directory, ".${target.name}.tmp")
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
            }
            SessionBackup(target.absolutePath, digest)
        }

    suspend fun write(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val output = resolver.openOutputStream(uri, "wt")
            ?: throw IllegalArgumentException("the destination could not be opened")
        output.use { it.write(bytes) }
    }

    suspend fun cacheForSharing(fileName: String, bytes: ByteArray): Uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "exports").apply { mkdirs() }
        directory.listFiles()?.forEach { stale ->
            if (System.currentTimeMillis() - stale.lastModified() > CACHE_MAX_AGE_MS) stale.delete()
        }
        val target = File(directory, fileName.safeFileName())
        FileOutputStream(target).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun String.safeFileName(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "save-file" }

    private companion object {
        const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}
