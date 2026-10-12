package dev.savant.domain.model

import kotlin.math.abs

/**
 * A value that can sit in a [Node].
 *
 * Integers stay [Long] so nothing is silently truncated, reals stay [Double].
 * Text formats additionally remember the literal they were parsed from (see
 * [dev.savant.parsers.json.JsonNumber] and the xml text nodes) so an untouched file is
 * written back byte for byte.
 */
sealed interface FieldValue {

    data object Null : FieldValue

    data class Text(val value: String) : FieldValue

    data class Int64(val value: Long, val unsigned: Boolean = false) : FieldValue

    /** [float32] is a hint from a binary/plugin field; text formats always use doubles. */
    data class Real(val value: Double, val float32: Boolean = false) : FieldValue

    data class Bool(val value: Boolean) : FieldValue

    companion object {
        fun of(value: Long, unsigned: Boolean = false): FieldValue = Int64(value, unsigned)
        fun of(value: Double): FieldValue = Real(value)
        fun of(value: Boolean): FieldValue = Bool(value)
        fun of(value: String?): FieldValue = if (value == null) Null else Text(value)

        /**
         * Parses what the user typed in an inline editor or a chat command.
         * Accepts `null`, booleans, decimals with separators/suffixes (1_000, 12k, 1.5e6, 0x1F)
         * and falls back to a string.
         */
        fun parse(input: String, targetType: FieldType? = null): FieldValue {
            val raw = input.trim()
            if (raw.isEmpty()) return Text("")
            if (raw.equals("null", true) || raw.equals("nil", true)) return Null
            if (raw.equals("true", true) || raw.equals("yes", true) || raw.equals("on", true)) return Bool(true)
            if (raw.equals("false", true) || raw.equals("no", true) || raw.equals("off", true)) return Bool(false)

            val number = parseNumber(raw)
            if (number != null) {
                if (targetType == FieldType.FLOAT || targetType == FieldType.DOUBLE) {
                    number.asDouble()?.let { return Real(it, float32 = targetType == FieldType.FLOAT) }
                }
                if (targetType != null && targetType.isInteger) {
                    number.asLong()?.let { return Int64(it, unsigned = targetType.signed == false) }
                }
                return number
            }
            return Text(raw)
        }

        private fun parseNumber(raw: String): FieldValue? {
            val cleaned = raw.replace("_", "").replace(" ", "")
            if (cleaned.isEmpty()) return null

            val hex = cleaned.removePrefix("0x").removePrefix("0X")
            if (hex != cleaned && hex.isNotEmpty() && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                val value = hex.toLongOrNull(16) ?: return null
                return Int64(if (cleaned.startsWith("-")) -value else value)
            }

            val multiplier = when {
                cleaned.endsWith("k", true) -> 1_000.0
                cleaned.endsWith("m", true) -> 1_000_000.0
                cleaned.endsWith("b", true) -> 1_000_000_000.0
                else -> null
            }
            val body = if (multiplier != null) cleaned.dropLast(1) else cleaned
            if (body.isEmpty()) return null

            val looksNumeric = body.all { it.isDigit() || it == '-' || it == '+' || it == '.' || it == 'e' || it == 'E' } &&
                body.any { it.isDigit() }
            if (!looksNumeric) return null

            if (multiplier == null) {
                val asLong = body.toLongOrNull()
                if (asLong != null) return Int64(asLong)
                val unsigned = body.toULongOrNull()
                if (unsigned != null) return Int64(unsigned.toLong(), unsigned = true)
            }
            val asDouble = body.toDoubleOrNull() ?: return null
            return if (multiplier != null) Real(asDouble * multiplier) else Real(asDouble)
        }
    }
}

val FieldValue.isNumeric: Boolean get() = this is FieldValue.Int64 || this is FieldValue.Real

fun FieldValue.asDouble(): Double? = when (this) {
    is FieldValue.Int64 -> value.toDouble()
    is FieldValue.Real -> value
    is FieldValue.Bool -> if (value) 1.0 else 0.0
    is FieldValue.Text -> value.trim().toDoubleOrNull()
    FieldValue.Null -> null
}

fun FieldValue.asLong(): Long? = when (this) {
    is FieldValue.Int64 -> value
    is FieldValue.Real -> if (value == value.toLong().toDouble()) value.toLong() else null
    is FieldValue.Bool -> if (value) 1L else 0L
    is FieldValue.Text -> value.trim().toLongOrNull()
    FieldValue.Null -> null
}

fun FieldValue.asText(): String? = (this as? FieldValue.Text)?.value

/** What the UI shows in a field row and in the diff. Short, no locale specific separators. */
fun FieldValue.displayText(): String = when (this) {
    FieldValue.Null -> "null"
    is FieldValue.Bool -> value.toString()
    is FieldValue.Int64 -> if (unsigned) java.lang.Long.toUnsignedString(value) else value.toString()
    is FieldValue.Real -> formatReal(value)
    is FieldValue.Text -> "\"$value\""
}

/** Compact, stable number formatting: no exponent for sane magnitudes, no trailing zeros. */
fun formatReal(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    if (value == value.toLong().toDouble() && abs(value) < 1e15) return value.toLong().toString()
    val text = "%.6f".format(value).trimEnd('0').trimEnd('.')
    return text.ifEmpty { "0" }
}
