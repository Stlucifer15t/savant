package dev.savant.parsers.json

/**
 * Faithful json document model.
 *
 * It keeps what a generic json tree throws away: the exact number literal, the exact quoting and
 * escapes of every string, key order, duplicate keys, whether a container spanned multiple lines,
 * and any bytes trailing the root value. That is what lets savant hand back a file where only the
 * edited values changed.
 */
internal sealed interface JsonNode

internal data object JNull : JsonNode

internal data class JBool(val value: Boolean) : JsonNode

/**
 * A number as written in the file. [raw] is what gets written back; [value] is the parsed
 * double (null for literals like `1.2.3` that no parser would accept but some games emit).
 */
internal data class JNumber(val raw: String, val value: Double?, val integral: Boolean) : JsonNode

/** [raw] is the original quoted literal including escapes, kept until the value is edited. */
internal data class JString(val value: String, val raw: String?) : JsonNode {
    fun withValue(next: String) = JString(next, null)
}

internal data class JArray(
    val items: MutableList<JsonNode> = mutableListOf(),
    var multiline: Boolean = false,
) : JsonNode

internal data class JObject(
    val members: MutableList<JMember> = mutableListOf(),
    var multiline: Boolean = false,
) : JsonNode {
    fun indexOf(key: String): Int = members.indexOfFirst { it.key == key }
}

internal class JMember(val keyRaw: String?, val key: String, var value: JsonNode)

/**
 * Formatting recovered from the source so an untouched file is written back identically.
 * `indent == null` means the document was compact (single line).
 */
internal data class JsonStyle(
    val indent: String? = null,
    val spaceAfterColon: Boolean = false,
    val spaceAfterComma: Boolean = false,
    val trailingNewline: Boolean = false,
    val bom: Boolean = false,
) {
    val compact: Boolean get() = indent == null

    companion object {
        val COMPACT = JsonStyle()
        val PRETTY_TWO = JsonStyle(indent = "  ", spaceAfterColon = true, trailingNewline = true)
    }
}

/** Mutable handle on one leaf inside the document, captured while the field tree is built. */
internal class JsonRef(
    val read: () -> JsonNode,
    val write: (JsonNode) -> Unit,
)
