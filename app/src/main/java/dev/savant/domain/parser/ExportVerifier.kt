package dev.savant.domain.parser

import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.displayText
import dev.savant.domain.model.SaveFormat

/** One edit that did not survive the rebuild. */
data class VerificationMismatch(
    val path: String,
    val expected: String,
    val actual: String?,
)

/**
 * Result of re-parsing a freshly rebuilt file.
 *
 * Nothing is offered for export until [ok] is true: the rebuilt bytes are parsed again and every
 * intended edit has to be present at its path with the intended value.
 */
data class VerificationReport(
    val ok: Boolean,
    val format: SaveFormat,
    val sizeBytes: Int,
    val checkedEdits: Int,
    val mismatches: List<VerificationMismatch>,
    val warnings: List<String>,
    val reparsedReadOnly: Boolean,
) {
    val summary: String
        get() = when {
            !ok -> "verification failed: ${mismatches.size} of $checkedEdits edits did not survive the rebuild"
            warnings.isNotEmpty() -> "verified with ${warnings.size} warning(s)"
            else -> "verified: $checkedEdits edit(s) present in the rebuilt file"
        }
}

/**
 * Re-parse and compare. Deterministic Kotlin, no model involved.
 */
class ExportVerifier(private val registry: ParserRegistry) {

    fun verify(
        rebuilt: ByteArray,
        fileName: String?,
        edits: List<FieldEdit>,
        parser: SaveParser,
        expectedFormat: SaveFormat = parser.format,
    ): VerificationReport {
        val warnings = mutableListOf<String>()
        val mismatches = mutableListOf<VerificationMismatch>()

        val result = runCatching {
            registry.parse(rebuilt, fileName, preferred = parser.format)
        }.getOrElse { error ->
            return VerificationReport(
                ok = false,
                format = expectedFormat,
                sizeBytes = rebuilt.size,
                checkedEdits = edits.size,
                mismatches = edits.map { VerificationMismatch(it.path, it.newText, null) },
                warnings = listOf("rebuilt file could not be parsed again: ${error.message}"),
                reparsedReadOnly = true,
            )
        }

        val tree = result.tree
        if (tree.format != expectedFormat) {
            warnings += "rebuilt file detects as ${tree.format.badge}, expected ${expectedFormat.badge}"
        }
        warnings += tree.warnings

        for (edit in edits) {
            val node = tree.node(edit.path)
            if (node == null) {
                mismatches += VerificationMismatch(edit.path, edit.newText, null)
                continue
            }
            val expected = node.copy(value = edit.newValue)
            if (!expected.sameValueAs(node)) {
                mismatches += VerificationMismatch(edit.path, edit.newText, node.value?.displayText() ?: "null")
            }
        }

        return VerificationReport(
            ok = mismatches.isEmpty(),
            format = tree.format,
            sizeBytes = rebuilt.size,
            checkedEdits = edits.size,
            mismatches = mismatches,
            warnings = warnings.distinct(),
            reparsedReadOnly = tree.readOnly,
        )
    }
}
