package dev.savant.parsers.json

import dev.savant.domain.parser.CorruptFileException
import dev.savant.domain.parser.NodeLimits
import dev.savant.domain.parser.SavantParserException

/**
 * Recursive descent json reader over raw bytes.
 *
 * Byte based on purpose: a 50 MB save must not become a 100 MB String before parsing.
 * Lenient where games are lenient (comments, unquoted keys, trailing commas, single quotes,
 * NaN/Infinity) and loud about it — every deviation lands in [warnings] and is reported in chat,
 * because a lenient read still has to produce a file the game accepts.
 */
internal class JsonReader(private val bytes: ByteArray) {

    private var pos = 0
    private var depth = 0
    private var nodeCount = 0L

    val warnings = mutableListOf<String>()
    var trailing: ByteArray = ByteArray(0)
        private set
    var style: JsonStyle = JsonStyle()
        private set

    private var bom = false
    private var spaceAfterColon = false
    private var spaceAfterComma = false
    private var sawColonSpacing = false
    private var sawCommaSpacing = false
    private var indentSample: String? = null
    private var sawNewlineInContainer = false
    private var trailingNewline = false
    private val warned = mutableSetOf<String>()

    val parsedNodes: Long get() = nodeCount

    fun read(): JsonNode {
        if (bytes.isEmpty()) throw CorruptFileException("the file is empty")
        detectBom()
        trailingNewline = bytes[bytes.size - 1] == NEWLINE || bytes[bytes.size - 1] == CARRIAGE
        skipWhitespace()
        if (pos >= bytes.size) throw CorruptFileException("the file contains only whitespace")

        val root = readValue()
        skipWhitespace()
        if (pos < bytes.size) {
            trailing = bytes.copyOfRange(pos, bytes.size)
            warnings += "ignored ${trailing.size} byte(s) after the top level value"
        }
        style = JsonStyle(
            indent = if (sawNewlineInContainer) (indentSample ?: "") else null,
            spaceAfterColon = spaceAfterColon,
            spaceAfterComma = spaceAfterComma,
            trailingNewline = trailingNewline,
            bom = bom,
        )
        return root
    }

