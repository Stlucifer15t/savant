package dev.savant.parsers.xml

import dev.savant.codecs.ChecksumSpec
import dev.savant.codecs.CodecException
import dev.savant.codecs.CodecStrategy
import dev.savant.codecs.Codecs
import dev.savant.codecs.IdentityCodec
import dev.savant.domain.model.Confidence
import dev.savant.domain.model.EditValidator
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.Node
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.parser.ApplyResult
import dev.savant.domain.parser.CorruptFileException
import dev.savant.domain.parser.NodeLimits
import dev.savant.domain.parser.ParseLogLine
import dev.savant.domain.parser.ParseRequest
import dev.savant.domain.parser.ParseResult
import dev.savant.domain.parser.RejectedEdit
import dev.savant.domain.parser.SaveDocument
import dev.savant.domain.parser.SaveParser
import dev.savant.domain.parser.SavantParserException
import dev.savant.parsers.FormatDetector

/**
 * Xml, including apple plist containers (unity PlayerPrefs).
 *
 * Same contract as json: read into a faithful model, expose a field tree, write back with only the
 * edited values changed. No DTD is ever resolved and no external entity is ever fetched.
 */
class XmlSaveParser : SaveParser {

    override val format: SaveFormat = SaveFormat.XML

    override fun detect(bytes: ByteArray, fileName: String?): Confidence {
        if (bytes.isEmpty()) return Confidence.none("empty file")

        val extension = fileName?.substringAfterLast('.', "")?.lowercase()
        val reasons = mutableListOf<String>()
        var score = 0f

        when (extension) {
            "xml" -> {
                score += 0.5f
                reasons += "extension .xml"
            }
            "plist" -> {
                score += 0.5f
                reasons += "extension .plist"
            }
            "config", "csproj", "sav", "save", "layout" -> {
                score += 0.2f
                reasons += "extension .$extension"
            }
        }

        val payload = decodeForProbe(bytes)
        if (payload == null) {
            reasons += "payload is compressed and could not be decoded"
            return Confidence.of(score.coerceAtMost(0.4f), reasons.joinToString(", "))
        }
        if (payload !== bytes) reasons += "compressed payload decodes"

        if (!FormatDetector.looksLikeXml(payload)) {
            return if (score > 0f) {
                Confidence.of(score.coerceAtMost(0.25f), (reasons + "but the content does not look like xml").joinToString(", "))
            } else {
                Confidence.none("does not look like xml")
            }
        }
        score += 0.45f
        reasons += if (FormatDetector.isApplePlist(payload)) "apple plist structure" else "structure looks like xml"

        if (payload.size <= PROBE_LIMIT) {
            val parsed = runCatching { XmlReader(payload).read() }
            if (parsed.isSuccess) {
                score += 0.45f
                reasons += "full parse succeeded"
            } else {
                score = minOf(score, 0.4f)
                reasons += "but parsing failed (${parsed.exceptionOrNull()?.message?.take(60)})"
            }
        } else {
            reasons += "too large for a probe parse"
        }
        return Confidence.of(score.coerceIn(0f, 1f), reasons.joinToString(", "))
    }

    private fun decodeForProbe(bytes: ByteArray): ByteArray? {
        val sniffed = Codecs.sniff(bytes).firstOrNull()?.first ?: return bytes
        return runCatching { sniffed.decode(bytes) }.getOrNull()
    }

