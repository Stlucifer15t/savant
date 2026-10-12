package dev.savant.ai

import java.util.UUID

/** Parameter type in a tool schema. Serialised to each provider's dialect by the client. */
enum class ParamType(val json: String) {
    STRING("string"),
    NUMBER("number"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
    ARRAY("array"),
}

data class ToolParameter(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
    val options: List<String> = emptyList(),
)

/**
 * One tool exposed to the model. Built in Kotlin, serialised per provider — the model never sees
 * savant internals, only these names and descriptions.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
)

/** A call the model (or the on-device parser) wants to make. */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
) {
    fun arg(name: String): String? = arguments[name]?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        fun of(name: String, vararg pairs: Pair<String, String?>): ToolCall = ToolCall(
            id = UUID.randomUUID().toString(),
            name = name,
            arguments = pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap(),
        )
    }
}

/**
 * The fixed tool list. Everything a model may do to a save file goes through one of these, and
 * every value-changing tool only *proposes* edits — the diff preview is the single gate.
 */
object ToolCatalog {

    val LIST_FIELDS = ToolSpec(
        name = "list_fields",
        description = "List editable values in the open save file, grouped by category. " +
            "Optional category filter: currency, stats, inventory, flags, other.",
        parameters = listOf(
            ToolParameter("category", ParamType.STRING, "only this category", required = false,
                options = listOf("currency", "stats", "inventory", "flags", "other")),
            ToolParameter("limit", ParamType.INTEGER, "max rows to return (default 40)", required = false),
        ),
    )

    val SEARCH_FIELDS = ToolSpec(
        name = "search_fields",
        description = "Search fields by name, path or value. Prefixes 'category:' and 'type:' narrow the match.",
        parameters = listOf(
            ToolParameter("query", ParamType.STRING, "what to look for"),
            ToolParameter("limit", ParamType.INTEGER, "max rows (default 25)", required = false),
        ),
    )

    val GET_FIELD = ToolSpec(
        name = "get_field",
        description = "Read one field: path, name, type, value, category, bounds and its siblings.",
        parameters = listOf(ToolParameter("path", ParamType.STRING, "field path, e.g. player.gold")),
    )

    val SET_FIELD = ToolSpec(
        name = "set_field",
        description = "Propose a new value for one field. The user sees a diff and confirms before anything is written.",
        parameters = listOf(
            ToolParameter("path", ParamType.STRING, "field path or name"),
            ToolParameter("value", ParamType.STRING, "new value; numbers, true/false, or text"),
        ),
    )

    val SET_MANY = ToolSpec(
        name = "set_many",
        description = "Propose the same change for many fields at once, e.g. max every stat. " +
            "Always shown as a diff the user confirms.",
        parameters = listOf(
            ToolParameter("target", ParamType.STRING, "'all', a category name, or a search query"),
            ToolParameter("op", ParamType.STRING, "what to do",
                options = listOf("set", "max", "zero", "double", "half", "add", "sub")),
            ToolParameter("value", ParamType.STRING, "value for op=set/add/sub", required = false),
        ),
    )

    val UNDO = ToolSpec(
        name = "undo",
        description = "Propose reverting the most recent applied batch of edits.",
    )

    val EXPORT = ToolSpec(
        name = "export",
        description = "Rebuild the save file and show the export card. Never overwrites the original.",
        parameters = listOf(
            ToolParameter("file_name", ParamType.STRING, "optional export file name", required = false),
        ),
    )

    val EXPLAIN_FIELD = ToolSpec(
        name = "explain_field",
        description = "Explain what a field probably does, from its name, type, category and neighbours. " +
            "Answers are marked as guesses.",
        parameters = listOf(ToolParameter("path", ParamType.STRING, "field path or name")),
    )

    val ALL: List<ToolSpec> = listOf(
        LIST_FIELDS, SEARCH_FIELDS, GET_FIELD, SET_FIELD, SET_MANY, UNDO, EXPORT, EXPLAIN_FIELD,
    )

    fun byName(name: String): ToolSpec? = ALL.firstOrNull { it.name == name }

    /** Tools that change something and therefore always go through the diff preview. */
    val VALUE_CHANGING = setOf(SET_FIELD, SET_MANY, UNDO)
}
