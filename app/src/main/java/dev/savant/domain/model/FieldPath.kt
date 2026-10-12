package dev.savant.domain.model

/**
 * Paths address nodes inside a field tree and are the only thing edits, tool calls and
 * plugin mappings refer to.
 *
 * Grammar:
 * - property: `player.gold`
 * - list element: `party[0]`, then `party[0].hp`
 * - xml attribute: `save.@version`
 * - xml text of a mixed element: `note.#text`
 * - keys containing `.`, `[`, `]`, spaces or a leading digit are backtick quoted: `` `save slot.1` ``
 *
 * Paths are stable across a parse/write round trip, which is what makes the diff finder able to
 * promote a discovered offset into a plugin mapping.
 */
data class FieldPath(val segments: List<Segment>) {

    sealed interface Segment {
        data class Key(val name: String) : Segment
        data class Index(val index: Int) : Segment
    }

    val isEmpty: Boolean get() = segments.isEmpty()
    val last: Segment? get() = segments.lastOrNull()

    val leafName: String
        get() = when (val tail = last) {
            is Segment.Key -> tail.name
            is Segment.Index -> "[${tail.index}]"
            null -> ""
        }

    fun parent(): FieldPath = FieldPath(segments.dropLast(1))

    fun child(key: String): FieldPath = FieldPath(segments + Segment.Key(key))

    fun child(index: Int): FieldPath = FieldPath(segments + Segment.Index(index))

    fun encode(): String = buildString {
        segments.forEachIndexed { position, segment ->
            when (segment) {
                is Segment.Key -> {
                    if (position > 0 && this.isNotEmpty() && !endsWith("[")) append('.')
                    append(quoteKey(segment.name))
                }
                is Segment.Index -> append('[').append(segment.index).append(']')
            }
        }
    }

    override fun toString(): String = encode()

    companion object {
        val ROOT = FieldPath(emptyList())

        private val needsQuotes = Regex("[.\\[\\]`\\\\\\s]")

        fun quoteKey(key: String): String =
            if (key.isNotEmpty() && !needsQuotes.containsMatchIn(key)) {
                key
            } else {
                "`" + key.replace("\\", "\\\\").replace("`", "\\`") + "`"
            }

        fun parse(text: String): FieldPath {
            if (text.isBlank()) return ROOT
            val segments = mutableListOf<Segment>()
            var i = 0
            while (i < text.length) {
                when (val c = text[i]) {
                    '.' -> i++
                    '[' -> {
                        val end = text.indexOf(']', i)
                        require(end > 0) { "unbalanced '[' in path: $text" }
                        val body = text.substring(i + 1, end).trim()
                        val index = body.toIntOrNull()
                        require(index != null) { "non numeric index '$body' in path: $text" }
                        segments += Segment.Index(index)
                        i = end + 1
                    }
                    '`' -> {
                        val sb = StringBuilder()
                        i++
                        while (i < text.length) {
                            val ch = text[i]
                            if (ch == '\\' && i + 1 < text.length) {
                                sb.append(text[i + 1]); i += 2
                            } else if (ch == '`') {
                                i++; break
                            } else {
                                sb.append(ch); i++
                            }
                        }
                        segments += Segment.Key(sb.toString())
                    }
                    else -> {
                        val start = i
                        while (i < text.length && text[i] != '.' && text[i] != '[' && text[i] != '`') i++
                        if (i == start) error("empty path segment in: $text")
                        segments += Segment.Key(text.substring(start, i))
                    }
                }
            }
            return FieldPath(segments)
        }

        fun of(vararg parts: Any): FieldPath {
            var path = ROOT
            for (part in parts) {
                path = when (part) {
                    is Int -> path.child(part)
                    is String -> path.child(part)
                    else -> error("unsupported path part: $part")
                }
            }
            return path
        }

        /** `a.b[0].c` -> `a.b[0]`, `a` -> `` (root). */
        fun parentOf(encoded: String): String = parse(encoded).parent().encode()
    }
}
