package dev.savant.ai.web

/**
 * The readable text of an html document.
 *
 * Not a html parser — a page is not a save file and savant does not need its structure, only its
 * words. Scripts, styles and comments go, block ends become line breaks, entities are unescaped, and
 * the result is capped, because what comes out is about to be handed to a model as *data*.
 */
object HtmlText {

    private val SCRIPT = Regex("(?is)<(script|style|noscript|template|svg)\\b.*?</\\1>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val BLOCK_END = Regex("(?i)</(p|div|li|ul|ol|h[1-6]|tr|section|article|blockquote|pre)>")
    private val LINE_BREAK = Regex("(?i)<br\\s*/?>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val BLANK_LINES = Regex("\n{3,}")
    private val SPACES = Regex("[ \\t]{2,}")

    fun extract(html: String, maxChars: Int = WebPolicy.MAX_TEXT_CHARS): String {
        var text = html
        text = SCRIPT.replace(text, " ")
        text = COMMENT.replace(text, " ")
        text = LINE_BREAK.replace(text, "\n")
        text = BLOCK_END.replace(text, "\n")
        text = TAG.replace(text, " ")
        text = unescape(text)
        text = text.lineSequence()
            .map { SPACES.replace(it, " ").trim() }
            .joinToString("\n")
        text = BLANK_LINES.replace(text, "\n\n").trim()
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n[truncated at $maxChars characters]"
    }

    /** Title text, if there is one: it is the most useful line on most pages. */
    fun title(html: String): String? {
        val match = Regex("(?is)<title[^>]*>(.*?)</title>").find(html) ?: return null
        val text = unescape(TAG.replace(match.groupValues[1], " ")).trim()
        return text.ifBlank { null }
    }

    /** Looks like markup rather than prose, which decides whether to run [extract] at all. */
    fun looksLikeHtml(text: String): Boolean {
        val head = text.take(2_000)
        return head.contains("<!doctype html", ignoreCase = true) || head.contains("<html") ||
            Regex("(?i)<(p|div|br|li|span|body|head)\\b").containsMatchIn(head)
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "copy" to "©", "reg" to "®", "trade" to "™",
        "bull" to "•", "middot" to "·", "laquo" to "«", "raquo" to "»", "deg" to "°",
        "times" to "×", "divide" to "÷", "frac12" to "½", "euro" to "€", "pound" to "£",
        "yen" to "¥", "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓",
    )

    private fun unescape(text: String): String = ENTITY.replace(text) { match ->
        val entity = match.groupValues[1]
        when {
            entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)?.toCharOrNull()
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.toCharOrNull()
            else -> NAMED[entity.lowercase()]
        } ?: match.value
    }

    /** A code point that is not a surrogate and not a control character, or nothing. */
    private fun Int.toCharOrNull(): String? {
        if (this <= 0 || this > 0x10FFFF) return null
        if (this in 0xD800..0xDFFF) return null
        if (this < 0x20 && this != 0x09 && this != 0x0A && this != 0x0D) return null
        return String(Character.toChars(this))
    }
}
