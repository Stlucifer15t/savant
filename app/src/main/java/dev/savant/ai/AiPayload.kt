package dev.savant.ai

import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.Node
import dev.savant.domain.model.displayText
import dev.savant.domain.parser.NodeLimits

/**
 * What may ever be sent to a provider or a web tool.
 *
 * Rules, enforced here rather than in each client:
 * - raw file bytes never go out. Ever. There is no method on this object that takes a ByteArray.
 * - a save larger than [NodeLimits.TRIM_TO_AI_BYTES] is trimmed to field names and types, and its
 *   values are withheld whatever the setting says
 * - long free text values are dropped even when values are allowed: a save holds player names,
 *   paths and chat logs, and none of those help a model edit a number
 * - keys and tokens are redacted to their last four characters in anything that is logged
 */
object AiPayload {

    fun mustTrim(sizeBytes: Long): Boolean =
        sizeBytes > NodeLimits.TRIM_TO_AI_BYTES

    /** What a provider is told about the open file, and what was left out and why. */
    data class Summary(
        val text: String,
        val valuesIncluded: Boolean,
        val fieldsIncluded: Int,
        val withheldReason: String? = null,
    ) {
        /** Printed in the chat once per remote answer, so the rule is visible where it applies. */
        val note: String?
            get() = withheldReason?.let { "sent field names and types only — $it" }
    }

    /**
     * The field listing handed to a model.
     *
     * [sendValues] is the user's switch; [fileSizeBytes] is the opened file. Values are dropped for
     * a large file even when the switch is on, because 200 kb of numbers is both expensive and
     * useless: an edit is proposed by path, and the path is in the listing either way.
     */
    fun summaryFor(
        tree: FieldTree,
        fileSizeBytes: Long,
        sendValues: Boolean,
        maxFields: Int = MAX_FIELDS,
    ): Summary {
        val tooBig = mustTrim(fileSizeBytes)
        val includeValues = sendValues && !tooBig
        val nodes = tree.editableNodes.take(maxFields)
        val lines = nodes.map { node ->
            val value = if (includeValues && !holdsLongText(node)) " = ${node.value?.displayText()}" else ""
            "${node.path}: ${node.type.tag}$value"
        }
        val header = buildString {
            append("${tree.format.badge.lowercase()} save, ${tree.editableCount} editable values")
            if (tree.truncated) append(", view truncated")
            if (tree.readOnly) append(", read only")
            if (nodes.size < tree.editableCount) append(", showing first ${nodes.size}")
        }
        val reason = when {
            tooBig -> "this file is ${fileSizeBytes / 1024} kb, over the ${NodeLimits.TRIM_TO_AI_BYTES / 1024} kb limit"
            !sendValues -> "send field values is off in settings"
            else -> null
        }
        return Summary(
            text = (listOf(header) + lines).joinToString("\n"),
            valuesIncluded = includeValues,
            fieldsIncluded = nodes.size,
            withheldReason = reason,
        )
    }

    /**
     * A trimmed field listing: paths and types only, optionally values when the user allowed it
     * and the file is small. Kept for callers that already know which of the two they want.
     */
    fun fieldSummary(
        tree: FieldTree,
        includeValues: Boolean,
        maxFields: Int = 400,
    ): String {
        val nodes = tree.editableNodes.take(maxFields)
        val lines = nodes.map { node ->
            val value = if (includeValues && !holdsLongText(node)) " = ${node.value?.displayText()}" else ""
            "${node.path}: ${node.type.tag}$value"
        }
        val header = buildString {
            append("${tree.format.badge} save, ${tree.editableCount} editable values")
            if (tree.truncated) append(", view truncated")
            if (!includeValues) append(", values withheld")
            if (nodes.size < tree.editableCount) append(", showing ${nodes.size}")
        }
        return (listOf(header) + lines).joinToString("\n")
    }

    private fun holdsLongText(node: Node): Boolean {
        val text = (node.value as? FieldValue.Text)?.value ?: return false
        return text.length > LONG_TEXT_CHARS
    }

    const val MAX_FIELDS = 400
    private const val LONG_TEXT_CHARS = 256

    /** `sk-ant-api03-...-abcd` -> `****abcd`. Used by every log and the network activity list. */
    fun redactKey(key: String?): String {
        if (key.isNullOrBlank()) return ""
        val tail = key.takeLast(4)
        return if (key.length <= 4) "****" else "****$tail"
    }

    /** Scrubs anything that looks like a bearer token or key from a message before it is logged. */
    fun redactText(text: String): String = text
        .replace(Regex("(?i)(authorization\\s*[:=]\\s*)\\S+"), "$1****")
        .replace(Regex("(?i)(api[-_]?key\\s*[\\\"']?\\s*[:=]\\s*[\\\"']?)[A-Za-z0-9_\\-._]+"), "$1****")
        .replace(Regex("(?i)(x-api-key\\s*[:=]\\s*)\\S+"), "$1****")
        .replace(Regex("(?i)(x-goog-api-key\\s*[:=]\\s*)\\S+"), "$1****")
        .replace(Regex("sk-[A-Za-z0-9_\\-]{8,}")) { match -> "****" + match.value.takeLast(4) }
        .replace(Regex("AIza[A-Za-z0-9_\\-]{10,}")) { match -> "****" + match.value.takeLast(4) }
}
