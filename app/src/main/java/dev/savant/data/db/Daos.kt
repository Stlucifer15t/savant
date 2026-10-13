package dev.savant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SessionRow)

    @Query("SELECT * FROM sessions ORDER BY openedAt DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<SessionRow>>

    @Query(
        "SELECT * FROM sessions WHERE fileName LIKE '%' || :query || '%' OR uri LIKE '%' || :query || '%' " +
            "ORDER BY openedAt DESC LIMIT :limit",
    )
    fun search(query: String, limit: Int): Flow<List<SessionRow>>

    @Query("SELECT * FROM sessions ORDER BY openedAt DESC LIMIT 1")
    suspend fun newest(): SessionRow?

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: String): SessionRow?

    @Query("UPDATE sessions SET editCount = :edits WHERE id = :id")
    suspend fun setEditCount(id: String, edits: Int)

    @Query("UPDATE sessions SET exportCount = exportCount + 1 WHERE id = :id")
    suspend fun countExport(id: String)

    @Query("UPDATE sessions SET closedAt = :closedAt WHERE id = :id")
    suspend fun close(id: String, closedAt: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sessions")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int
}

@Dao
interface EntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: EntryRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<EntryRow>)

    @Query("SELECT * FROM entries WHERE sessionId = :sessionId ORDER BY position ASC")
    fun observeFor(sessionId: String): Flow<List<EntryRow>>

    @Query("SELECT * FROM entries WHERE sessionId = :sessionId ORDER BY position ASC")
    suspend fun forSession(sessionId: String): List<EntryRow>

    @Query(
        "SELECT * FROM entries WHERE body LIKE '%' || :query || '%' " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun search(query: String, limit: Int): List<EntryRow>

    @Query("DELETE FROM entries WHERE sessionId = :sessionId")
    suspend fun deleteFor(sessionId: String)

    @Query("DELETE FROM entries")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM entries")
    suspend fun count(): Int
}

@Dao
interface BackupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: BackupRow)

    @Query("SELECT * FROM backups ORDER BY savedAt DESC")
    fun observe(): Flow<List<BackupRow>>

    @Query("SELECT * FROM backups WHERE id = :id")
    suspend fun byId(id: String): BackupRow?

    @Query("DELETE FROM backups WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM backups")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM backups")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM backups")
    suspend fun totalBytes(): Long
}

@Dao
interface ImportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ImportRow)

    @Query("SELECT * FROM imports ORDER BY importedAt DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<ImportRow>>

    @Query("DELETE FROM imports WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM imports")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM imports")
    suspend fun count(): Int
}

@Dao
interface NamedFieldDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: NamedFieldRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<NamedFieldRow>)

    @Query("SELECT * FROM named_fields WHERE format = :format")
    suspend fun forFormat(format: String): List<NamedFieldRow>

    @Query("SELECT * FROM named_fields ORDER BY savedAt DESC")
    fun observe(): Flow<List<NamedFieldRow>>

    @Query("SELECT * FROM named_fields WHERE nameKey = :nameKey AND format = :format LIMIT 1")
    suspend fun byKey(format: String, nameKey: String): NamedFieldRow?

    @Query("DELETE FROM named_fields WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM named_fields")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM named_fields")
    suspend fun count(): Int
}
