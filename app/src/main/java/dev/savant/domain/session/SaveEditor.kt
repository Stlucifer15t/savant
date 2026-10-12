package dev.savant.domain.session

import dev.savant.domain.model.AppliedEditBatch
import dev.savant.domain.model.EditSource
import dev.savant.domain.model.EditValidator
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.ImportedFile
import dev.savant.domain.model.Node
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.parser.ApplyResult
import dev.savant.domain.parser.CorruptFileException
import dev.savant.domain.parser.ExportVerifier
import dev.savant.domain.parser.NodeLimits
import dev.savant.domain.parser.ParseHints
import dev.savant.domain.parser.ParseResult
import dev.savant.domain.parser.ParserRegistry
import dev.savant.domain.parser.SavantParserException
import java.util.UUID

/** Why a file could not be opened for editing. */
data class OpenFailure(
    val headline: String,
    val detail: String,
    /** Set when savant could read the file but will not write it. */
    val session: EditorSession? = null,
) {
    val chatLines: List<String> get() = listOf(headline) + detail.split("\n").filter { it.isNotBlank() }
}

data class OpenOutcome(
    val session: EditorSession?,
    val failure: OpenFailure?,
) {
    val ok: Boolean get() = session != null && failure == null
}

/** Result of applying a batch of edits. */
data class EditOutcome(
    val session: EditorSession,
    val applied: List<FieldEdit>,
    val rejected: List<Pair<FieldEdit, String>>,
    val tree: FieldTree,
) {
    val lines: List<String>
        get() = buildList {
            if (applied.isNotEmpty()) {
                add("applied ${applied.size} edit${if (applied.size == 1) "" else "s"}")
            }
            rejected.forEach { (edit, reason) -> add("skipped ${edit.displayName}: $reason") }
            if (applied.isEmpty() && rejected.isEmpty()) add("nothing to change")
        }
}

/**
 * The deterministic half of savant: open, edit, undo, rebuild, verify.
 *
 * Nothing here consults a model. The AI proposes [FieldEdit]s through the tool layer; this class
 * validates them, applies them to the parse document and rebuilds the file.
 */
class SaveEditor(
    private val registry: ParserRegistry,
    private val verifier: ExportVerifier,
) {

    fun open(
        file: ImportedFile,
        bytes: ByteArray,
        hints: ParseHints = ParseHints(),
        preferred: SaveFormat? = null,
    ): OpenOutcome {
        if (bytes.isEmpty()) {
            return OpenOutcome(null, OpenFailure("that file is empty", "0 bytes, nothing to parse"))
        }
        if (bytes.size.toLong() > NodeLimits.MAX_FILE_BYTES) {
            return OpenOutcome(
                null,
                OpenFailure(
                    "that file is too large",
                    "${bytes.size / 1_048_576} mb is over the ${NodeLimits.MAX_FILE_BYTES / 1_048_576} mb limit",
                ),
            )
        }

        val detection = registry.best(bytes, file.name)
        if (detection == null && preferred == null) {
            val guess = dev.savant.parsers.FormatDetector.guess(bytes, file.name).firstOrNull()
            val blocker = dev.savant.parsers.FormatDetector.describeBlocker(bytes, file.name)
            return OpenOutcome(
                null,
                OpenFailure(
                    "no parser recognised this file",
                    buildString {
                        append(blocker)
                        if (guess != null) append("\nit looks most like ${guess.first.label} (${guess.second.label})")
                        append("\nnothing was modified")
                    },
                ),
            )
        }

        return try {
            val result: ParseResult = registry.parse(bytes, file.name, preferred, hints)
            val session = EditorSession(
                id = UUID.randomUUID().toString(),
                file = file,
                parser = result.document.parser,
                document = result.document,
                detection = result.detection,
                log = result.log,
            )
            if (result.tree.isEmpty) {
                OpenOutcome(
                    session,
                    OpenFailure(
                        "parsed, but there is nothing to edit",
                        "the file holds no addressable values",
                        session,
                    ),
                )
            } else {
                OpenOutcome(session, null)
            }
        } catch (error: CorruptFileException) {
            OpenOutcome(
                null,
                OpenFailure("that file is corrupt", error.message ?: "unreadable structure"),
            )
        } catch (error: SavantParserException) {
            OpenOutcome(null, OpenFailure("could not open that file", error.message ?: "unknown parser error"))
        } catch (error: Exception) {
            OpenOutcome(
                null,
                OpenFailure("could not open that file", "${error.javaClass.simpleName}: ${error.message}"),
            )
        }
    }

    /** Validates and applies one batch. Rejected edits are reported, never written. */
    fun apply(session: EditorSession, edits: List<FieldEdit>, note: String? = null): EditOutcome {
        val usable = edits.filterNot { it.isNoOp }
        if (usable.isEmpty()) return EditOutcome(session, emptyList(), emptyList(), session.tree)

        val result: ApplyResult = session.document.applyEdits(usable)
        val rejected = result.rejected.map { it.edit to it.reason }
        if (result.applied.isEmpty()) return EditOutcome(session, emptyList(), rejected, session.tree)

        val batch = AppliedEditBatch(
            id = UUID.randomUUID().toString(),
            edits = result.applied,
            appliedAt = System.currentTimeMillis(),
            note = note,
        )
        return EditOutcome(session.withTreeChanges(batch), result.applied, rejected, session.document.tree)
    }

    /** Reverts the most recent batch. Values go back, the file is rebuilt on demand. */
    fun undo(session: EditorSession): EditOutcome {
        val last = session.history.lastOrNull()
            ?: return EditOutcome(session, emptyList(), emptyList(), session.tree)
        val result = session.document.applyEdits(last.edits.map { it.revert() })
        val undone = session.copy(history = session.history.dropLast(1), export = null)
        return EditOutcome(
            session = undone,
            applied = result.applied.map { it.copy(source = EditSource.UNDO) },
            rejected = result.rejected.map { it.edit to it.reason },
            tree = undone.tree,
        )
    }

    /**
     * Rebuilds the file, re-parses it and verifies every intended edit survived.
     * The artifact is only offered for export when [ExportArtifact.usable].
     */
    fun rebuild(session: EditorSession, fileName: String? = null): ExportArtifact {
        val bytes = session.document.toBytes()
        val name = fileName ?: exportNameFor(session.file.name)
        val report = verifier.verify(
            rebuilt = bytes,
            fileName = name,
            edits = session.appliedEdits,
            parser = session.parser,
            expectedFormat = session.format,
        )
        return ExportArtifact(fileName = name, bytes = bytes, report = report)
    }

    /** Never overwrite the original: `save.json` becomes `save.edited.json`. */
    fun exportNameFor(name: String, format: SaveFormat? = null): String {
        val extension = name.substringAfterLast('.', "")
        val base = if (extension.isEmpty()) name else name.substringBeforeLast('.')
        val suffix = "edited"
        return if (extension.isEmpty()) "$base.$suffix" else "$base.$suffix.$extension"
    }

    /** One proposed edit for a node, validated up front. */
    fun propose(node: Node, input: String, source: EditSource): Pair<FieldEdit?, String?> =
        proposeValue(node, EditValidator.coerce(node, input), source)

    /** Same, for a value that is already typed (tool calls, quick chips, the finder). */
    fun proposeValue(node: Node, value: FieldValue, source: EditSource): Pair<FieldEdit?, String?> {
        val validation = EditValidator.validate(node, value)
        if (!validation.ok) return null to (validation.reason ?: "invalid value")
        val edit = FieldEdit.of(node, value, source)
        if (edit.isNoOp) return null to "already ${edit.newText}"
        return edit to null
    }
}
