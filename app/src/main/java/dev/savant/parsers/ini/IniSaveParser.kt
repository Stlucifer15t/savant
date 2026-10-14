package dev.savant.parsers.ini

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
 * Ini, cfg, conf and java properties style files: `[section]` headers and `key = value` lines.
 *
 * Same contract as json and xml: read into a faithful model, expose a field tree, write back with
 * only the edited values changed. The model is the file's own lines, so comments, blank lines,
 * indentation, separator spacing, quoting and line endings all survive an edit untouched.
 */
class IniSaveParser : SaveParser {

    override val format: SaveFormat = SaveFormat.INI

    override fun detect(bytes: ByteArray, fileName: String?): Confidence {
        if (bytes.isEmpty()) return Confidence.none("empty file")

        val extension = fileName?.substringAfterLast('.', "")?.lowercase()
        val reasons = mutableListOf<String>()
        var score = 0f

        when (extension) {
            "ini", "cfg", "conf", "properties" -> {
                score += 0.45f
                reasons += "extension .$extension"
            }
            "txt", "sav", "dat", "config", "settings", "prefs", "options" -> {
                score += 0.15f
                reasons += "extension .$extension"
            }
        }

        val payload = decodeForProbe(bytes)
        if (payload == null) {
            reasons += "payload is compressed and could not be decoded"
            return Confidence.of(score.coerceAtMost(0.4f), reasons.joinToString(", "))
        }
        if (payload !== bytes) reasons += "compressed payload decodes"

        if (!FormatDetector.looksLikeIni(payload)) {
            return if (score > 0f) {
                Confidence.of(score.coerceAtMost(0.25f), (reasons + "but the content does not look like ini").joinToString(", "))
            } else {
                Confidence.none("does not look like ini")
            }
        }
        score += 0.35f
        reasons += "section/key=value lines"

        if (payload.size <= PROBE_LIMIT) {
            val parsed = runCatching { IniReader(payload).read() }
            val model = parsed.getOrNull()
            if (model != null && model.entryCount > 0) {
                score += 0.3f
                reasons += "${model.entryCount} key(s) in ${model.sectionCount} section(s)"
            } else {
                score = minOf(score, 0.3f)
                reasons += if (model == null) {
                    "but parsing failed (${parsed.exceptionOrNull()?.message?.take(60)})"
                } else {
                    "but no key = value line parsed"
                }
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
                SaveFormat.INI,
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
                throw CorruptFileException("${codec.label} payload could not be decoded: ${error.message}", SaveFormat.INI, error)
            }
        }

        step("parsing ini...")
        val reader = IniReader(payload)
        val model = try {
            reader.read()
        } catch (error: SavantParserException) {
            throw error
        } catch (error: Exception) {
            throw CorruptFileException("ini could not be read: ${error.message}", SaveFormat.INI, error)
        }
        if (model.entryCount == 0) {
            throw SavantParserException("no key = value lines found; there is nothing to edit", SaveFormat.INI)
        }
        step("found ${model.entryCount} key(s) in ${model.sectionCount} section(s)")
        step("mapping fields...")

        val readOnlyReason = if (sniffed != null) {
            "payload is ${codec.label} compressed and no plugin declared it, so savant will not recompress it. " +
                "add a plugin with codec=\"${codec.id}\" to edit this file"
        } else {
            null
        }

        val built = IniTreeBuilder.build(
            model = model,
            maxNodes = request.hints.maxNodes,
            extraWarnings = reader.warnings,
            readOnlyReason = readOnlyReason,
        )
        step("found ${built.tree.editableCount} editable values in ${built.tree.totalCount} nodes")

        val document = IniDocument(
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
            detection = Confidence.of(0.98f, "parsed as ini"),
            log = log,
        )
    }

    companion object {
        const val PROBE_LIMIT = 512 * 1024
    }
}

/**
 * Live handle on an imported ini file.
 *
 * Ini has no types of its own: the type shown in the UI is inferred at parse time and stays stable
 * while editing, and the value written back is the plain text form of the new value, spliced into
 * the line it came from.
 */
internal class IniDocument(
    override val parser: SaveParser,
    private val model: IniDocumentModel,
    private val codec: CodecStrategy,
    private val checksum: ChecksumSpec?,
    initialTree: FieldTree,
    private val refs: Map<String, IniLine>,
    override val originalSize: Int,
) : SaveDocument {

    override val format: SaveFormat = SaveFormat.INI

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
            val line = refs[edit.path]
            if (line == null) {
                rejected += RejectedEdit(edit, "not writable: ${edit.path}")
                continue
            }
            val validation = EditValidator.validate(node, edit.newValue)
            if (!validation.ok) {
                rejected += RejectedEdit(edit, validation.reason ?: "invalid value")
                continue
            }
            val literal = IniValues.literalFor(edit.newValue, line.valueText)
            val failure = line.setValue(literal)
            if (failure != null) {
                rejected += RejectedEdit(edit, failure)
                continue
            }
            updated[edit.path] = node.copy(value = edit.newValue)
            applied += edit
        }

        if (applied.isNotEmpty()) tree = tree.withNodes(updated)
        return ApplyResult(tree, applied, rejected)
    }

    override fun toBytes(): ByteArray {
        var payload = IniWriter.write(model)
        val spec = checksum
        if (spec != null) {
            payload = when {
                spec.storedAtOffset != null -> {
                    checksumState = "recomputed ${spec.strategy.label} at offset ${spec.storedAtOffset}"
                    spec.apply(payload)
                }
                spec.storedAtPath != null -> {
                    val line = refs[spec.storedAtPath]
                    if (line == null) {
                        checksumState = "checksum field ${spec.storedAtPath} not found; written without recomputing"
                        payload
                    } else {
                        val digest = if (spec.storedAsHexText) spec.digestText(payload) else spec.digestNumber(payload).toString()
                        line.setValue(digest)
                        checksumState = "recomputed ${spec.strategy.label} into ${spec.storedAtPath}"
                        IniWriter.write(model)
                    }
                }
                else -> payload
            }
        }
        return codec.encode(payload)
    }
}

/** Concatenates the lines. There is nothing to decide: every byte is already in the model. */
internal object IniWriter {

    private val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())

    fun write(model: IniDocumentModel): ByteArray {
        var size = if (model.bom) UTF8_BOM.size else 0
        for (line in model.lines) size += line.raw.size + line.terminator.size
        val out = ByteArray(size)
        var at = 0
        if (model.bom) {
            UTF8_BOM.copyInto(out, at)
            at += UTF8_BOM.size
        }
        for (line in model.lines) {
            line.raw.copyInto(out, at)
            at += line.raw.size
            line.terminator.copyInto(out, at)
            at += line.terminator.size
        }
        return out
    }
}
