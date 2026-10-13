package dev.savant.data.history

import dev.savant.data.db.EntryRow
import dev.savant.data.db.SavantDatabase
import dev.savant.data.db.SessionRow
import dev.savant.di.IoDispatcher
import dev.savant.domain.chat.ChatEntry
import dev.savant.domain.session.EditorSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Row counts for the settings → data block. */
data class HistoryCounts(
    val sessions: Int = 0,
    val entries: Int = 0,
    val backups: Int = 0,
    val backupBytes: Long = 0,
    val imports: Int = 0,
    val namedFields: Int = 0,
) {
    val summary: String
        get() = "$sessions session${if (sessions == 1) "" else "s"} · $entries message${if (entries == 1) "" else "s"}" +
            " · $backups backup${if (backups == 1) "" else "s"} · $namedFields saved name${if (namedFields == 1) "" else "s"}"
}

/** A transcript row plus the session it belongs to, for the history search list. */
data class HistoryHit(
    val entry: ChatEntry,
    val session: SessionRow?,
    val stored: StoredEntry,
) {
    val snippet: String get() = EntryCodec.snippet(stored)
    val fileName: String get() = session?.fileName ?: "unknown file"
}

/**
 * Chat history and sessions.
 *
 * Every entry the chat appends is stored as it happens, so a transcript survives a restart, and the
 * Files screen can list what savant has opened. Values and rebuilt bytes are not stored — reopening
 * the file is the only honest way to show them again.
 */
@Singleton
class ChatHistoryRepository @Inject constructor(
    private val db: SavantDatabase,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val sessions get() = db.sessionDao()
    private val entries get() = db.entryDao()

    fun observeSessions(limit: Int = 50): Flow<List<SessionRow>> = sessions.observe(limit)

    fun searchSessions(query: String, limit: Int = 50): Flow<List<SessionRow>> =
        if (query.isBlank()) sessions.observe(limit) else sessions.search(query.trim(), limit)

    suspend fun newestSessionId(): String? = withContext(io) { sessions.newest()?.id }

    suspend fun sessionById(id: String): SessionRow? = withContext(io) { sessions.byId(id) }

    /** Opens a history row for this session, or refreshes the one that is already there. */
    suspend fun recordSession(session: EditorSession): SessionRow = withContext(io) {
        val row = SessionRow(
            id = session.id,
            uri = session.file.uri,
            fileName = session.file.name,
            formatBadge = session.formatBadge,
            sizeBytes = session.file.sizeBytes,
            nodeCount = session.tree.totalCount,
            editableCount = session.tree.editableCount,
            backupId = session.backupId,
            readOnly = session.readOnly,
            readOnlyReason = session.tree.readOnlyReason,
            origin = session.file.origin.name,
            openedAt = session.openedAt,
            closedAt = null,
            editCount = session.appliedEdits.size,
            exportCount = if (session.export == null) 0 else 1,
        )
        sessions.upsert(row)
        row
    }

    suspend fun updateCounts(session: EditorSession) = withContext(io) {
        sessions.setEditCount(session.id, session.appliedEdits.size)
        if (session.export != null) sessions.countExport(session.id)
    }

    suspend fun closeSession(id: String) = withContext(io) {
        sessions.close(id, System.currentTimeMillis())
    }

    suspend fun append(sessionId: String, entry: ChatEntry, position: Int) = withContext(io) {
        val stored = EntryCodec.encode(entry, sessionId, position)
        entries.insert(stored.toRow())
    }

    suspend fun transcript(sessionId: String): List<ChatEntry> = withContext(io) {
        entries.forSession(sessionId).mapNotNull { row -> EntryCodec.decode(row.toStored()) }
    }

    fun observeTranscript(sessionId: String): Flow<List<ChatEntry>> =
        entries.observeFor(sessionId).map { rows -> rows.mapNotNull { EntryCodec.decode(it.toStored()) } }

    suspend fun searchEntries(query: String, limit: Int = 50): List<HistoryHit> = withContext(io) {
        if (query.isBlank()) return@withContext emptyList()
        entries.search(query.trim(), limit).mapNotNull { row ->
            val stored = row.toStored()
            val entry = EntryCodec.decode(stored) ?: return@mapNotNull null
            HistoryHit(entry = entry, session = sessions.byId(row.sessionId), stored = stored)
        }
    }

    suspend fun deleteSession(id: String) = withContext(io) { sessions.delete(id) }

    suspend fun clear() = withContext(io) {
        entries.clear()
        sessions.clear()
    }

    suspend fun counts(): HistoryCounts = withContext(io) {
        HistoryCounts(
            sessions = sessions.count(),
            entries = entries.count(),
            backups = db.backupDao().count(),
            backupBytes = db.backupDao().totalBytes(),
            imports = db.importDao().count(),
            namedFields = db.namedFieldDao().count(),
        )
    }

    private fun StoredEntry.toRow() = EntryRow(
        id = id,
        sessionId = sessionId,
        kind = kind,
        role = role,
        body = body,
        payload = payload,
        isError = isError,
        createdAt = createdAt,
        position = position,
    )

    private fun EntryRow.toStored() = StoredEntry(
        id = id,
        sessionId = sessionId,
        kind = kind,
        role = role,
        body = body,
        payload = payload,
        isError = isError,
        createdAt = createdAt,
        position = position,
    )
}
