package dev.savant.plugins

import dev.savant.domain.model.AiGuess
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.Confidence
import dev.savant.domain.model.Node
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.parser.ApplyResult
import dev.savant.domain.parser.ParseLogLine
import dev.savant.domain.parser.ParseRequest
import dev.savant.domain.parser.ParseResult
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
        if (plugin.fields.isEmpty()) return Mapped(tree, 0)
        val byPath = plugin.fields.associateBy { it.path }
        val updates = tree.all.mapNotNull { node ->
            val mapping = byPath[node.path] ?: byPath[wildcard(node.path)] ?: return@mapNotNull null
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

        override fun applyEdits(edits: List<FieldEdit>): ApplyResult {
            val result = inner.applyEdits(edits)
            return result.copy(tree = map(result.tree))
        }

        override fun toBytes(): ByteArray = inner.toBytes()
    }
}
