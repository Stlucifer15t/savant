package dev.savant.parsers.ini

/**
 * Faithful ini document model.
 *
 * One record per physical line, each holding the exact bytes it was read from plus its own line
 * terminator (`\n`, `\r\n`, or nothing for a last line without one). The writer concatenates them,
 * so an untouched file comes back byte for byte, and an edited entry has only the bytes between
 * [valueStart] and [valueEnd] replaced: indentation, the key, the separator and its spacing, the
 * quotes and the inline comment are all left where they were.
 *
 * Nothing is interpreted that does not have to be: a continuation line, a bare word, a `[` without
 * a name — all of it is [Kind.OTHER], kept verbatim and not editable.
 */
internal class IniLine(
    var raw: ByteArray,
    val terminator: ByteArray,
    val kind: Kind,
    /** The key, decoded and trimmed. Only for [Kind.ENTRY]. */
    val key: String? = null,
    /** The name between the brackets, trimmed. Only for [Kind.SECTION]. */
    val sectionName: String? = null,
    /** Byte offsets into [raw] of the value — inside the quotes when the value was quoted. */
    var valueStart: Int = -1,
    var valueEnd: Int = -1,
    /** The quote character around the value, or null when it was bare. */
    var quote: Byte? = null,
) {
    enum class Kind { BLANK, COMMENT, SECTION, ENTRY, OTHER }

    val quoted: Boolean get() = quote != null

    /** The value as text. Malformed utf-8 shows as U+FFFD but the bytes stay put until an edit. */
    val valueText: String
        get() = if (kind != Kind.ENTRY || valueStart < 0) "" else String(raw, valueStart, valueEnd - valueStart, Charsets.UTF_8)

    /**
     * Replaces the value, and nothing else, on this line.
     *
     * Returns null when the write happened, or the reason it could not. A bare value that would not
     * read back as itself — leading or trailing blanks, a `;` or `#` after a blank, or something that
     * looks like a quoted string — is wrapped in quotes rather than written in a form the next parse
     * would misread; that is the one case the quoting style changes, and it changes because it has to.
     */
    fun setValue(literal: String): String? {
        if (kind != Kind.ENTRY || valueStart < 0) return "not writable"
        if (literal.indexOf('\n') >= 0 || literal.indexOf('\r') >= 0) {
            return "an ini value cannot contain a line break"
        }
        val current = quote
        var wrapWith: Byte? = null
        if (current != null) {
            if (literal.indexOf(current.toInt().toChar()) >= 0) {
                return "a quoted ini value cannot contain its own quote character"
            }
        } else if (needsQuoting(literal)) {
            wrapWith = when {
                literal.indexOf('"') < 0 -> '"'.code.toByte()
                literal.indexOf('\'') < 0 -> '\''.code.toByte()
                else -> return "this value needs quoting and contains both quote characters"
            }
        }

        val encoded = literal.toByteArray(Charsets.UTF_8)
        val before = raw.copyOfRange(0, valueStart)
        val after = raw.copyOfRange(valueEnd, raw.size)
        val extra = if (wrapWith != null) 2 else 0
        val out = ByteArray(before.size + encoded.size + after.size + extra)
        var at = 0
        before.copyInto(out, at); at += before.size
        if (wrapWith != null) out[at++] = wrapWith
        val newStart = at
        encoded.copyInto(out, at); at += encoded.size
        val newEnd = at
        if (wrapWith != null) out[at++] = wrapWith
        after.copyInto(out, at)
        raw = out
        valueStart = newStart
        valueEnd = newEnd
        if (wrapWith != null) quote = wrapWith
        return null
    }

    private fun needsQuoting(literal: String): Boolean {
        if (literal.isEmpty()) return false
        if (literal.first().isSpaceOrTab() || literal.last().isSpaceOrTab()) return true
        if (literal.length >= 2 && (literal.first() == '"' || literal.first() == '\'') && literal.last() == literal.first()) return true
        for (i in literal.indices) {
            val c = literal[i]
            if ((c == ';' || c == '#') && (i == 0 || literal[i - 1].isSpaceOrTab())) return true
        }
        return false
    }

    private fun Char.isSpaceOrTab(): Boolean = this == ' ' || this == '\t'

    override fun toString(): String = "IniLine($kind, key=$key, section=$sectionName, bytes=${raw.size})"
}

internal class IniDocumentModel(
    val lines: MutableList<IniLine>,
    val bom: Boolean,
) {
    val entryCount: Int get() = lines.count { it.kind == IniLine.Kind.ENTRY }
    val sectionCount: Int get() = lines.count { it.kind == IniLine.Kind.SECTION }
}
