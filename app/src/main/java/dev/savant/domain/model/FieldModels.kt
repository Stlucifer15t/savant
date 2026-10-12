package dev.savant.domain.model

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

enum class FieldType(val tag: String) {
    INT("int"),
    FLOAT("float"),
    BOOL("bool"),
    STRING("string"),
    ENUM("enum"),
    LIST("list"),
    OBJECT("object"),
    NULL("null"),
}

enum class FieldCategory(val label: String) {
    CURRENCY("Currency"),
    STATS("Stats"),
    INVENTORY("Inventory"),
    FLAGS("Flags"),
    OTHER("Other"),
}

sealed interface FieldValue {
    data class IntegerValue(val value: Long) : FieldValue
    data class FloatValue(val value: Double) : FieldValue
    data class BooleanValue(val value: Boolean) : FieldValue
    data class StringValue(val value: String) : FieldValue
    data class EnumValue(val value: String) : FieldValue
    data object NullValue : FieldValue
}

fun FieldValue.asDisplayString(): String = when (this) {
    is FieldValue.IntegerValue -> value.toString()
    is FieldValue.FloatValue -> {
        if (!value.isFinite()) value.toString()
        else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
    is FieldValue.BooleanValue -> value.toString()
    is FieldValue.StringValue -> value
    is FieldValue.EnumValue -> value
    FieldValue.NullValue -> "null"
}

fun FieldValue.asAccessibleString(): String = when (this) {
    is FieldValue.IntegerValue -> NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)
    else -> asDisplayString()
}

data class FieldNode(
    val path: String,
    val displayName: String,
    val type: FieldType,
    val value: FieldValue? = null,
    val min: Double? = null,
    val max: Double? = null,
    val category: FieldCategory = FieldCategory.OTHER,
    val editable: Boolean = false,
    val aiGuess: String? = null,
    val children: List<FieldNode> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) {
    val isContainer: Boolean get() = type == FieldType.OBJECT || type == FieldType.LIST
}

data class FieldTree(
    val formatId: String,
    val roots: List<FieldNode>,
    val metadata: Map<String, String> = emptyMap(),
) {
    fun allNodes(): List<FieldNode> = buildList {
        fun visit(node: FieldNode) {
            add(node)
            node.children.forEach(::visit)
        }
        roots.forEach(::visit)
    }

    fun editableNodes(): List<FieldNode> = allNodes().filter { it.editable && it.value != null }

    fun find(path: String): FieldNode? = allNodes().firstOrNull { it.path == path }

    fun withValues(replacements: Map<String, FieldValue>): FieldTree {
        if (replacements.isEmpty()) return this
        fun update(node: FieldNode): FieldNode {
            val replacement = replacements[node.path]
            return node.copy(
                value = replacement ?: node.value,
                children = node.children.map(::update),
            )
        }
        return copy(roots = roots.map(::update))
    }
}

data class FieldChange(
    val path: String,
    val displayName: String,
    val type: FieldType,
    val oldValue: FieldValue,
    val newValue: FieldValue,
)