    override fun parse(request: ParseRequest): ParseResult {
        val bytes = request.bytes
        if (bytes.size.toLong() > NodeLimits.MAX_FILE_BYTES) {
            throw SavantParserException(
                "file is ${(bytes.size / 1_048_576.0)} mb, over the ${NodeLimits.MAX_FILE_BYTES / 1_048_576} mb limit",
                SaveFormat.XML,
            )
        }

        val startedAt = System.nanoTime()
        val log = mutableListOf<ParseLogLine>()
        fun step(text: String) {
            log += ParseLogLine(text, (System.nanoTime() - startedAt) / 1_000_000)
        }

        step("reading ${bytes.size} bytes...")

        val sniffed = if (request.hints.codec == null) Codecs.sniff(bytes).firstOrNull() else null
        val codec: CodecStrategy = request.hints.codec ?: sniffed?.first ?: IdentityCodec
        val payload = if (codec === IdentityCodec) {
            bytes
        } else {
            step("decoding ${codec.label} payload...")
            try {
                codec.decode(bytes)
            } catch (error: CodecException) {
                throw CorruptFileException("${codec.label} payload could not be decoded: ${error.message}", SaveFormat.XML, error)
            }
        }

        step("parsing xml...")
        val reader = XmlReader(payload)
        val model = try {
            reader.read()
        } catch (error: SavantParserException) {
            throw error
        } catch (error: Exception) {
            throw CorruptFileException("xml is corrupt: ${error.message}", SaveFormat.XML, error)
        }
        if (model.isPlist) step("apple plist: mapping dict keys to fields")
        step("mapping fields...")

        val readOnlyReason = if (sniffed != null) {
            "payload is ${codec.label} compressed and no plugin declared it, so savant will not recompress it. " +
                "add a plugin with codec=\"${codec.id}\" to edit this file"
        } else {
            null
        }

        val built = XmlTreeBuilder.build(
            model = model,
            maxNodes = request.hints.maxNodes,
            extraWarnings = reader.warnings,
            readOnlyReason = readOnlyReason,
        )
        step("found ${built.tree.editableCount} editable values in ${built.tree.totalCount} nodes")

        val document = XmlDocument(
            parser = this,
            model = model,
            codec = codec,
            checksum = request.hints.checksum,
            initialTree = built.tree,
            refs = built.refs,
            originalSize = bytes.size,
        )
        return ParseResult(
            document = document,
            tree = built.tree,
            detection = Confidence.of(0.98f, if (model.isPlist) "parsed as an apple plist" else "parsed as xml"),
            log = log,
        )
    }

    companion object {
        const val PROBE_LIMIT = 512 * 1024
    }
}

/**
 * Live handle on an imported xml file.
 *
 * Xml has no types of its own: the type shown in the UI is inferred at parse time and stays stable
 * while editing, and the value written back is the plain text form of the new value (escaped).
 */
internal class XmlDocument(
    override val parser: SaveParser,
    private val model: XmlDocumentModel,
    private val codec: CodecStrategy,
    private val checksum: ChecksumSpec?,
    initialTree: FieldTree,
    private val refs: Map<String, XmlRef>,
    override val originalSize: Int,
) : SaveDocument {

    override val format: SaveFormat = SaveFormat.XML

    override var tree: FieldTree = initialTree
        private set

    var checksumState: String = if (checksum == null) "no checksum field" else "recomputed ${checksum.strategy.label}"
        private set

    override fun applyEdits(edits: List<FieldEdit>): ApplyResult {
        if (tree.readOnly) {
            val reason = tree.readOnlyReason ?: "file is read-only"
            return ApplyResult(tree, emptyList(), edits.map { RejectedEdit(it, reason) })
        }

        val applied = mutableListOf<FieldEdit>()
        val rejected = mutableListOf<RejectedEdit>()
        val updated = HashMap<String, Node>()

        for (edit in edits) {
            val node = tree.node(edit.path)
            if (node == null) {
                rejected += RejectedEdit(edit, "no such field: ${edit.path}")
                continue
            }
            val ref = refs[edit.path]
            if (ref == null) {
                rejected += RejectedEdit(edit, "not writable: ${edit.path}")
                continue
            }
            val validation = EditValidator.validate(node, edit.newValue)
            if (!validation.ok) {
                rejected += RejectedEdit(edit, validation.reason ?: "invalid value")
                continue
            }
            val literal = when (ref.kind) {
                XmlRef.Kind.PLIST_BOOL -> if (edit.newValue == dev.savant.domain.model.FieldValue.Bool(true)) "true" else "false"
                else -> XmlValues.literalFor(edit.newValue)
            }
            ref.write(literal)
            updated[edit.path] = node.copy(value = edit.newValue)
            applied += edit
        }

        if (applied.isNotEmpty()) tree = tree.withNodes(updated)
        return ApplyResult(tree, applied, rejected)
    }

    override fun toBytes(): ByteArray {
        var payload = XmlWriter().write(model)
        val spec = checksum
        if (spec != null) {
            payload = when {
                spec.storedAtOffset != null -> {
                    checksumState = "recomputed ${spec.strategy.label} at offset ${spec.storedAtOffset}"
                    spec.apply(payload)
                }
                spec.storedAtPath != null -> {
                    val ref = refs[spec.storedAtPath]
                    if (ref == null) {
                        checksumState = "checksum field ${spec.storedAtPath} not found; written without recomputing"
                        payload
                    } else {
                        val digest = if (spec.storedAsHexText) spec.digestText(payload) else spec.digestNumber(payload).toString()
                        ref.write(digest)
                        checksumState = "recomputed ${spec.strategy.label} into ${spec.storedAtPath}"
                        XmlWriter().write(model)
                    }
                }
                else -> payload
            }
        }
        return codec.encode(payload)
    }
}
