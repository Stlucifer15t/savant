package dev.savant.parsers.categorize

/**
 * Turns raw save-file keys into readable labels: `max_hp` -> `Max HP`, `bIsDead` -> `Is Dead`.
 *
 * Deliberately conservative. Renaming cryptic keys into something meaningful ("v_0042" ->
 * "Gold (likely)") is the AI's job and is always marked as a guess; this only fixes spacing,
 * casing and obvious separators so the list is readable offline.
 */
object Humanizer {

    private val ACRONYMS = setOf(
        "hp", "mp", "sp", "xp", "id", "ai", "ui", "hp2", "mp2", "atk", "def", "dex", "int",
        "str", "wis", "cha", "con", "agi", "vit", "lck", "npc", "pvp", "fps", "lvl", "lv",
    )

    private val SMALL_WORDS = setOf("of", "the", "a", "an", "in", "on", "at", "to", "for", "and", "or")

    fun tokens(key: String): List<String> {
        if (key.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                out += current.toString()
                current.setLength(0)
            }
        }

        var index = 0
        while (index < key.length) {
            val c = key[index]
            val next = key.getOrNull(index + 1)
            when {
                c == '_' || c == '-' || c == ' ' || c == '.' || c == '/' || c == ':' -> flush()
                c.isDigit() -> {
                    if (current.isNotEmpty() && !current.last().isDigit()) flush()
                    current.append(c)
                }
                c.isUpperCase() -> {
                    val previousIsLower = current.isNotEmpty() && current.last().isLowerCase()
                    val nextIsLower = next?.isLowerCase() == true
                    if (current.isNotEmpty() && (previousIsLower || nextIsLower)) flush()
                    current.append(c)
                }
                else -> current.append(c)
            }
            index++
        }
        flush()
        return out.filter { it.isNotEmpty() }
    }

    /**
     * Display label for a key. Empty or fully symbolic keys fall back to the raw text so a row
     * is never blank.
     */
    fun humanize(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return key
        val parts = tokens(trimmed).filter { it.isNotBlank() }
        if (parts.isEmpty()) return trimmed
        return parts.mapIndexed { index, token -> word(token, index == 0 || index == parts.lastIndex) }
            .joinToString(" ")
    }

    private fun word(token: String, significant: Boolean): String {
        val lower = token.lowercase()
        if (lower in ACRONYMS) return lower.uppercase()
        if (token.all { it.isDigit() }) return token
        if (token.all { it.isUpperCase() } && token.length <= 4) return token
        if (!significant && lower in SMALL_WORDS) return lower
        return lower.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * True for keys that carry no meaning on their own: `v_0042`, `f3`, `x`, `data1`, `0x1c`.
     * The chat reports how many of these exist so the user knows a provider would help.
     */
    fun isCryptic(key: String): Boolean {
        val tokens = tokens(key)
        if (tokens.isEmpty()) return true
        if (tokens.all { it.all { c -> c.isDigit() } }) return true
        // A single letter or a single letter plus digits ("x", "v 0042") carries no meaning.
        if (tokens.sumOf { it.count { c -> c.isLetter() } } <= 1) return true
        val letters = tokens.joinToString("").count { it.isLetter() }
        if (letters <= 2 && tokens.any { it.any { c -> c.isDigit() } }) return true
        // "v_0042", "val12", "tmp3": one or two letters glued to digits.
        if (tokens.size <= 2 && tokens.any { token ->
                token.matches(Regex("[A-Za-z]{1,3}\\d+")) || token.matches(Regex("\\d+[A-Za-z]{1,3}"))
            }
        ) {
            val meaningful = tokens.any { it.length >= 4 && it.count { c -> c.isLetter() } >= 3 }
            if (!meaningful) return true
        }
        return false
    }
}
