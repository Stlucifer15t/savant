package dev.savant.parsers.xml

/**
 * Faithful xml document model.
 *
 * Text nodes, comments, processing instructions and the doctype keep their raw source so an
 * unedited file is written back unchanged. Attribute values keep the raw (still escaped) text and
 * the quote character that was used. Whitespace *inside* a tag is normalised on write — the only
 * formatting savant does not promise to reproduce.
 */
internal sealed interface XmlItem

/** Raw text exactly as found, entities intact. Includes indentation between elements. */
internal data class XmlText(val raw: String) : XmlItem

internal data class XmlCData(val content: String) : XmlItem

/** Includes the `<!--` and `-->` markers. */
internal data class XmlComment(val raw: String) : XmlItem

/** Any processing instruction, including `<?xml ... ?>`. Includes `<?` and `?>`. */
internal data class XmlProcessing(val raw: String) : XmlItem

/** `<!DOCTYPE ...>`, internal subset included. Never resolved, never fetched. */
internal data class XmlDocType(val raw: String) : XmlItem

internal data class XmlAttribute(
    val name: String,
    var rawValue: String,
    val quote: Char = '"',
) {
    val value: String get() = XmlEntities.decode(rawValue)
}

internal class XmlElement(
    val name: String,
    val attributes: MutableList<XmlAttribute> = mutableListOf(),
    val children: MutableList<XmlItem> = mutableListOf(),
    var selfClosing: Boolean = false,
) : XmlItem {

    fun attribute(name: String): XmlAttribute? = attributes.firstOrNull { it.name == name }

    /** Direct child elements, in document order. */
    fun elements(): List<XmlElement> = children.mapNotNull { it as? XmlElement }

    /** Concatenated decoded text of the text/cdata children. */
    fun textValue(): String = buildString {
        for (child in children) {
            when (child) {
                is XmlText -> append(XmlEntities.decode(child.raw))
                is XmlCData -> append(child.content)
                else -> Unit
            }
        }
    }

    fun hasElementChildren(): Boolean = children.any { it is XmlElement }

    /** True when the element carries nothing but whitespace text. */
    fun isBlank(): Boolean = !hasElementChildren() && textValue().isBlank()

    override fun toString(): String = "XmlElement($name, attrs=${attributes.size}, children=${children.size})"
}

internal class XmlDocumentModel(
    val prolog: MutableList<XmlItem> = mutableListOf(),
    var root: XmlElement? = null,
    val epilog: MutableList<XmlItem> = mutableListOf(),
    val bom: Boolean = false,
) {
    val isPlist: Boolean
        get() {
            val doctype = prolog.filterIsInstance<XmlDocType>().any { it.raw.contains("plist", true) }
            return doctype || root?.name == "plist"
        }
}

/** Mutable handle on one editable slot: an element's text or one attribute value. */
internal class XmlRef(
    val read: () -> String,
    val write: (String) -> Unit,
    val kind: Kind,
) {
    enum class Kind { TEXT, ATTRIBUTE, PLIST_BOOL }
}

/** Entity handling. Only the five predefined entities plus numeric references — never external. */
internal object XmlEntities {

    fun decode(raw: String): String {
        if (!raw.contains('&')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = raw.indexOf(';', i + 1)
            if (end < 0 || end - i > 12) {
                out.append(c)
                i++
                continue
            }
            val entity = raw.substring(i + 1, end)
            when {
                entity == "amp" -> out.append('&')
                entity == "lt" -> out.append('<')
                entity == "gt" -> out.append('>')
                entity == "quot" -> out.append('"')
                entity == "apos" -> out.append('\'')
                entity.startsWith("#x") || entity.startsWith("#X") -> {
                    val code = entity.substring(2).toIntOrNull(16)
                    if (code == null) {
                        out.append('&').append(entity).append(';')
                    } else {
                        out.appendCodePoint(code)
                    }
                }
                entity.startsWith("#") -> {
                    val code = entity.substring(1).toIntOrNull(10)
                    if (code == null) {
                        out.append('&').append(entity).append(';')
                    } else {
                        out.appendCodePoint(code)
                    }
                }
                else -> {
                    // Unknown named entity (nbsp, hellip, ...): keep it verbatim.
                    out.append('&').append(entity).append(';')
                }
            }
            i = end + 1
        }
        return out.toString()
    }

    fun escapeText(value: String): String = buildString(value.length + 8) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\r' -> append("&#13;")
                else -> append(c)
            }
        }
    }

    fun escapeAttribute(value: String, quote: Char): String = buildString(value.length + 8) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\r' -> append("&#13;")
                '\n' -> append("&#10;")
                '\t' -> append("&#9;")
                else -> if (c == quote) append('&').append(if (c == '"') "quot" else "apos").append(';') else append(c)
            }
        }
    }
}
