package dev.savant.domain.finder

import dev.savant.domain.model.Endianness
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.displayText

/**
 * One place in a file that might hold the value the user is looking for.
 *
 * A candidate is a proposal, never a fact: it says where, as what type, what it held before and
 * what it holds now, and why savant ranked it there. The bytes are only written once the user names
 * it and an edit goes through the normal diff preview.
 */
data class Candidate(
    val offset: Int,
    val type: FieldType,
    val endianness: Endianness,
    val before: FieldValue?,
    val after: FieldValue?,
    val score: Float,
    val reasons: List<String>,
    /** How many bytes changed around this offset, which is the strongest signal there is. */
    val runLength: Int = 0,
) {
    val offsetLabel: String get() = BinaryValues.offsetLabel(offset)

    /** `i32 le` — the type tag plus byte order, which is half of what makes a scan readable. */
    val typeTag: String get() = "${type.tag} ${endianness.tag}"

    /** Stable key for lists and for the saved-name table. */
    val key: String get() = "$offsetLabel:$typeTag"

    val changed: Boolean get() = before != after

    val changeText: String
        get() = when {
            before == null && after == null -> "unreadable"
            before == null -> after?.displayText() ?: "?"
            after == null -> "${before.displayText()} -> unreadable"
            !changed -> after.displayText()
            else -> "${before.displayText()} -> ${after.displayText()}"
        }

    /** 0..1, shown as a percentage because "high" means nothing next to a number. */
    val confidence: Float get() = score.coerceIn(0f, 1f)

    val confidenceLabel: String
        get() = buildString {
            append("${(confidence * 100).toInt()}%")
            reasons.take(3).forEach { append(" · ") ; append(it) }
        }

    /** The label a plugin mapping would use: `gold @ 0x000008 (i32 le)`. */
    fun describeAs(name: String): String = "$name @ $offsetLabel ($typeTag)"
}

/** A contiguous stretch of bytes that differs between two files. */
data class ByteRun(val start: Int, val length: Int) {
    val endInclusive: Int get() = start + length - 1
    val label: String get() = "${BinaryValues.offsetLabel(start)}..${BinaryValues.offsetLabel(endInclusive)}"
}

/**
 * What to scan for.
 *
 * Defaults are deliberately wide — every fixed width number in both byte orders — because a first
 * pass across two saves is cheap once it only looks at the bytes that changed. Narrowing is what the
 * third save and [ScanSpec.alignedOnly] are for.
 */
data class ScanSpec(
    val types: Set<FieldType> = DEFAULT_TYPES,
    val endianness: Set<Endianness> = setOf(Endianness.LITTLE, Endianness.BIG),
    val alignedOnly: Boolean = false,
    val limit: Int = 400,
) {
    init {
        require(limit > 0) { "a scan has to be allowed at least one candidate" }
    }

    val typeCount: Int get() = types.size
    val combinations: Int get() = types.size * endianness.size

    companion object {
        val DEFAULT_TYPES: Set<FieldType> = BinaryValues.scannable.toSet()
        val INTEGERS: Set<FieldType> = DEFAULT_TYPES.filter { it.isInteger }.toSet()
        val FLOATS: Set<FieldType> = setOf(FieldType.FLOAT, FieldType.DOUBLE)

        fun of(vararg types: FieldType) = ScanSpec(types = types.toSet())
    }
}
