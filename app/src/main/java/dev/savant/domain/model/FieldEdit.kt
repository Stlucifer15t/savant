package dev.savant.domain.model

/** Where an edit came from. Everything except [USER_INLINE] goes through the diff preview. */
enum class EditSource(val label: String) {
    USER_INLINE("tap"),
    USER_COMMAND("command"),
    AI_TOOL("ai"),
    FINDER("finder"),
    UNDO("undo"),
}

/**
 * A single proposed value change. Edits are *proposed* first: they never touch a file until the
 * user confirms the diff, and they are replayed onto the parse document by
 * [dev.savant.domain.parser.SaveDocument.applyEdits].
 */
data class FieldEdit(
    val path: String,
    val displayName: String,
    val type: FieldType,
    val oldValue: FieldValue,
    val newValue: FieldValue,
    val source: EditSource = EditSource.USER_INLINE,
    val min: Double? = null,
    val max: Double? = null,
) {
    val isNoOp: Boolean get() = oldValue == newValue || oldValue.asDouble() == newValue.asDouble() && oldValue.asDouble() != null

    val oldText: String get() = oldValue.displayText()
    val newText: String get() = newValue.displayText()

    fun revert(): FieldEdit = copy(oldValue = newValue, newValue = oldValue, source = EditSource.UNDO)

    companion object {
        fun of(node: Node, newValue: FieldValue, source: EditSource = EditSource.USER_INLINE): FieldEdit =
            FieldEdit(
                path = node.path,
                displayName = node.label,
                type = node.type,
                oldValue = node.value ?: FieldValue.Null,
                newValue = newValue,
                source = source,
                min = node.min,
                max = node.max,
            )
    }
}

/** A batch of edits that was confirmed and applied together; the unit of undo. */
data class AppliedEditBatch(
    val id: String,
    val edits: List<FieldEdit>,
    val appliedAt: Long,
    val note: String? = null,
)

/** Result of checking an edit against the node it targets. */
data class EditValidation(val ok: Boolean, val reason: String?) {
    companion object {
        val OK = EditValidation(true, null)
        fun reject(reason: String) = EditValidation(false, reason)
    }
}

/**
 * Type and range checking for edits. Deterministic Kotlin — the AI proposes values, this decides
 * whether they may be written.
 */
object EditValidator {

    fun validate(node: Node, newValue: FieldValue): EditValidation {
        if (!node.editable) return EditValidation.reject("not editable")
        if (node.readOnlyContainer()) return EditValidation.reject("container")

        if (newValue == node.value) return EditValidation.OK

        when (node.type) {
            FieldType.NULL -> if (newValue !is FieldValue.Null) {
                // A json null may become anything; that is a legitimate edit of the value type.
                return EditValidation.OK
            }

            FieldType.BOOL -> if (newValue !is FieldValue.Bool) {
                return EditValidation.reject("needs true or false")
            }

            FieldType.STRING -> if (newValue is FieldValue.Null) {
                return EditValidation.reject("needs text, not null")
            }

            FieldType.ENUM -> {
                val text = (newValue as? FieldValue.Text)?.value ?: newValue.displayText()
                if (node.enumOptions.isNotEmpty() && text !in node.enumOptions) {
                    return EditValidation.reject("one of: ${node.enumOptions.joinToString()}")
                }
            }

            FieldType.OBJECT, FieldType.LIST -> return EditValidation.reject("container")

            else -> {
                if (newValue is FieldValue.Null) return EditValidation.reject("needs a number")
                if (newValue is FieldValue.Text) {
                    if (newValue.value.toDoubleOrNull() == null) return EditValidation.reject("needs a number")
                }
                val number = newValue.asDouble()
                    ?: return EditValidation.reject("needs a number")
                if (number.isNaN() || number.isInfinite()) return EditValidation.reject("not a finite number")

                // Only fixed width fields (binary offsets, plugin mappings) are hard bounded by
                // their type. Text formats widen instead: i32 -> i64 is a legal json edit.
                if (node.fixedWidth) {
                    node.type.range?.let { range ->
                        if (number < range.start || number > range.endInclusive) {
                            return EditValidation.reject("out of range for ${node.type.tag}")
                        }
                    }
                }
                if (node.type.isInteger || newValue is FieldValue.Int64) {
                    val long = newValue.asLong()
                    if (long == null) return EditValidation.reject("not a whole number")
                    if (node.type.signed == false && long < 0) {
                        return EditValidation.reject("${node.type.tag} cannot be negative")
                    }
                }
            }
        }

        // Only explicit bounds from a plugin (or the file itself) are hard limits. The "MAX"
        // quick action uses effectiveMax, which is deliberately more conservative than this.
        node.min?.let { min ->
            val number = newValue.asDouble() ?: return@let
            if (number < min) return EditValidation.reject("below minimum ${formatReal(min)}")
        }
        node.max?.let { max ->
            val number = newValue.asDouble() ?: return@let
            if (number > max) return EditValidation.reject("above maximum ${formatReal(max)}")
        }
        return EditValidation.OK
    }

