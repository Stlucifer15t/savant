package dev.savant.parsers.ini

import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.formatReal

/**
 * Value typing for ini text.
 *
 * Ini has no types, so the type shown is inferred from the literal and is advisory: an edit may
 * widen it. Two things differ from xml text: a quoted value is a string no matter what it says,
 * because the quotes were somebody's way of saying so, and `yes`/`no`/`on`/`off` are booleans,
 * because that is how config files spell them — and a flipped flag is written back in the spelling
 * and case the file already used, so `Fullscreen=Yes` becomes `Fullscreen=No` and not `false`.
 */
internal object IniValues {

    private val INTEGER = Regex("^[+-]?\\d+$")
    private val DECIMAL = Regex("^[+-]?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][+-]?\\d+)?$")

    private val TRUE_WORDS = setOf("true", "yes", "on")
    private val FALSE_WORDS = setOf("false", "no", "off")

    fun infer(text: String, quoted: Boolean): Pair<FieldType, FieldValue> {
        if (quoted) return FieldType.STRING to FieldValue.Text(text)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return FieldType.STRING to FieldValue.Text(text)
        booleanOf(trimmed)?.let { return FieldType.BOOL to FieldValue.Bool(it) }
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

    fun booleanOf(text: String): Boolean? = when (text.lowercase()) {
        in TRUE_WORDS -> true
        in FALSE_WORDS -> false
        else -> null
    }

    /** The literal to write for [value], given the literal that was there before. */
    fun literalFor(value: FieldValue, previous: String): String = when (value) {
        FieldValue.Null -> ""
        is FieldValue.Bool -> booleanLiteral(value.value, previous)
        is FieldValue.Int64 -> if (value.unsigned) java.lang.Long.toUnsignedString(value.value) else value.value.toString()
        is FieldValue.Real -> formatReal(value.value)
        is FieldValue.Text -> value.value
    }

    /** `yes`/`no` stays `yes`/`no`, `ON`/`OFF` stays upper case, anything else is `true`/`false`. */
    private fun booleanLiteral(value: Boolean, previous: String): String {
        val sample = previous.trim()
        val family = when (sample.lowercase()) {
            "yes", "no" -> "yes" to "no"
            "on", "off" -> "on" to "off"
            else -> "true" to "false"
        }
        val word = if (value) family.first else family.second
        return when {
            sample.isEmpty() -> word
            sample.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            sample.first().isUpperCase() -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}
