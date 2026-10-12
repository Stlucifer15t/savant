package dev.savant.domain.parser

import dev.savant.codecs.ChecksumSpec
import dev.savant.codecs.CodecStrategy
import dev.savant.domain.model.Confidence
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.SaveFormat

/** Hard limits. Parsing is expected to stay responsive for files up to [MAX_FILE_BYTES]. */
object NodeLimits {
    const val MAX_FILE_BYTES: Long = 50L * 1024 * 1024
    const val MAX_NODES: Int = 25_000
    const val MAX_DEPTH: Int = 48
    /** Above this the chat reports a summary instead of listing groups. */
    const val LARGE_TREE_NODES: Int = 400
    /** Above this only a trimmed tree may be sent to a provider (see privacy rules). */
    const val TRIM_TO_AI_BYTES: Long = 200L * 1024
}

/** Thrown when a file cannot be read at all. The message is shown verbatim in chat. */
open class SavantParserException(
    message: String,
    val format: SaveFormat? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The bytes are damaged beyond a safe rebuild (bad length, truncated payload, ...). */
class CorruptFileException(
    message: String,
    format: SaveFormat? = null,
    cause: Throwable? = null,
) : SavantParserException(message, format, cause)

/**
 * What a parser was asked to read.
 *
 * [hints] carries everything that cannot be inferred from the bytes: a codec or checksum picked
 * by a plugin, a user supplied key, or a node budget.
 */
data class ParseRequest(
    val bytes: ByteArray,
    val fileName: String?,
    val hints: ParseHints = ParseHints(),
) {
    val extension: String? get() = fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParseRequest) return false
        return bytes.contentEquals(other.bytes) && fileName == other.fileName && hints == other.hints
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + (fileName?.hashCode() ?: 0)
}

data class ParseHints(
    val codec: CodecStrategy? = null,
    val checksum: ChecksumSpec? = null,
    val pluginId: String? = null,
    val maxNodes: Int = NodeLimits.MAX_NODES,
    val cryptoKey: String? = null,
    val xorKey: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParseHints) return false
        return codec == other.codec && checksum == other.checksum && pluginId == other.pluginId &&
            maxNodes == other.maxNodes && cryptoKey == other.cryptoKey &&
            (xorKey == null && other.xorKey == null || xorKey?.contentEquals(other.xorKey) == true)
    }

    override fun hashCode(): Int {
        var result = codec?.hashCode() ?: 0
        result = 31 * result + (checksum?.hashCode() ?: 0)
        result = 31 * result + (pluginId?.hashCode() ?: 0)
        result = 31 * result + maxNodes
        result = 31 * result + (cryptoKey?.hashCode() ?: 0)
        result = 31 * result + (xorKey?.contentHashCode() ?: 0)
        return result
    }
}

/** One line of the ParseProgress log, in the order it happened. */
data class ParseLogLine(val text: String, val atMillis: Long = 0L)

/**
 * A parsed file: the faithful document model plus the field tree derived from it.
 *
 * [tree] carries `readOnlyReason` when savant can read the file but cannot promise a valid
 * rebuild (unidentified codec or checksum). In that case editing is blocked and the export
 * button never appears.
 */
data class ParseResult(
    val document: SaveDocument,
    val tree: FieldTree,
    val detection: Confidence,
    val log: List<ParseLogLine> = emptyList(),
) {
    val readOnly: Boolean get() = tree.readOnly
}

/**
 * The parse side of a format. Parsers are pure, synchronous Kotlin: they never touch Android
 * APIs and never ask a model for anything, which is what keeps writes deterministic.
 *
 * Contract:
 * - [detect] is cheap and must not throw; it scores how well this parser fits the bytes.
 * - [parse] throws [SavantParserException] for unreadable files and returns a tree with
 *   `readOnlyReason` set for files it can read but not safely rebuild.
 * - `write(document)` must round trip: parse(write(parse(bytes))) equals parse(bytes).
 */
interface SaveParser {

    val format: SaveFormat

    fun detect(bytes: ByteArray, fileName: String?): Confidence

    fun parse(request: ParseRequest): ParseResult

    /** Rebuilds the file: applies codecs, recomputes checksums, re-encodes. */
    fun write(document: SaveDocument, fileName: String? = null): ByteArray = document.toBytes()
}

/**
 * A live, editable handle on one imported file.
 *
 * Implementations keep the original structure (key order, whitespace, quoting, raw number
 * literals) so an untouched file is written back identically and a touched file changes only
 * where the user asked for it.
 */
interface SaveDocument {

    val format: SaveFormat

    val parser: SaveParser

    /** Current field tree, reflecting every applied edit. */
    val tree: FieldTree

    /** Size of the original payload in bytes. */
    val originalSize: Int

    /**
     * Validates and applies edits to the underlying document model.
     * Rejected edits (type, range, min/max) are reported, never written.
     */
    fun applyEdits(edits: List<FieldEdit>): ApplyResult

    /** Serialises the document back to bytes, recomputing any codec or checksum. */
    fun toBytes(): ByteArray
}

data class ApplyResult(
    val tree: FieldTree,
    val applied: List<FieldEdit>,
    val rejected: List<RejectedEdit>,
) {
    val allApplied: Boolean get() = rejected.isEmpty()
}

data class RejectedEdit(val edit: FieldEdit, val reason: String)