    /** Parses user input straight into the shape the node expects. */
    fun coerce(node: Node, input: String): FieldValue {
        val trimmed = input.trim()
        return when (node.type) {
            FieldType.STRING, FieldType.ENUM -> {
                val unquoted = trimmed.removeSurrounding("\"").removeSurrounding("'")
                FieldValue.Text(unquoted)
            }
            FieldType.BOOL -> when {
                trimmed.equals("true", true) || trimmed == "1" || trimmed.equals("yes", true) || trimmed.equals("on", true) -> FieldValue.Bool(true)
                trimmed.equals("false", true) || trimmed == "0" || trimmed.equals("no", true) || trimmed.equals("off", true) -> FieldValue.Bool(false)
                else -> FieldValue.parse(trimmed, node.type)
            }
            FieldType.NULL -> if (trimmed.equals("null", true) || trimmed.isEmpty()) FieldValue.Null else FieldValue.parse(trimmed, node.type)
            FieldType.OBJECT, FieldType.LIST -> FieldValue.parse(trimmed, node.type)
            else -> {
                val parsed = FieldValue.parse(trimmed, node.type)
                when {
                    node.type == FieldType.FLOAT || node.type == FieldType.DOUBLE -> when (parsed) {
                        is FieldValue.Int64 -> FieldValue.Real(parsed.value.toDouble())
                        is FieldValue.Real -> parsed
                        else -> parsed
                    }
                    node.type.isInteger -> when (parsed) {
                        is FieldValue.Real -> if (parsed.value == parsed.value.toLong().toDouble()) {
                            FieldValue.Int64(parsed.value.toLong(), unsigned = node.type.signed == false)
                        } else parsed
                        is FieldValue.Int64 -> parsed.copy(unsigned = node.type.signed == false)
                        else -> parsed
                    }
                    else -> parsed
                }
            }
        }
    }

    /** `max`, `min`, `0`, `x2`, `+10`, `-10` relative to the current value. */
    fun quickValue(node: Node, kind: QuickValue): FieldValue? {
        val current = node.value ?: return null
        val number = current.asDouble()
        return when (kind) {
            QuickValue.MAX -> node.effectiveMax?.let { bound(node, it) }
            QuickValue.ZERO -> zeroFor(node)
            QuickValue.DOUBLE -> number?.let { bound(node, it * 2) }
            QuickValue.HALF -> number?.let { bound(node, it / 2) }
        }
    }

    private fun zeroFor(node: Node): FieldValue = when {
        node.type == FieldType.BOOL -> FieldValue.Bool(false)
        node.type == FieldType.FLOAT || node.type == FieldType.DOUBLE -> FieldValue.Real(0.0)
        node.type.isInteger -> FieldValue.Int64(0, unsigned = node.type.signed == false)
        node.type == FieldType.STRING -> FieldValue.Text("")
        else -> FieldValue.Int64(0)
    }

    /** Clamps into the node's own min/max first, then into the type range. */
    private fun bound(node: Node, value: Double): FieldValue {
        val min = node.effectiveMin
        val max = node.effectiveMax
        var clamped = value
        if (min != null && clamped < min) clamped = min
        if (max != null && clamped > max) clamped = max
        if (clamped.isNaN() || clamped.isInfinite()) return FieldValue.Real(value)
        return if (node.type.isInteger || node.value is FieldValue.Int64) {
            FieldValue.Int64(clamped.toLong(), unsigned = node.type.signed == false)
        } else {
            FieldValue.Real(clamped)
        }
    }

    private fun Node.readOnlyContainer(): Boolean = type.isContainer

    enum class QuickValue(val label: String) {
        MAX("MAX"),
        ZERO("0"),
        DOUBLE("x2"),
        HALF("/2"),
    }
}
