package dev.savant.parsers.ini

import dev.savant.domain.model.SaveFormat
import dev.savant.domain.parser.CorruptFileException
import dev.savant.domain.parser.SavantParserException
import dev.savant.parsers.FormatDetector

/**
 * Byte based ini reader.
 *
 * Grammar, as lenient as the files in the wild need it to be:
 *
 * ```
 * ; comment            # comment
 * [section name]       ; an inline comment after the bracket is kept as text
 * key = value          key: value         key=value ; comment      name = "quoted ; not a comment"
 * ```
 *
 * - the separator is the first `=` or `:` on the line, whichever comes first, so `path=C:\games`
 *   and `url: http://x` both split where a person would split them;
 * - an inline comment starts at a `;` or `#` that begins the value or follows a blank, which is the
 *   rule python's configparser uses and the one that keeps `Program Files;x` intact;
 * - a value wrapped in matching `"` or `'` is a string, quotes excluded, and nothing inside it is a
 *   comment;
 * - keys are case preserved and never normalised; duplicate keys are the tree builder's problem;
 * - anything else — a bare word, a continuation line, `[` with no name — is kept verbatim as
 *   [IniLine.Kind.OTHER], because a parser that cannot name a line still has to write it back.
 *
 * Lines are split on `\n`; a preceding `\r` belongs to the terminator, so crlf files round trip.
 */
internal class IniReader(private val bytes: ByteArray) {

    val warnings = mutableListOf<String>()

    fun read(): IniDocumentModel {
        if (bytes.isEmpty()) throw CorruptFileException("the file is empty", SaveFormat.INI)

        val bom = FormatDetector.hasUtf8Bom(bytes)
        var pos = if (bom) 3 else 0
        val lines = mutableListOf<IniLine>()
        var unnamedBrackets = 0

        while (pos < bytes.size) {
            if (lines.size >= MAX_LINES) {
                throw SavantParserException("more than $MAX_LINES lines; this is not a save file savant will edit", SaveFormat.INI)
            }
            val lineStart = pos
            var newline = pos
            while (newline < bytes.size && bytes[newline] != LF) newline++
            var contentEnd = newline
            if (contentEnd > lineStart && bytes[contentEnd - 1] == CR) contentEnd--
            val terminatorEnd = if (newline < bytes.size) newline + 1 else newline

            val raw = bytes.copyOfRange(lineStart, contentEnd)
            val terminator = bytes.copyOfRange(contentEnd, terminatorEnd)
            val line = classify(raw, terminator)
            if (line.kind == IniLine.Kind.OTHER && raw.isNotEmpty() && raw[firstNonBlank(raw)] == OPEN_BRACKET) unnamedBrackets++
            lines += line
            pos = terminatorEnd
        }

        if (unnamedBrackets > 0) {
            warnings += "$unnamedBrackets line(s) start with '[' but name no section; they are kept as text"
        }
        return IniDocumentModel(lines, bom)
    }

    private fun classify(raw: ByteArray, terminator: ByteArray): IniLine {
        val start = firstNonBlank(raw)
        if (start >= raw.size) return IniLine(raw, terminator, IniLine.Kind.BLANK)

        val first = raw[start]
        if (first == SEMICOLON || first == HASH) return IniLine(raw, terminator, IniLine.Kind.COMMENT)

        if (first == OPEN_BRACKET) {
            val close = indexOf(raw, CLOSE_BRACKET, start + 1)
            if (close > start) {
                val name = String(raw, start + 1, close - start - 1, Charsets.UTF_8).trim()
                if (name.isNotEmpty()) {
                    return IniLine(raw, terminator, IniLine.Kind.SECTION, sectionName = name)
                }
            }
            return IniLine(raw, terminator, IniLine.Kind.OTHER)
        }

        var separator = -1
        for (i in start until raw.size) {
            val b = raw[i]
            if (b == EQUALS || b == COLON) {
                separator = i
                break
            }
        }
        if (separator <= start) return IniLine(raw, terminator, IniLine.Kind.OTHER)

        var keyEnd = separator
        while (keyEnd > start && raw[keyEnd - 1].isBlank()) keyEnd--
        val key = String(raw, start, keyEnd - start, Charsets.UTF_8)
        if (key.isEmpty()) return IniLine(raw, terminator, IniLine.Kind.OTHER)

        var valueStart = separator + 1
        while (valueStart < raw.size && raw[valueStart].isBlank()) valueStart++

        if (valueStart < raw.size && (raw[valueStart] == DOUBLE_QUOTE || raw[valueStart] == SINGLE_QUOTE)) {
            val quote = raw[valueStart]
            val close = indexOf(raw, quote, valueStart + 1)
            if (close > valueStart) {
                return IniLine(
                    raw, terminator, IniLine.Kind.ENTRY,
                    key = key,
                    valueStart = valueStart + 1,
                    valueEnd = close,
                    quote = quote,
                )
            }
        }

        var valueEnd = raw.size
        for (i in valueStart until raw.size) {
            val b = raw[i]
            if ((b == SEMICOLON || b == HASH) && (i == valueStart || raw[i - 1].isBlank())) {
                valueEnd = i
                break
            }
        }
        while (valueEnd > valueStart && raw[valueEnd - 1].isBlank()) valueEnd--

        return IniLine(raw, terminator, IniLine.Kind.ENTRY, key = key, valueStart = valueStart, valueEnd = valueEnd)
    }

    private fun firstNonBlank(raw: ByteArray): Int {
        var i = 0
        while (i < raw.size && raw[i].isBlank()) i++
        return i
    }

    private fun indexOf(raw: ByteArray, wanted: Byte, from: Int): Int {
        for (i in from until raw.size) if (raw[i] == wanted) return i
        return -1
    }

    private fun Byte.isBlank(): Boolean = this == SPACE || this == TAB

    companion object {
        /** A save file is not a log; a million lines is a database export. */
        const val MAX_LINES = 1_000_000

        private const val LF: Byte = '\n'.code.toByte()
        private const val CR: Byte = '\r'.code.toByte()
        private const val SPACE: Byte = ' '.code.toByte()
        private const val TAB: Byte = '\t'.code.toByte()
        private const val SEMICOLON: Byte = ';'.code.toByte()
        private const val HASH: Byte = '#'.code.toByte()
        private const val EQUALS: Byte = '='.code.toByte()
        private const val COLON: Byte = ':'.code.toByte()
        private const val OPEN_BRACKET: Byte = '['.code.toByte()
        private const val CLOSE_BRACKET: Byte = ']'.code.toByte()
        private const val DOUBLE_QUOTE: Byte = '"'.code.toByte()
        private const val SINGLE_QUOTE: Byte = '\''.code.toByte()
    }
}