    private fun detectBom() {
        if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) {
            bom = true
            pos = 3
        }
    }

    private fun readValue(): JsonNode {
        if (depth > NodeLimits.MAX_DEPTH) {
            throw CorruptFileException("json is nested deeper than ${NodeLimits.MAX_DEPTH} levels at ${position()}")
        }
        if (nodeCount > MAX_DOCUMENT_NODES) {
            throw SavantParserException(
                "this file holds more than $MAX_DOCUMENT_NODES values, which is more than savant will keep in memory on a phone",
            )
        }
        if (pos >= bytes.size) throw CorruptFileException("unexpected end of file at ${position()}")
        val node = when (val c = bytes[pos]) {
            OPEN_BRACE -> readObject()
            OPEN_BRACKET -> readArray()
            QUOTE, SINGLE_QUOTE -> readStringNode()
            LETTER_T -> readLiteral("true", JBool(true))
            LETTER_F -> readLiteral("false", JBool(false))
            LETTER_N -> readNullish()
            LETTER_I -> readInfinity(false)
            else -> if (c == MINUS || c == PLUS || c.isAsciiDigit()) {
                readNumber()
            } else {
                throw CorruptFileException("unexpected character '${c.toPrintable()}' at ${position()}")
            }
        }
        nodeCount++
        return node
    }

    private fun readObject(): JsonNode {
        expect(OPEN_BRACE)
        depth++
        val obj = JObject()
        var multiline = skipWhitespace()
        if (peek() == CLOSE_BRACE) {
            pos++
            depth--
            obj.multiline = multiline
            return obj
        }
        while (true) {
            multiline = skipWhitespace() || multiline
            val keyRaw = when (val c = peek()) {
                QUOTE, SINGLE_QUOTE -> {
                    val node = readStringNode() as JString
                    node.raw ?: quote(node.value)
                }
                CLOSE_BRACE -> null
                null -> throw CorruptFileException("unterminated object at ${position()}")
                else -> if (c.isAsciiLetter() || c == UNDERSCORE || c == DOLLAR || c.isAsciiDigit()) {
                    warnOnce("unquoted object keys")
                    readUnquotedKey()
                } else {
                    throw CorruptFileException("expected a key but found '${c.toPrintable()}' at ${position()}")
                }
            }
            if (keyRaw == null) {
                // Trailing comma before the closing brace.
                if (peek() == CLOSE_BRACE) {
                    warnOnce("trailing comma")
                    pos++
                    break
                }
                throw CorruptFileException("expected a key at ${position()}")
            }
            val key = unquote(keyRaw)

            multiline = skipWhitespace() || multiline
            if (peek() != COLON) throw CorruptFileException("expected ':' after key '$key' at ${position()}")
            pos++
            if (!sawColonSpacing) {
                sawColonSpacing = true
                spaceAfterColon = peek() == SPACE
            }
            multiline = skipWhitespace() || multiline

            obj.members += JMember(keyRaw, key, readValue())

            multiline = skipWhitespace() || multiline
            when (peek()) {
                COMMA -> {
                    pos++
                    recordCommaSpacing()
                }
                CLOSE_BRACE -> {
                    pos++
                    break
                }
                null -> throw CorruptFileException("unterminated object after key '$key'")
                else -> throw CorruptFileException("expected ',' or '}' after the value of '$key' at ${position()}")
            }
        }
        depth--
        obj.multiline = multiline
        return obj
    }

    private fun readArray(): JsonNode {
        expect(OPEN_BRACKET)
        depth++
        val array = JArray()
        var multiline = skipWhitespace()
        if (peek() == CLOSE_BRACKET) {
            pos++
            depth--
            array.multiline = multiline
            return array
        }
        while (true) {
            multiline = skipWhitespace() || multiline
            if (peek() == CLOSE_BRACKET) {
                warnOnce("trailing comma")
                pos++
                break
            }
            array.items += readValue()
            multiline = skipWhitespace() || multiline
            when (peek()) {
                COMMA -> {
                    pos++
                    recordCommaSpacing()
                }
                CLOSE_BRACKET -> {
                    pos++
                    break
                }
                null -> throw CorruptFileException("unterminated array")
                else -> throw CorruptFileException("expected ',' or ']' in array at ${position()}")
            }
        }
        depth--
        array.multiline = multiline
        return array
    }

    private fun readStringNode(): JsonNode {
        val quoteChar = bytes[pos]
        if (quoteChar == SINGLE_QUOTE) warnOnce("single quoted strings")
        val start = pos
        pos++
        val text = StringBuilder()
        var escaped = false
        while (true) {
            if (pos >= bytes.size) throw CorruptFileException("unterminated string starting at ${position(start)}")
            val c = bytes[pos]
            if (escaped) {
                when (c) {
                    QUOTE -> text.append('"')
                    SINGLE_QUOTE -> text.append('\'')
                    BACKSLASH -> text.append('\\')
                    SLASH -> text.append('/')
                    LETTER_B -> text.append('\b')
                    LETTER_F -> text.append('\u000C')
                    LETTER_N -> text.append('\n')
                    LETTER_R -> text.append('\r')
                    LETTER_T -> text.append('\t')
                    LETTER_U -> {
                        pos++
                        val high = readHex4()
                        val paired = high.isHighSurrogate() &&
                            pos + 5 < bytes.size && bytes[pos] == BACKSLASH && bytes[pos + 1] == LETTER_U
                        if (paired) {
                            pos += 2
                            val low = readHex4()
                            if (low.isLowSurrogate()) {
                                val codePoint = 0x10000 + ((high - '\uD800') shl 10) + (low - '\uDC00')
                                text.appendCodePoint(codePoint)
                            } else {
                                text.append(high)
                                text.append(low)
                            }
                        } else {
                            text.append(high)
                        }
                        escaped = false
                        continue // pos already points past the escape
                    }
                    else -> {
                        warnOnce("unknown escape '\\${c.toPrintable()}'")
                        text.append(c.toInt().toChar())
                    }
                }
                escaped = false
                pos++
                continue
            }
            when {
                c == BACKSLASH -> escaped = true
                c == quoteChar -> {
                    pos++
                    val raw = String(bytes, start, pos - start, Charsets.UTF_8)
                    return JString(text.toString(), raw)
                }
                c == NEWLINE -> {
                    warnOnce("newline inside a string literal")
                    text.append('\n')
                }
                else -> text.append(c.toInt().toChar())
            }
            pos++
        }
    }

    private fun readUnquotedKey(): String {
        val start = pos
        while (pos < bytes.size) {
            val c = bytes[pos]
            if (c.isAsciiLetter() || c.isAsciiDigit() || c == UNDERSCORE || c == DOLLAR || c == MINUS) pos++ else break
        }
        return String(bytes, start, pos - start, Charsets.UTF_8)
    }

    private fun readHex4(): Char {
        if (pos + 4 > bytes.size) throw CorruptFileException("truncated \\u escape at ${position()}")
        val text = String(bytes, pos, 4, Charsets.US_ASCII)
        val code = text.toIntOrNull(16) ?: throw CorruptFileException("bad \\u escape '\\u$text' at ${position()}")
        pos += 4
        return code.toChar()
    }

    private fun readLiteral(text: String, node: JsonNode): JsonNode {
        if (matchesAhead(text)) {
            pos += text.length
            return node
        }
        return readWordFallback()
    }

    private fun readNullish(): JsonNode {
        if (matchesAhead("null")) {
            pos += 4
            return JNull
        }
        if (matchesAhead("NaN")) {
            pos += 3
            warnOnce("NaN literal")
            return JNumber("NaN", Double.NaN, false)
        }
        return readWordFallback()
    }

    private fun readInfinity(negative: Boolean): JsonNode {
        if (!matchesAhead("Infinity")) return readWordFallback()
        pos += "Infinity".length
        warnOnce("Infinity literal")
        return JNumber(
            (if (negative) "-" else "") + "Infinity",
            if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY,
            false,
        )
    }

    private fun readWordFallback(): JsonNode {
        val start = pos
        while (pos < bytes.size && bytes[pos].isAsciiLetter()) pos++
        val word = String(bytes, start, pos - start, Charsets.US_ASCII)
        throw CorruptFileException("unexpected token '$word' at ${position(start)}")
    }

    private fun matchesAhead(text: String): Boolean {
        if (pos + text.length > bytes.size) return false
        for (i in text.indices) {
            if (bytes[pos + i] != text[i].code.toByte()) return false
        }
        return true
    }

    private fun readNumber(): JsonNode {
        val start = pos
        val negative = peek() == MINUS
        if (peek() == MINUS || peek() == PLUS) pos++
        if (matchesAhead("Infinity")) {
            pos += "Infinity".length
            warnOnce("Infinity literal")
            return JNumber(
                String(bytes, start, pos - start, Charsets.US_ASCII),
                if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY,
                false,
            )
        }
        if (matchesAhead("NaN")) {
            val raw = String(bytes, start, pos + 3 - start, Charsets.US_ASCII)
            pos += 3
            warnOnce("NaN literal")
            return JNumber(raw, Double.NaN, false)
        }

        var sawDot = false
        var sawExponent = false
        outer@ while (pos < bytes.size && pos - start < MAX_NUMBER_LENGTH) {
            val c = bytes[pos]
            when {
                c.isAsciiDigit() -> pos++
                c == DOT && !sawDot && !sawExponent -> {
                    sawDot = true
                    pos++
                }
                (c == LETTER_E || c == LETTER_E_UPPER) && !sawExponent && pos > start -> {
                    sawExponent = true
                    pos++
                    if (peek() == PLUS || peek() == MINUS) pos++
                }
                else -> break@outer
            }
        }
        val raw = String(bytes, start, pos - start, Charsets.US_ASCII)
        if (raw.isEmpty()) throw CorruptFileException("expected a number at ${position(start)}")

        val asLong = raw.toLongOrNull()
        if (asLong != null) return JNumber(raw, asLong.toDouble(), true)
        val asDouble = raw.toDoubleOrNull()
        if (asDouble != null) return JNumber(raw, asDouble, !sawDot && !sawExponent)
        warnOnce("number literal '$raw' is not valid json")
        return JNumber(raw, null, false)
    }

    /**
     * Records the compact-style comma spacing. A comma followed by a newline belongs to a
     * multiline container and must not decide how single line containers are written.
     */
    private fun recordCommaSpacing() {
        if (sawCommaSpacing) return
        val next = peek() ?: return
        if (next == NEWLINE || next == CARRIAGE) return
        sawCommaSpacing = true
        spaceAfterComma = next == SPACE
    }

    /** Skips whitespace and comments. Returns true when a newline was crossed. */
    private fun skipWhitespace(): Boolean {
        var newline = false
        while (pos < bytes.size) {
            when (val c = bytes[pos]) {
                NEWLINE -> {
                    newline = true
                    sawNewlineInContainer = true
                    pos++
                    recordIndent()
                }
                CARRIAGE, SPACE, TAB -> pos++
                SLASH -> {
                    when (bytes.getOrNull(pos + 1)) {
                        SLASH -> {
                            warnOnce("line comments")
                            while (pos < bytes.size && bytes[pos] != NEWLINE) pos++
                        }
                        STAR -> {
                            warnOnce("block comments")
                            pos += 2
                            while (pos + 1 < bytes.size && !(bytes[pos] == STAR && bytes[pos + 1] == SLASH)) {
                                if (bytes[pos] == NEWLINE) {
                                    newline = true
                                    sawNewlineInContainer = true
                                    pos++
                                    recordIndent()
                                } else {
                                    pos++
                                }
                            }
                            pos = minOf(pos + 2, bytes.size)
                        }
                        else -> return newline
                    }
                }
                else -> return newline
            }
        }
        return newline
    }

    private fun recordIndent() {
        val start = pos
        var end = start
        while (end < bytes.size && (bytes[end] == SPACE || bytes[end] == TAB)) end++
        if (end > start && end < bytes.size && bytes[end] != NEWLINE && bytes[end] != CARRIAGE) {
            val candidate = String(bytes, start, end - start, Charsets.US_ASCII)
            val current = indentSample
            if (current == null || candidate.length < current.length) indentSample = candidate
        }
        pos = end
    }

    private fun warnOnce(what: String) {
        if (warned.add(what)) warnings += "non standard json: $what (kept on read, normalised on export)"
    }

    private fun peek(): Byte? = bytes.getOrNull(pos)

    private fun expect(expected: Byte) {
        if (peek() != expected) {
            throw CorruptFileException(
                "expected '${expected.toPrintable()}' at ${position()} but found '${peek()?.toPrintable() ?: "end of file"}'",
            )
        }
        pos++
    }

    private fun position(offset: Int = pos): String {
        var line = 1
        var column = 1
        val limit = offset.coerceAtMost(bytes.size)
        for (i in 0 until limit) {
            if (bytes[i] == NEWLINE) {
                line++
                column = 1
            } else {
                column++
            }
        }
        return "line $line, column $column"
    }

    companion object {
        /** Above this the document is refused rather than risking an OOM on a phone. */
        const val MAX_DOCUMENT_NODES = 1_000_000L
        private const val MAX_NUMBER_LENGTH = 64

        private const val OPEN_BRACE = '{'.code.toByte()
        private const val CLOSE_BRACE = '}'.code.toByte()
        private const val OPEN_BRACKET = '['.code.toByte()
        private const val CLOSE_BRACKET = ']'.code.toByte()
        private const val QUOTE = '"'.code.toByte()
        private const val SINGLE_QUOTE = '\''.code.toByte()
        private const val COMMA = ','.code.toByte()
        private const val COLON = ':'.code.toByte()
        private const val DOT = '.'.code.toByte()
        private const val MINUS = '-'.code.toByte()
        private const val PLUS = '+'.code.toByte()
        private const val SPACE = ' '.code.toByte()
        private const val TAB = '\t'.code.toByte()
        private const val NEWLINE = '\n'.code.toByte()
        private const val CARRIAGE = '\r'.code.toByte()
        private const val BACKSLASH = '\\'.code.toByte()
        private const val SLASH = '/'.code.toByte()
        private const val STAR = '*'.code.toByte()
        private const val UNDERSCORE = '_'.code.toByte()
        private const val DOLLAR = '$'.code.toByte()
        private const val LETTER_B = 'b'.code.toByte()
        private const val LETTER_E = 'e'.code.toByte()
        private const val LETTER_E_UPPER = 'E'.code.toByte()
        private const val LETTER_F = 'f'.code.toByte()
        private const val LETTER_I = 'I'.code.toByte()
        private const val LETTER_N = 'n'.code.toByte()
        private const val LETTER_R = 'r'.code.toByte()
        private const val LETTER_T = 't'.code.toByte()
        private const val LETTER_U = 'u'.code.toByte()

        private fun Byte.isAsciiDigit(): Boolean = this in '0'.code.toByte()..'9'.code.toByte()
        private fun Byte.isAsciiLetter(): Boolean =
            this in 'a'.code.toByte()..'z'.code.toByte() || this in 'A'.code.toByte()..'Z'.code.toByte()

        private fun Byte?.toPrintable(): String = when (this) {
            null -> ""
            NEWLINE -> "\\n"
            TAB -> "\\t"
            CARRIAGE -> "\\r"
            else -> (toInt().toChar()).toString()
        }

        private fun Char.isHighSurrogate(): Boolean = this in '\uD800'..'\uDBFF'
        private fun Char.isLowSurrogate(): Boolean = this in '\uDC00'..'\uDFFF'

        fun quote(text: String): String = buildString {
            append('"')
            for (c in text) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }

        /** Decodes a quoted (or bare) key literal. */
        fun unquote(raw: String): String {
            val first = raw.firstOrNull() ?: return raw
            val last = raw.lastOrNull()
            if (raw.length >= 2 && (first == '"' || first == '\'') && last == first) {
                val reader = JsonReader(raw.toByteArray(Charsets.UTF_8))
                return (reader.readStringNode() as JString).value
            }
            return raw
        }
    }
}
