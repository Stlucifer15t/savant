package dev.savant.parsers.xml

import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue

/** Value typing for xml text and apple plist tags. */
internal object XmlValues {

    private val INTEGER = Regex("^[+-]?\\d+$")
    private val DECIMAL = Regex("^[+-]?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][+-]?\\d+)?$")

    /** Infers a type from element text. Falls back to a string, which is never wrong. */
    fun infer(text: String): Pair<FieldType, FieldValue> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return FieldType.STRING to FieldValue.Text(text)
        if (trimmed == "true") return FieldType.BOOL to FieldValue.Bool(true)
        if (trimmed == "false") return FieldType.BOOL to FieldValue.Bool(false)
        if (INTEGER.matches(trimmed)) {
            trimmed.toLongOrNull()?.let { value ->
                val type = if (value in Int.MIN_VALUE..Int.MAX_VALUE) FieldType.INT32 else FieldType.INT64
                return type to FieldValue.Int64(value)
            }
            trimmed.toULongOrNull()?.let { value ->
                return FieldType.UINT64 to FieldValue.Int64(value.toLong(), unsigned = true)
            }
        }
        if (DECIMAL.matches(trimmed)) {
            trimmed.toDoubleOrNull()?.let { value -> return FieldType.DOUBLE to FieldValue.Real(value) }
        }
        return FieldType.STRING to FieldValue.Text(text)
    }

    /** Type for a plist value tag: `<integer>`, `<real>`, `<string>`, `<true/>`, ... */
    fun plistType(tag: String): FieldType = when (tag.lowercase()) {
        "integer" -> FieldType.INT64
        "real" -> FieldType.DOUBLE
        "string", "date", "data" -> FieldType.STRING
        "true", "false" -> FieldType.BOOL
        "dict" -> FieldType.OBJECT
        "array" -> FieldType.LIST
        else -> FieldType.STRING
    }

    fun plistValue(tag: String, text: String): FieldValue = when (tag.lowercase()) {
        "integer" -> FieldValue.Int64(text.trim().toLongOrNull() ?: 0L)
        "real" -> FieldValue.Real(text.trim().toDoubleOrNull() ?: 0.0)
        "true" -> FieldValue.Bool(true)
        "false" -> FieldValue.Bool(false)
        else -> FieldValue.Text(text)
    }

    /** Literal to write for a value, given the type the field had. */
    fun literalFor(value: FieldValue): String = when (value) {
        FieldValue.Null -> ""
        is FieldValue.Bool -> value.value.toString()
        is FieldValue.Int64 -> if (value.unsigned) java.lang.Long.toUnsignedString(value.value) else value.value.toString()
        is FieldValue.Real -> dev.savant.domain.model.formatReal(value.value)
        is FieldValue.Text -> value.value
    }
}
