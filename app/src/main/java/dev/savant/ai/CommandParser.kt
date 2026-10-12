package dev.savant.ai

import kotlin.text.RegexOption.IGNORE_CASE

/**
 * Turns plain typing into tool calls: "set gold to 999999", "max all stats", "undo".
 *
 * Deterministic regex matching, no model, no network. The provider engine in build step 6 produces
 * the same [ToolCall]s, so everything downstream — validation, the diff, the rebuild — is shared.
 */
object CommandParser {

    private val SET = Regex("^(?:set|change|make|put)\\s+(.+?)\\s+(?:to|=|:=|->|:)\\s+(.+)$", IGNORE_CASE)
    private val BARE_SET = Regex("^([A-Za-z_@#.\\[\\]0-9 ]+?)\\s*=\\s*(.+)$")
    private val MAX = Regex("^(?:max|maximize|maximise|maxx|max out|boost)\\s+(.+)$", IGNORE_CASE)
    private val ZERO = Regex("^(?:zero|reset|clear|empty)\\s+(.+)$", IGNORE_CASE)
    private val DOUBLE = Regex("^(?:double|twice|x2)\\s+(.+)$", IGNORE_CASE)
    private val HALVE = Regex("^(?:halve|half)\\s+(.+)$", IGNORE_CASE)
    private val ADD = Regex("^add\\s+(\\S+)\\s+to\\s+(.+)$", IGNORE_CASE)
    private val INCREASE = Regex("^(?:increase|raise)\\s+(.+?)\\s+by\\s+(\\S+)$", IGNORE_CASE)
    private val DECREASE = Regex("^(?:decrease|lower|reduce)\\s+(.+?)\\s+by\\s+(\\S+)$", IGNORE_CASE)
    private val SUBTRACT = Regex("^(?:subtract|remove)\\s+(\\S+)\\s+from\\s+(.+)$", IGNORE_CASE)
    private val UNLOCK = Regex("^(?:unlock|enable|complete)\\s+(.+)$", IGNORE_CASE)
    private val LOCK = Regex("^(?:lock|disable)\\s+(.+)$", IGNORE_CASE)
    private val SEARCH = Regex("^(?:search|find|where is|locate|look for)\\s+(.+)$", IGNORE_CASE)
    private val EXPLAIN = Regex("^(?:explain|describe)\\s+(.+)$", IGNORE_CASE)
    private val WHAT_IS = Regex("^(?:what|who)\\s+(?:is|are)\\s+(.+?)\\??$", IGNORE_CASE)
    private val WHAT_DOES = Regex("^what\\s+(?:does|do)\\s+(.+?)\\s+do\\??$", IGNORE_CASE)
    private val GET = Regex("^(?:get|show|read)\\s+(?:field\\s+)?(.+)$", IGNORE_CASE)
    private val MAKE = Regex("^(?:make|set|put)\\s+(.+?)\\s+(\\S+)$", IGNORE_CASE)

    private val UNDO = setOf("undo", "undo that", "revert", "revert that", "go back", "cancel that", "take it back")
    private val EXPORT = setOf("export", "export file", "save", "save file", "save it", "rebuild", "write file", "write it", "build")
    private val LIST = setOf("list", "list fields", "show fields", "fields", "what can i edit", "what is editable", "help", "commands")
    private val GREETING = setOf("hi", "hello", "hey", "yo", "sup")

    /** Null when the text is not a command — the caller then answers conversationally. */
    fun parse(text: String): ToolCall? {
        val trimmed = text.trim().trimEnd('.', '!', '?')
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()

        if (lower in UNDO) return ToolCall.of("undo")
        if (lower in EXPORT) return ToolCall.of("export")
        if (lower in LIST) return ToolCall.of("list_fields")
        if (lower in GREETING) return null

        WHAT_DOES.find(lower)?.let { return ToolCall.of("explain_field", "path" to it.groupValues[1].trim().removeSuffix("?")) }
        EXPLAIN.find(trimmed)?.let { return ToolCall.of("explain_field", "path" to clean(it.groupValues[1])) }
        WHAT_IS.find(lower)?.let { match ->
            val target = match.groupValues[1].trim().removeSuffix("?")
            if (target.startsWith("this") || target.startsWith("the file") || target.startsWith("savant")) return null
            return ToolCall.of("get_field", "path" to clean(target))
        }
        SEARCH.find(trimmed)?.let { return ToolCall.of("search_fields", "query" to clean(it.groupValues[1])) }

        SET.find(trimmed)?.let { match ->
            val target = clean(match.groupValues[1])
            val value = clean(match.groupValues[2])
            return setCall(target, value)
        }
        BARE_SET.find(trimmed)?.let { match ->
            return setCall(clean(match.groupValues[1]), clean(match.groupValues[2]))
        }
        ADD.find(trimmed)?.let { return ToolCall.of("set_many", "target" to clean(it.groupValues[2]), "op" to "add", "value" to it.groupValues[1]) }
        SUBTRACT.find(trimmed)?.let { return ToolCall.of("set_many", "target" to clean(it.groupValues[2]), "op" to "sub", "value" to it.groupValues[1]) }
        INCREASE.find(trimmed)?.let { return ToolCall.of("set_many", "target" to clean(it.groupValues[1]), "op" to "add", "value" to it.groupValues[2]) }
        DECREASE.find(trimmed)?.let { return ToolCall.of("set_many", "target" to clean(it.groupValues[1]), "op" to "sub", "value" to it.groupValues[2]) }
        MAX.find(trimmed)?.let { return ToolCall.of("set_many", "target" to clean(it.groupValues[1]), "op" to "max") }
        ZERO.find(trimmed)?.let { return ToolCall.of("set_many", "target" to clean(it.groupValues[1]), "op" to "zero") }
        DOUBLE.find(trimmed)?.let { return ToolCall.of("set_many", "target" to clean(it.groupValues[1]), "op" to "double") }
        HALVE.find(trimmed)?.let { return ToolCall.of("set_many", "target" to clean(it.groupValues[1]), "op" to "half") }
        UNLOCK.find(trimmed)?.let { return setCall(clean(it.groupValues[1]), "true") }
        LOCK.find(trimmed)?.let { return setCall(clean(it.groupValues[1]), "false") }
        MAKE.find(trimmed)?.let { return setCall(clean(it.groupValues[1]), clean(it.groupValues[2])) }
        GET.find(trimmed)?.let { return ToolCall.of("get_field", "path" to clean(it.groupValues[1])) }
        return null
    }

    private fun setCall(target: String, value: String): ToolCall {
        val maxed = value.equals("max", true) || value.equals("maximum", true)
        val zeroed = value.equals("min", true)
        return when {
            maxed -> ToolCall.of("set_many", "target" to target, "op" to "max")
            zeroed -> ToolCall.of("set_many", "target" to target, "op" to "min")
            plural(target) -> ToolCall.of("set_many", "target" to target, "op" to "set", "value" to value)
            else -> ToolCall.of("set_field", "path" to target, "value" to value)
        }
    }

    private fun plural(target: String): Boolean {
        val lower = target.lowercase()
        return lower.startsWith("all ") || lower.startsWith("every ") || lower.startsWith("each ") ||
            lower == "all" || lower == "everything" ||
            dev.savant.domain.model.FieldCategory.entries.any { it.label == lower || it.label == lower.removePrefix("all ").trim() }
    }

    private fun clean(text: String): String = text.trim().trim('"', '\'', '`').trim()
}
