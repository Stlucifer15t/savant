package dev.savant.parsers.json

import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.formatReal

/**
 * Serialises a [JsonNode] back to bytes in the style recovered from the source.
 *
 * Untouched literals (numbers, string quoting and escapes, key quoting) are written exactly as
 * they were read, so `parse -> write` on an unedited file reproduces the original bytes for the
 * common compact and uniformly indented layouts. Output is streamed through a bounded buffer:
 * a 50 MB document never materialises as one String.
 */
internal class JsonWriter(private val style: JsonStyle) {

    private val out = java.io.ByteArrayOutputStream(INITIAL_BUFFER)
    private val buffer = StringBuilder(BUFFER_FLUSH_CHARS)

    fun write(root: JsonNode, trailing: ByteArray = ByteArray(0)): ByteArray {
        emit(root, 0)
        if (style.trailingNewline) append("\n")
        appendBytes(trailing)
        flush()
        if (!style.bom) return out.toByteArray()
        val body = out.toByteArray()
        return ByteArray(body.size + UTF8_BOM.size).also { full ->
            UTF8_BOM.copyInto(full, 0)
            body.copyInto(full, UTF8_BOM.size)
        }
    }

    private fun emit(node: JsonNode, depth: Int) {
        when (node) {
            JNull -> append("null")
            is JBool -> append(if (node.value) "true" else "false")
            is JNumber -> append(node.raw)
            is JString -> append(node.raw ?: JsonReader.quote(node.value))
            is JArray -> emitArray(node, depth)
            is JObject -> emitObject(node, depth)
        }
    }

    private fun emitArray(array: JArray, depth: Int) {
        if (array.items.isEmpty()) {
            append("[]")
            return
        }
        val multiline = array.multiline && !style.compact
        append("[")
        if (multiline) append("\n")
        array.items.forEachIndexed { index, item ->
            if (index > 0) {
                append(",")
                if (multiline) append("\n") else if (style.spaceAfterComma) append(" ")
            }
            if (multiline) appendIndent(depth + 1)
            emit(item, depth + 1)
        }
        if (multiline) {
            append("\n")
            appendIndent(depth)
        }
        append("]")
    }

    private fun emitObject(obj: JObject, depth: Int) {
        if (obj.members.isEmpty()) {
            append("{}")
            return
        }
        val multiline = obj.multiline && !style.compact
        append("{")
        if (multiline) append("\n")
        obj.members.forEachIndexed { index, member ->
            if (index > 0) {
                append(",")
                if (multiline) append("\n") else if (style.spaceAfterComma) append(" ")
            }
            if (multiline) appendIndent(depth + 1)
            append(member.keyRaw ?: JsonReader.quote(member.key))
            append(":")
            if (style.spaceAfterColon || multiline) append(" ")
            emit(member.value, depth + 1)
        }
        if (multiline) {
            append("\n")
            appendIndent(depth)
        }
        append("}")
    }

    private fun appendIndent(depth: Int) {
        val unit = style.indent ?: return
        repeat(depth) { append(unit) }
    }

    private fun append(text: String) {
        buffer.append(text)
        if (buffer.length >= BUFFER_FLUSH_CHARS) flush()
    }

    private fun appendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        flush()
        out.write(bytes, 0, bytes.size)
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

        /** Canonical literal for a value the user just typed. */
        fun literalFor(value: FieldValue): String = when (value) {
            FieldValue.Null -> "null"
            is FieldValue.Bool -> value.value.toString()
            is FieldValue.Int64 ->
                if (value.unsigned) java.lang.Long.toUnsignedString(value.value) else value.value.toString()
            is FieldValue.Real -> formatReal(value.value)
            is FieldValue.Text -> JsonReader.quote(value.value)
        }
    }
}
