package dev.savant.data.history

import dev.savant.data.db.NamedFieldDao
import dev.savant.data.db.NamedFieldRow
import dev.savant.di.IoDispatcher
import dev.savant.domain.finder.Candidate
import dev.savant.domain.model.Node
import dev.savant.domain.model.SaveFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Why a name was stored. Shown nowhere, but it keeps guesses separate from things you typed. */
enum class NameSource { USER, AI_GUESS, FINDER, PLUGIN }

/**
 * Names for fields that shipped without one.
 *
 * A save file full of `gld`, `hp_2`, `f0x1c` is readable only once somebody says what those hold.
 * This table remembers those answers per format, so the second time the same game is opened the
 * names are already there — no assistant, no network, nothing re-derived.
 *
 * Names are a display overlay only. Writing a save never uses them: bytes go out exactly as the
 * parser read them, with only the edited values changed.
 */
@Singleton
class NamedFieldRepository @Inject constructor(
    private val dao: NamedFieldDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun observe(): Flow<List<NamedFieldRow>> = dao.observe()

    /** `path → display name`, for the rows belonging to one format. */
    suspend fun namesFor(format: SaveFormat): Map<String, String> = withContext(io) {
        dao.forFormat(format.name).associate { it.path to it.displayName }
    }

    /** Stores one name. Re-storing the same key replaces it, so a rename is just another save. */
    suspend fun remember(
        format: SaveFormat,
        node: Node,
        displayName: String,
        source: NameSource = NameSource.USER,
        confidence: Float = 1f,
        explanation: String? = null,
    ): NamedFieldRow? {
        val name = displayName.trim()
        if (name.isEmpty()) return null
        val row = NamedFieldRow(
            id = "${format.name}|${node.sourceKey}|${node.path}",
            format = format.name,
            path = node.path,
            nameKey = node.sourceKey,
            displayName = name,
            confidence = confidence,
            explanation = explanation,
            source = source.name,
            savedAt = System.currentTimeMillis(),
        )
        return withContext(io) {
            dao.upsert(row)
            row
        }
    }

    /**
     * Stores a name for a byte offset the finder ranked.
     *
     * This is the finder's only output: nothing was written to any file. The row keeps the offset,
     * the type and the byte order, which is exactly what a plugin field mapping needs, so step 7 can
     * export these instead of asking the user to type offsets again.
     */
    suspend fun rememberCandidate(
        candidate: Candidate,
        displayName: String,
        fileName: String?,
    ): NamedFieldRow? {
        val name = displayName.trim()
        if (name.isEmpty()) return null
        val row = NamedFieldRow(
            id = "${SaveFormat.BINARY.name}|${fileName ?: "?"}|${candidate.key}",
            format = SaveFormat.BINARY.name,
            path = candidate.offsetLabel,
            nameKey = candidate.key,
            displayName = name,
            confidence = candidate.confidence,
            explanation = candidate.reasons.joinToString(" · "),
            source = NameSource.FINDER.name,
            savedAt = System.currentTimeMillis(),
        )
        return withContext(io) {
            dao.upsert(row)
            row
        }
    }

    suspend fun remove(id: String) = withContext(io) { dao.delete(id) }

    suspend fun clear() = withContext(io) { dao.clear() }
}
