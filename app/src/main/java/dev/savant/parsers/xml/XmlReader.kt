package dev.savant.parsers.xml

import dev.savant.domain.parser.CorruptFileException
import dev.savant.domain.parser.NodeLimits
import dev.savant.domain.parser.SavantParserException

/**
 * Byte based xml reader.
 *
 * No DTD resolution, no external entities, no includes: anything that would make the parser fetch
 * something is kept as raw text. Unknown named entities survive verbatim.
 */
internal class XmlReader(private val bytes: ByteArray) {

    private var pos = 0
    private var itemCount = 0L

    val warnings = mutableListOf<String>()
    var bom = false
        private set

    fun read(): XmlDocumentModel {
        if (bytes.isEmpty()) throw CorruptFileException("the file is empty")
        detectBom()

        val model = XmlDocumentModel(bom = bom)
        val stack = ArrayDeque<XmlElement>()

        while (pos < bytes.size) {
            budget()
            when {
                matches("<!--") -> container(stack, model) += readComment()
                matches("<![CDATA[") -> container(stack, model) += readCData()
                matches("<?") -> container(stack, model) += readProcessing()
                matches("<!DOCTYPE", ignoreCase = true) -> {
                    if (stack.isNotEmpty()) warnings += "doctype inside an element is ignored"
                    model.prolog += readDocType()
                }
                matches("<!") -> {
                    warnings += "skipped an unknown <! declaration"
                    val end = indexOfByte('>'.code.toByte(), pos)
                    pos = if (end < 0) bytes.size else end + 1
                }
                matches("</") -> {
                    val name = readCloseTag()
                    val open = stack.removeLastOrNull()
                        ?: throw CorruptFileException("closing tag </$name> without an open element at ${position()}")
                    if (open.name != name) {
                        throw CorruptFileException("mismatched tag: <${open.name}> closed by </$name> at ${position()}")
                    }
                }
                matches("<") && pos + 1 < bytes.size && bytes[pos + 1].isNameStart() -> {
                    val element = readOpenTag()
                    when {
                        stack.isNotEmpty() -> stack.last().children += element
                        model.root == null -> model.root = element
                        else -> {
                            warnings += "more than one root element; the extra one is kept but not addressable"
                            model.epilog += element
                        }
                    }
                    if (!element.selfClosing) {
                        if (stack.size >= NodeLimits.MAX_DEPTH) {
                            throw CorruptFileException("xml is nested deeper than ${NodeLimits.MAX_DEPTH} levels at ${position()}")
                        }
                        stack += element
                    }
                }
                else -> {
                    val text = readText()
                    if (text.raw.isNotEmpty()) {
                        container(stack, model) += text
                        if (stack.isEmpty() && text.raw.isNotBlank()) {
                            warnings += "text outside the root element is kept but not editable"
                        }
                    }
                }
            }
        }

        if (stack.isNotEmpty()) {
            throw CorruptFileException("unclosed element <${stack.last().name}> at end of file")
        }
        if (model.root == null) throw CorruptFileException("no root element found")
        return model
    }

    /** Items before the root go to the prolog, items inside an element to that element, the rest to the epilog. */
    private fun container(stack: ArrayDeque<XmlElement>, model: XmlDocumentModel): MutableList<XmlItem> = when {
        stack.isNotEmpty() -> stack.last().children
        model.root == null -> model.prolog
        else -> model.epilog
    }

