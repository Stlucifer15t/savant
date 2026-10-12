package dev.savant.domain.model

/**
 * One node of the parsed field tree.
 *
 * Containers ([FieldType.OBJECT], [FieldType.LIST]) carry children and have `value == null`;
 * leaves carry a value and no children. [path] is the stable identity used by every edit,
 * tool call and plugin mapping — see [FieldPath] for the grammar.
 */
data class Node(
    val path: String,
    val displayName: String,
    val type: FieldType,
    val value: FieldValue?,
    val category: FieldCategory = FieldCategory.OTHER,
    val editable: Boolean = true,
    val endianness: Endianness = Endianness.LITTLE,
    val min: Double? = null,
    val max: Double? = null,
    val byteOffset: Long? = null,
    val byteLength: Int? = null,
    val enumOptions: List<String> = emptyList(),
    val aiGuess: AiGuess? = null,
    val children: List<Node> = emptyList(),
    /** Raw key as it appears in the file, before any humanising or AI renaming. */
    val sourceKey: String = displayName,
    /**
     * True when the on-disk representation has a hard width (binary offsets, plugin fields).
     * Text formats are false: their declared type is advisory, so a value may be widened
     * (i32 -> i64) instead of rejected.
     */
    val fixedWidth: Boolean = false,
) {
    val isContainer: Boolean get() = type.isContainer
    val isLeaf: Boolean get() = !isContainer

    /** Effective label: an AI rename wins, with the guess marker handled by the UI. */
    val label: String get() = aiGuess?.displayName ?: displayName

    /**
     * Upper bound used by quick actions and by validation.
     * Text formats have no width, so integers fall back to the int32 max — the largest value
     * practically every game can store — instead of Long.MAX_VALUE.
     */
    val effectiveMax: Double?
        get() = max ?: if (fixedWidth) type.range?.endInclusive
        else if (type.isInteger) DEFAULT_TEXT_INT_MAX else null

    val effectiveMin: Double?
        get() = min ?: if (fixedWidth) type.range?.start
        else if (type.isInteger && type.signed == false) 0.0 else null

    /** Human readable location, e.g. `party[0] / hp`. */
    val trail: String
        get() = FieldPath.parse(path).segments.joinToString(" / ") { segment ->
            when (segment) {
                is FieldPath.Segment.Key -> segment.name
                is FieldPath.Segment.Index -> "[${segment.index}]"
            }
        }

    fun child(path: String): Node? = children.firstOrNull { it.path == path }

    /** Depth first walk including this node. */
    fun walk(): Sequence<Node> = sequence {
        yield(this@Node)
        for (child in children) yieldAll(child.walk())
    }

    fun leaves(): List<Node> = walk().filter { it.isLeaf }.toList()

    /** True when [candidate] holds the same value at this path. Used by export verification. */
    fun sameValueAs(candidate: Node?): Boolean {
        if (candidate == null) return false
        val a = value
        val b = candidate.value
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        if (a == b) return true
        // 500 stored as Int64 and 500.0 stored as Real are the same intent.
        val ad = a.asDouble()
        val bd = b.asDouble()
        return ad != null && bd != null && ad == bd
    }

    override fun toString(): String =
        "Node(path=$path, type=$type, value=${value?.displayText()}, category=$category)"

    companion object {
        /** int32 max: what "MAX" means for an integer in a text format without a declared bound. */
        const val DEFAULT_TEXT_INT_MAX = 2147483647.0
    }
}

