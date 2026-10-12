package dev.savant.domain.model

sealed interface EditResult {
    data class Valid(val value: FieldValue) : EditResult
    data class Invalid(val reason: String) : EditResult
}

object FieldEditor {
    fun parse(node: FieldNode, input: String): EditResult {
        if (!node.editable || node.value == null) return EditResult.Invalid("this field is read-only")
        val trimmed = input.trim()
        val parsed = when (node.type) {
            FieldType.INT -> trimmed.toLongOrNull()?.let(FieldValue::IntegerValue)
                ?: return EditResult.Invalid("enter a whole number")
            FieldType.FLOAT -> trimmed.toDoubleOrNull()
                ?.takeIf(Double::isFinite)
                ?.let(FieldValue::FloatValue)
                ?: return EditResult.Invalid("enter a finite number")
            FieldType.BOOL -> when (trimmed.lowercase()) {
                "true", "1", "yes", "on" -> FieldValue.BooleanValue(true)
                "false", "0", "no", "off" -> FieldValue.BooleanValue(false)
                else -> return EditResult.Invalid("use true or false")
            }
            FieldType.STRING -> FieldValue.StringValue(input)
            FieldType.ENUM -> {
                val options = node.metadata["options"]?.split('\u001f').orEmpty()
                if (options.isNotEmpty() && input !in options) {
                    return EditResult.Invalid("choose one of: ${options.joinToString()}")
                }
                FieldValue.EnumValue(input)
            }
            FieldType.LIST, FieldType.OBJECT, FieldType.NULL -> {
                return EditResult.Invalid("this field cannot be edited directly")
            }
        }

        val numeric = when (parsed) {
            is FieldValue.IntegerValue -> parsed.value.toDouble()
            is FieldValue.FloatValue -> parsed.value
            else -> null
        }
        if (numeric != null && node.min != null && numeric < node.min) {
            return EditResult.Invalid("minimum is ${formatBound(node.min)}")
        }
        if (numeric != null && node.max != null && numeric > node.max) {
            return EditResult.Invalid("maximum is ${formatBound(node.max)}")
        }
        return EditResult.Valid(parsed)
    }

    fun doubledValue(node: FieldNode): String? = when (val value = node.value) {
        is FieldValue.IntegerValue -> try {
            Math.multiplyExact(value.value, 2L).toString()
        } catch (_: ArithmeticException) {
            null
        }
        is FieldValue.FloatValue -> (value.value * 2.0).takeIf(Double::isFinite)?.toString()
        else -> null
    }

    fun maximumValue(node: FieldNode): String? = node.max?.let(::formatBound)

    private fun formatBound(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
