package dev.savant.domain.model

/** Byte order of a value. Only meaningful for binary formats; text parsers keep [LITTLE]. */
enum class Endianness(val tag: String) {
    LITTLE("le"),
    BIG("be"),
    ;

    companion object {
        fun fromTag(tag: String?): Endianness =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
                ?: when (tag?.lowercase()) {
                    "big", "be", "network", "msb" -> BIG
                    else -> LITTLE
                }
    }
}

/**
 * Type of a single editable value.
 *
 * [sizeBytes] is the on-disk width for the fixed width types and `null` for variable width
 * ones (strings, containers). It is what the binary offset plugins and the diff finder use.
 */
enum class FieldType(
    val tag: String,
    val sizeBytes: Int?,
    val signed: Boolean?,
) {
    INT8("i8", 1, true),
    UINT8("u8", 1, false),
    INT16("i16", 2, true),
    UINT16("u16", 2, false),
    INT32("i32", 4, true),
    UINT32("u32", 4, false),
    INT64("i64", 8, true),
    UINT64("u64", 8, false),
    FLOAT("f32", 4, true),
    DOUBLE("f64", 8, true),
    BOOL("bool", null, null),
    STRING("str", null, null),
    ENUM("enum", null, null),
    NULL("null", null, null),
    LIST("list", null, null),
    OBJECT("object", null, null),
    ;

    val isContainer: Boolean get() = this == LIST || this == OBJECT
    val isNumeric: Boolean get() = this in NUMERIC
    val isInteger: Boolean get() = this in INTEGERS

    /** Inclusive range a value of this type may take, or null when unbounded. */
    val range: ClosedRange<Double>?
        get() = when (this) {
            INT8 -> -128.0..127.0
            UINT8 -> 0.0..255.0
            INT16 -> -32768.0..32767.0
            UINT16 -> 0.0..65535.0
            INT32 -> -2147483648.0..2147483647.0
            UINT32 -> 0.0..4294967295.0
            INT64 -> -9.223372036854776E18..9.223372036854776E18
            UINT64 -> 0.0..1.8446744073709552E19
            else -> null
        }

    companion object {
        val INTEGERS = setOf(INT8, UINT8, INT16, UINT16, INT32, UINT32, INT64, UINT64)
        val NUMERIC = INTEGERS + setOf(FLOAT, DOUBLE)

        fun fromTag(tag: String?): FieldType? =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

        /** Widest integer type that still holds [value] exactly. */
        fun forInteger(value: Long, unsigned: Boolean = false): FieldType = when {
            unsigned -> when {
                value in 0..255 -> UINT8
                value in 0..65535 -> UINT16
                value in 0..4294967295 -> UINT32
                else -> UINT64
            }
            else -> when {
                value in -128..127 -> INT8
                value in -32768..32767 -> INT16
                value in -2147483648..2147483647 -> INT32
                else -> INT64
            }
        }
    }
}
