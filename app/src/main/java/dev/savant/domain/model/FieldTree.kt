package dev.savant.domain.model

/** One category section of the field list, in the order the chat reply reports them. */
data class FieldGroup(
    val category: FieldCategory,
    val nodes: List<Node>,
) {
    val count: Int get() = nodes.size
}

/**
 * Immutable view of everything editable in a save file.
 *
 * Built by a [dev.savant.domain.parser.SaveParser], indexed by path for O(1) lookups, and
 * replaced (never mutated) after each applied edit batch.
 */
class FieldTree(
    val format: SaveFormat,
    val roots: List<Node>,
    val warnings: List<String> = emptyList(),
    val truncated: Boolean = false,
    val readOnlyReason: String? = null,
) {
    private val index: Map<String, Node> = buildMap {
        fun walk(node: Node) {
            put(node.path, node)
            node.children.forEach(::walk)
        }
        roots.forEach(::walk)
    }

    private val flattened: List<Node> = index.values.toList()

    val all: List<Node> get() = flattened
    val totalCount: Int get() = flattened.size

    val editableNodes: List<Node> by lazy { flattened.filter { it.editable && it.isLeaf } }
    val editableCount: Int get() = editableNodes.size

    val readOnly: Boolean get() = readOnlyReason != null

    val isEmpty: Boolean get() = flattened.isEmpty()

    fun node(path: String): Node? = index[path]

    fun has(path: String): Boolean = index.containsKey(path)

    /** Grouped, non empty categories first in the canonical order. */
    fun groups(): List<FieldGroup> = FieldCategory.entries
        .map { category -> FieldGroup(category, flattened.filter { it.isLeaf && it.category == category }) }
        .filter { it.count > 0 }

    /**
     * Search by name, path, raw key or value; `category:` and `type:` prefixes narrow the field.
     * Matching is case insensitive and ignores `_`, `-` and spaces so "playergold",
     * "player_gold" and "Player Gold" all hit the same node.
     */
    fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<Node> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        var categoryFilter: FieldCategory? = null
        var typeFilter: FieldType? = null
        var text = trimmed

        // "category:currency gold" / "type:f32 speed" narrow the search; anything else is text.
        text.substringBefore(' ').let { first ->
            val key = first.substringBefore(':', "").lowercase()
            val value = first.substringAfter(':', "")
            if (key == "category" && value.isNotEmpty()) {
                categoryFilter = FieldCategory.fromLabel(value)
                text = text.removePrefix(first).trim()
            } else if (key == "type" && value.isNotEmpty()) {
                typeFilter = FieldType.fromTag(value)
                text = text.removePrefix(first).trim()
            }
        }

        if (text.isEmpty()) {
            return flattened
                .filter { it.isLeaf }
                .filter { categoryFilter == null || it.category == categoryFilter }
                .filter { typeFilter == null || it.type == typeFilter }
                .take(limit)
        }

        val needle = normalize(text)
        val scored = mutableListOf<Pair<Int, Node>>()
        for (node in flattened) {
            if (!node.isLeaf) continue
            if (categoryFilter != null && node.category != categoryFilter) continue
            if (typeFilter != null && node.type != typeFilter) continue

            val leaf = normalize(FieldPath.parse(node.path).leafName)
            val full = normalize(node.path)
            val label = normalize(node.displayName)
            val value = node.value?.displayText()?.let(::normalize) ?: ""

            val score = when {
                leaf == needle -> 0
                label == needle -> 0
                leaf.startsWith(needle) -> 1
                label.startsWith(needle) -> 1
                value == needle -> 2
                leaf.contains(needle) -> 3
                label.contains(needle) -> 3
                full.contains(needle) -> 4
                value.contains(needle) -> 5
                // plural or typo: "golds" still finds "gold"
                leaf.length >= 3 && needle.contains(leaf) -> 6
                label.length >= 3 && needle.contains(label) -> 6
                else -> -1
            }
            if (score >= 0) scored += score to node
        }
        return scored.sortedWith(compareBy({ it.first }, { it.second.path })).map { it.second }.take(limit)
    }

    /** Returns a new tree with whole nodes swapped in, keeping their positions. */
    fun withNodes(updated: Map<String, Node>): FieldTree {
        if (updated.isEmpty()) return this
        fun rewrite(current: Node): Node {
            val replacement = updated[current.path]
            val base = replacement ?: current
            if (base.children.isEmpty()) return base
            var changed = base !== current
            val children = base.children.map { child ->
                val newChild = rewrite(child)
                if (newChild !== child) changed = true
                newChild
            }
            return if (changed) base.copy(children = children) else base
        }
        val rootsAfter = roots.map { root -> updated[root.path] ?: rewrite(root) }
        return FieldTree(format, rootsAfter, warnings, truncated, readOnlyReason)
    }

    /** Returns a new tree with [edits] applied to the leaf values. */
    fun withEdits(edits: List<FieldEdit>): FieldTree {
        val updated = HashMap<String, Node>(edits.size)
        for (edit in edits) {
            val node = index[edit.path] ?: continue
            updated[edit.path] = node.copy(value = edit.newValue)
        }
        return withNodes(updated)
    }

    /** Returns a new tree with [node] swapped in, keeping its position. Used for AI renames. */
    fun withNode(node: Node): FieldTree = withNodes(mapOf(node.path to node))

    override fun toString(): String =
        "FieldTree(format=$format, nodes=$totalCount, editable=$editableCount, warnings=${warnings.size})"

    companion object {
        const val DEFAULT_SEARCH_LIMIT = 200

        /**
         * Case and separator insensitive comparison key: only letters and digits survive, so
         * "player.gold", "PLAYER GOLD" and "playergold" all collapse to the same needle.
         */
        fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
    }
}
