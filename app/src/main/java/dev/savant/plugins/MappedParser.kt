package dev.savant.plugins

import dev.savant.domain.model.AiGuess
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.Confidence
import dev.savant.domain.model.EditValidator
import dev.savant.domain.model.Node
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.parser.ApplyResult
import dev.savant.domain.parser.ParseLogLine
import dev.savant.domain.parser.ParseRequest
import dev.savant.domain.parser.ParseResult
import dev.savant.domain.parser.RejectedEdit
import dev.savant.domain.parser.SaveDocument
import dev.savant.domain.parser.SaveParser

/**
 * A text format savant already parses, with a plugin's names and limits laid over it.
 *
 * The second kind of plugin, and the more common one: a game writes ordinary json or xml, and what is
 * missing is not a parser but knowledge — that `gld` is gold, that it is currency, and that 999999 is
 * the most the game will accept. This decorator adds exactly that and changes nothing else: the base
 * parser still owns the bytes, so an untouched file is still written back identically.
 *
 * Detection only prefers the plugin when the plugin actually claims the file. A json plugin for one
 * game must not start winning every json save on the phone.
 */
class MappedParser(
    private val plugin: GamePlugin,
    private val base: SaveParser,
) : SaveParser {

    override val format: SaveFormat get() = base.format

    override val pluginId: String get() = plugin.id

    /**
     * A claim *adds* to the base parser's confidence rather than competing with it.
     *
     * "This is json" and "this is example quest's save" are both true, and knowing both is worth more
     * than either — which is also what makes the mapped parser win for the files its plugin claims, so
     * the names actually get applied. A file the plugin does not claim falls through to the base
     * score, because a json plugin for one game must not start winning every json save on the phone.
     */
    override fun detect(bytes: ByteArray, fileName: String?): Confidence {
        val baseScore = base.detect(bytes, fileName)
        val claim = PluginMatcher.score(plugin, fileName, bytes)
        if (!claim.usable) return baseScore
        val boosted = (baseScore.score + (1f - baseScore.score) * CLAIM_WEIGHT).coerceAtMost(0.99f)
        return Confidence(
            maxOf(boosted, claim.score),
            "${plugin.gameName}: ${claim.reason} · ${baseScore.reason}",
        )
    }

    override fun parse(request: ParseRequest): ParseResult {
        val result = base.parse(request)
        val mapped = applyMappings(result.tree)
        val document = MappedDocument(result.document) { tree -> applyMappings(tree).tree }
        val log = result.log + ParseLogLine(
            "plugin ${plugin.gameName}: ${mapped.count} field(s) named" +
                if (mapped.count == 0) " — none of its paths matched this file" else "",
        )
        return result.copy(document = document, tree = mapped.tree, log = log)
    }

    private fun applyMappings(tree: FieldTree): Mapped {
        if (plugin.fields.isEmpty() && plugin.rename.isEmpty()) return Mapped(tree, 0)
        val byPath = plugin.fields.associateBy { it.path }
        val updates = tree.all.mapNotNull { node ->
            val mapping = byPath[node.path] ?: byPath[wildcard(node.path)]
            if (mapping == null) {
                // No mapping for this node: a `rename` entry may still have something to say about it.
                val onlyRenamed = renamed(node) ?: return@mapNotNull null
                return@mapNotNull onlyRenamed.path to onlyRenamed
            }
            if (mapping.fieldType != null && mapping.fieldType != node.type) {
                // A plugin that disagrees with the file about the type is a warning, not a rewrite:
                // the bytes are the truth, and the mapping is somebody's note about them.
                return@mapNotNull node.path to node.copy(
                    displayName = mapping.displayName ?: node.displayName,
                    category = mapping.declaredCategory ?: node.category,
                    aiGuess = mapping.displayName?.let { AiGuess(it, 0.5f, "plugin ${plugin.gameName}, type differs") },
                )
            }
            node.path to node.copy(
                displayName = mapping.displayName ?: node.displayName,
                category = mapping.declaredCategory ?: node.category,
                min = mapping.min ?: node.min,
                max = mapping.max ?: node.max,
                editable = node.editable && mapping.editable,
                aiGuess = mapping.displayName?.let { AiGuess(it, 1f, "plugin ${plugin.gameName}") } ?: node.aiGuess,
            )
        }
        if (updates.isEmpty()) return Mapped(tree, 0)
        return Mapped(tree.withNodes(updates.toMap()), updates.size)
    }

    /**
     * The `rename` half of a plugin: a display name for a cryptic key, with no type and no limits.
     *
     * Matched on the raw key and on the last path segment, so `{"gld": "Gold"}` reaches `player.gld`
     * without the plugin author writing every path it appears at. Marked as a guess, because it is
     * somebody's note about the file and not the file's own claim.
     */
    private fun renamed(node: Node): Node? {
        if (plugin.rename.isEmpty()) return null
        val name = plugin.rename[node.sourceKey] ?: plugin.rename[node.path.substringAfterLast('.')] ?: return null
        if (node.aiGuess?.displayName == name) return null
        // A plugin's rename outranks whatever savant guessed from the key: a person said this, and a
        // person who plays the game knows more about it than a tokeniser does.
        return node.copy(aiGuess = AiGuess(name, 0.9f, "renamed by plugin ${plugin.gameName}"))
    }

    /** `party[2].hp` is matched by a mapping written as `party[*].hp`. */
    private fun wildcard(path: String): String = path.replace(Regex("\\[\\d+]"), "[*]")

    private data class Mapped(val tree: FieldTree, val count: Int)

    private companion object {
        /** How much of the remaining doubt a matching plugin removes. */
        const val CLAIM_WEIGHT = 0.5f
    }

    /**
     * Re-applies the mappings to every tree the base document produces.
     *
     * Without this the first edit would hand back the base parser's tree and every plugin name would
     * disappear from the screen mid session.
     */
    private class MappedDocument(
        private val inner: SaveDocument,
        private val map: (FieldTree) -> FieldTree,
    ) : SaveDocument {

        override val format: SaveFormat get() = inner.format

        override val parser: SaveParser get() = inner.parser

        override val tree: FieldTree get() = map(inner.tree)

        override val originalSize: Int get() = inner.originalSize

        /**
         * Validates against the *mapped* tree first.
         *
         * The base document only knows the file's own limits, so a plugin's maximum or its
         * `editable: false` would be decoration rather than a rule if this did not check them here.
         * What passes is handed to the base document, which still enforces everything it knows.
         */
        override fun applyEdits(edits: List<FieldEdit>): ApplyResult {
            val mapped = map(inner.tree)
            val allowed = mutableListOf<FieldEdit>()
            val refused = mutableListOf<RejectedEdit>()
            for (edit in edits) {
                val node = mapped.node(edit.path)
                val validation = node?.let { EditValidator.validate(it, edit.newValue) }
                if (validation != null && !validation.ok) {
                    refused += RejectedEdit(edit, validation.reason ?: "the plugin does not allow that")
                } else {
                    allowed += edit
                }
            }
            val result = inner.applyEdits(allowed)
            return ApplyResult(tree = map(result.tree), applied = result.applied, rejected = refused + result.rejected)
        }

        override fun toBytes(): ByteArray = inner.toBytes()
    }
}