    private fun detectBom() {
        if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) {
            bom = true
            pos = 3
        }
    }

    private fun budget() {
        itemCount++
        if (itemCount > MAX_ITEMS) {
            throw SavantParserException(
                "this xml holds more than $MAX_ITEMS items, which is more than savant will keep in memory on a phone",
            )
        }
    }

    private fun readComment(): XmlItem {
        val start = pos
        pos += 4
        val end = indexOfSequence(byteArrayOf('-'.code.toByte(), '-'.code.toByte(), '>'.code.toByte()), pos)
        if (end < 0) throw CorruptFileException("unterminated comment at ${position(start)}")
        pos = end + 3
        return XmlComment(slice(start, pos))
    }

    private fun readCData(): XmlItem {
        pos += 9
        val start = pos
        val end = indexOfSequence(byteArrayOf(']'.code.toByte(), ']'.code.toByte(), '>'.code.toByte()), pos)
        if (end < 0) throw CorruptFileException("unterminated cdata at ${position(start)}")
        val content = slice(start, end)
        pos = end + 3
        return XmlCData(content)
    }

    private fun readProcessing(): XmlItem {
        val start = pos
        pos += 2
        val end = indexOfSequence(byteArrayOf('?'.code.toByte(), '>'.code.toByte()), pos)
        if (end < 0) throw CorruptFileException("unterminated processing instruction at ${position(start)}")
        pos = end + 2
        return XmlProcessing(slice(start, pos))
    }

    private fun readDocType(): XmlItem {
        val start = pos
        var depth = 0
        while (pos < bytes.size) {
            val c = bytes[pos]
            when {
                c == '['.code.toByte() -> depth++
                c == ']'.code.toByte() -> depth--
                c == '>'.code.toByte() && depth <= 0 -> {
                    pos++
                    return XmlDocType(slice(start, pos))
                }
            }
            pos++
        }
        throw CorruptFileException("unterminated doctype at ${position(start)}")
    }

    private fun readOpenTag(): XmlElement {
        val start = pos
        pos++ // '<'
        val name = readName()
        if (name.isEmpty()) throw CorruptFileException("element without a name at ${position(start)}")
        val element = XmlElement(name)

        while (true) {
            skipInlineWhitespace()
            if (pos >= bytes.size) throw CorruptFileException("unterminated tag <$name> at ${position(start)}")
            val c = bytes[pos]
            if (c == '/'.code.toByte() && pos + 1 < bytes.size && bytes[pos + 1] == '>'.code.toByte()) {
                pos += 2
                element.selfClosing = true
                return element
            }
            if (c == '>'.code.toByte()) {
                pos++
                return element
            }
            val attrName = readName()
            if (attrName.isEmpty()) {
                // Stray character inside the tag: skip it rather than failing on a sloppy save.
                warnings += "skipped an unexpected character inside <$name>"
                pos++
                continue
            }
            skipInlineWhitespace()
            if (pos < bytes.size && bytes[pos] == '='.code.toByte()) {
                pos++
                skipInlineWhitespace()
                val quote = if (pos < bytes.size && (bytes[pos] == '"'.code.toByte() || bytes[pos] == '\''.code.toByte())) {
                    val q = bytes[pos].toInt().toChar()
                    pos++
                    val valueStart = pos
                    val end = indexOfByte(q.code.toByte(), pos)
                    if (end < 0) throw CorruptFileException("unterminated attribute value for '$attrName' in <$name>")
                    pos = end + 1
                    element.attributes += XmlAttribute(attrName, slice(valueStart, end), q)
                } else {
                    warnings += "unquoted attribute '$attrName' in <$name>; it is quoted on export"
                    val valueStart = pos
                    while (pos < bytes.size && !bytes[pos].isInlineWhitespace() && bytes[pos] != '>'.code.toByte()) pos++
                    element.attributes += XmlAttribute(attrName, slice(valueStart, pos), '"')
                }
            } else {
                warnings += "attribute '$attrName' in <$name> has no value; written back empty"
                element.attributes += XmlAttribute(attrName, "", '"')
            }
            if (element.attributes.count { it.name == attrName } > 1) {
                warnings += "duplicate attribute '$attrName' in <$name>; the last one wins"
            }
        }
    }

    private fun readCloseTag(): String {
        pos += 2
        val name = readName()
        skipInlineWhitespace()
        val end = indexOfByte('>'.code.toByte(), pos)
        if (end < 0) throw CorruptFileException("unterminated closing tag </$name>")
        pos = end + 1
        return name
    }

    private fun readText(): XmlText {
        val start = pos
        while (pos < bytes.size && bytes[pos] != '<'.code.toByte()) pos++
        return XmlText(slice(start, pos))
    }

    private fun readName(): String {
        val start = pos
        if (pos < bytes.size && bytes[pos].isNameStart()) pos++
        while (pos < bytes.size && bytes[pos].isNameChar()) pos++
        return slice(start, pos)
    }

    private fun skipInlineWhitespace() {
        while (pos < bytes.size && bytes[pos].isInlineWhitespace()) pos++
    }

    private fun indexOfByte(byte: Byte, from: Int = pos): Int {
        var i = from
        while (i < bytes.size) {
            if (bytes[i] == byte) return i
            i++
        }
        return -1
    }

    private fun indexOfSequence(sequence: ByteArray, from: Int = pos): Int {
        var i = from
        outer@ while (i + sequence.size <= bytes.size) {
            for (j in sequence.indices) if (bytes[i + j] != sequence[j]) {
                i++
                continue@outer
            }
            return i
        }
        return -1
    }

    private fun matches(text: String, ignoreCase: Boolean = false): Boolean {
        if (pos + text.length > bytes.size) return false
        for (i in text.indices) {
            val a = bytes[pos + i].toInt().toChar()
            val b = text[i]
            if (if (ignoreCase) a.lowercaseChar() != b.lowercaseChar() else a != b) return false
        }
        return true
    }

    private fun slice(from: Int, to: Int): String =
        if (to <= from) "" else String(bytes, from, to - from, Charsets.UTF_8)

    private fun position(offset: Int = pos): String {
        var line = 1
        var column = 1
        for (i in 0 until offset.coerceAtMost(bytes.size)) {
            if (bytes[i] == '\n'.code.toByte()) {
                line++
                column = 1
            } else {
                column++
            }
        }
        return "line $line, column $column"
    }

    private fun Byte.isInlineWhitespace(): Boolean =
        this == ' '.code.toByte() || this == '\t'.code.toByte() || this == '\n'.code.toByte() || this == '\r'.code.toByte()

    private fun Byte.isNameStart(): Boolean {
        val c = toInt().toChar()
        return c.isLetter() || c == '_' || c == ':' || c.toInt() > 0x7f
    }

    private fun Byte.isNameChar(): Boolean {
        val c = toInt().toChar()
        return c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == ':' || c.toInt() > 0x7f
    }

    companion object {
        const val MAX_ITEMS = 1_000_000L
    }
}
