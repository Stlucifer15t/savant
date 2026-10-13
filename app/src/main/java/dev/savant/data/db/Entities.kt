package dev.savant.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One file savant opened.
 *
 * A session is the join point for everything else: its transcript, its edit batches and the backup
 * taken before the first edit. `closedAt` stays null while the file is still the open one.
 */
@Entity(
    tableName = "sessions",
    indices = [Index("openedAt"), Index("fileName")],
)
data class SessionRow(
    @PrimaryKey val id: String,
    val uri: String,
    val fileName: String,
    val formatBadge: String,
    val sizeBytes: Long,
    val nodeCount: Int,
    val editableCount: Int,
    val backupId: String?,
    val readOnly: Boolean,
    val readOnlyReason: String?,
    val origin: String,
    val openedAt: Long,
    val closedAt: Long? = null,
    val editCount: Int = 0,
    val exportCount: Int = 0,
)

/**
 * One chat entry.
 *
 * [body] is the searchable plain text; [payload] is a compact json document with everything the
 * body cannot carry, written by `EntryCodec`. Nodes and rebuilt bytes are deliberately not stored:
 * a transcript is a record of what happened, and the values come back when the file is reopened.
 */
@Entity(
    tableName = "entries",
    indices = [Index("sessionId"), Index("createdAt")],
    foreignKeys = [
        ForeignKey(
            entity = SessionRow::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EntryRow(
    @PrimaryKey val id: String,
    val sessionId: String,
    val kind: String,
    val role: String?,
    val body: String,
    val payload: String,
    val isError: Boolean,
    val createdAt: Long,
    val position: Int,
)

/** Backup metadata. The bytes stay in `files/backups/<id>/save.bin`. */
@Entity(
    tableName = "backups",
    indices = [Index("savedAt"), Index("sessionId")],
)
data class BackupRow(
    @PrimaryKey val id: String,
    val sessionId: String?,
    val fileName: String,
    val formatBadge: String,
    val sizeBytes: Long,
    val savedAt: Long,
    val note: String,
    val path: String,
)

/** A file the user handed to savant, so it can be reopened without browsing again. */
@Entity(
    tableName = "imports",
    indices = [Index("importedAt")],
)
data class ImportRow(
    @PrimaryKey val uri: String,
    val name: String,
    val formatBadge: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val backupId: String?,
    val opened: Boolean,
    val note: String,
)

/**
 * A name for a field that had none.
 *
 * Written when savant guesses what a cryptic key holds, when the user renames one, and later by
 * "name it" in the binary scanner. Keyed by format plus the raw key, so the same game keeps its
 * names across saves and across restarts.
 */
@Entity(
    tableName = "named_fields",
    indices = [Index("nameKey"), Index("format")],
)
data class NamedFieldRow(
    @PrimaryKey val id: String,
    val format: String,
    val path: String,
    val nameKey: String,
    val displayName: String,
    val confidence: Float,
    val explanation: String?,
    val source: String,
    val savedAt: Long,
    @ColumnInfo(defaultValue = "0") val promotedToPlugin: Boolean = false,
)
