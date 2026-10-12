package dev.savant.parsers.xml

/**
 * Serialises an [XmlDocumentModel] back to bytes.
 *
 * Raw text, comments, doctypes and processing instructions are written exactly as they were read,
 * so an unedited file round trips byte for byte. Only whitespace inside a tag is normalised.
 */
internal class XmlWriter {

    private val out = java.io.ByteArrayOutputStream(INITIAL_BUFFER)
    private val buffer = StringBuilder(BUFFER_FLUSH_CHARS)

    fun write(model: XmlDocumentModel): ByteArray {
        for (item in model.prolog) emit(item)
        model.root?.let { emitElement(it) }
        for (item in model.epilog) emit(item)
        flush()
        if (!model.bom) return out.toByteArray()
        val body = out.toByteArray()
        return ByteArray(body.size + UTF8_BOM.size).also { full ->
            UTF8_BOM.copyInto(full, 0)
            body.copyInto(full, UTF8_BOM.size)
        }
    }

    private fun emit(item: XmlItem) {
        when (item) {
            is XmlText -> append(item.raw)
            is XmlComment -> append(item.raw)
            is XmlProcessing -> append(item.raw)
            is XmlDocType -> append(item.raw)
            is XmlCData -> {
                append("<![CDATA[")
                append(item.content)
                append("]]>")
            }
            is XmlElement -> emitElement(item)
        }
    }

    private fun emitElement(element: XmlElement) {
        append("<")
        append(element.name)
        for (attribute in element.attributes) {
            append(" ")
            append(attribute.name)
            append("=")
            append(attribute.quote.toString())
            append(attribute.rawValue)
            append(attribute.quote.toString())
        }
        if (element.selfClosing && element.children.isEmpty()) {
            append("/>")
            return
        }
        append(">")
        for (child in element.children) emit(child)
        append("</")
        append(element.name)
        append(">")
    }

    private fun append(text: String) {
        if (text.isEmpty()) return
        buffer.append(text)
        if (buffer.length >= BUFFER_FLUSH_CHARS) flush()
    }

    private fun flush() {
        if (buffer.isEmpty()) return
        val encoded = buffer.toString().toByteArray(Charsets.UTF_8)
        out.write(encoded, 0, encoded.size)
        buffer.setLength(0)
    }

    companion object {
        private const val INITIAL_BUFFER = 8 * 1024
        private const val BUFFER_FLUSH_CHARS = 1 shl 16
        private val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    }
}
