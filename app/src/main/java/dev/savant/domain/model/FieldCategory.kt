package dev.savant.domain.model

/**
 * Coarse grouping used by the chat reply ("found 42 editable values") and by the
 * field list. Classification is deterministic keyword matching, see
 * [dev.savant.parsers.categorize.CategoryClassifier].
 */
enum class FieldCategory(val label: String) {
    CURRENCY("currency"),
    STATS("stats"),
    INVENTORY("inventory"),
    FLAGS("flags"),
    OTHER("other"),
    ;

    companion object {
        fun fromLabel(label: String?): FieldCategory =
            entries.firstOrNull { it.label.equals(label?.trim(), ignoreCase = true) } ?: OTHER
    }
}
