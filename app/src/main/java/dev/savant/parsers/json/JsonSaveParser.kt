package dev.savant.parsers.json

import dev.savant.codecs.ChecksumSpec
import dev.savant.codecs.CodecException
import dev.savant.codecs.CodecStrategy
import dev.savant.codecs.Codecs
import dev.savant.codecs.IdentityCodec
import dev.savant.domain.model.Confidence
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.Node
import dev.savant.domain.model.EditValidator
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.model.formatReal
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
 * Plain json: objects, arrays, numbers, strings, booleans, null.
 *
 * Reads through an optional codec (gzip/zlib payloads are decoded so the fields are visible), but
 * a codec that savant sniffed rather than one a plugin declared makes the file read-only: savant
 * will not recompress something it cannot promise to reproduce.
 */
class JsonSaveParser : SaveParser {

    override val format: SaveFormat = SaveFormat.JSON

    override fun detect(bytes: ByteArray, fileName: String?): Confidence {
        if (bytes.isEmpty()) return Confidence.none("empty file")

        val extension = fileName?.substringAfterLast('.', "")?.lowercase()
        val reasons = mutableListOf<String>()
        var score = 0f

        when (extension) {
            "json" -> {
                score += 0.55f
                reasons += "extension .json"
            }
            "json5", "sav", "save" -> {
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

        if (!FormatDetector.looksLikeJson(payload)) {
            return if (score > 0f) {
                Confidence.of(score.coerceAtMost(0.25f), (reasons + "but the content does not look like json").joinToString(", "))
            } else {
                Confidence.none("does not look like json")
            }
        }
        score += 0.45f
        reasons += "structure looks like json"

        if (payload.size <= PROBE_LIMIT) {
            val parsed = runCatching { JsonReader(payload).read() }
            if (parsed.isSuccess) {
                score += 0.5f
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
                "file is ${(bytes.size / 1_048_576.0).round(1)} mb, over the ${NodeLimits.MAX_FILE_BYTES / 1_048_576} mb limit",
                SaveFormat.JSON,
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
                throw CorruptFileException("${codec.label} payload could not be decoded: ${error.message}", SaveFormat.JSON, error)
            }
        }

        step("parsing json...")
        val reader = JsonReader(payload)
        val root = try {
            reader.read()
        } catch (error: SavantParserException) {
            throw error
        } catch (error: Exception) {
            throw CorruptFileException("json is corrupt: ${error.message}", SaveFormat.JSON, error)
        }
        step("read ${reader.parsedNodes} json values")

        val readOnlyReason = if (sniffed != null) {
            "payload is ${codec.label} compressed and no plugin declared it, so savant will not recompress it. " +
                "add a plugin with codec=\"${codec.id}\" to edit this file"
        } else {
            null
        }

        step("mapping fields...")
        val built = JsonTreeBuilder.build(
            root = root,
            maxNodes = request.hints.maxNodes,
            extraWarnings = reader.warnings,
            readOnlyReason = readOnlyReason,
        )
        step("found ${built.tree.editableCount} editable values in ${built.tree.totalCount} nodes")

        val document = JsonDocument(
            parser = this,
            root = root,
            style = reader.style,
            trailing = reader.trailing,
            codec = codec,
            checksum = request.hints.checksum,
            initialTree = built.tree,
            refs = built.refs,
            originalSize = bytes.size,
        )
        return ParseResult(
            document = document,
            tree = built.tree,
            detection = Confidence.of(0.99f, "parsed as json"),
            log = log,
        )
    }

    companion object {
        /** Files up to this size are fully parsed during detection; bigger ones are sniffed only. */
        const val PROBE_LIMIT = 512 * 1024

        private fun Double.round(decimals: Int): Double {
            var factor = 1.0
            repeat(decimals) { factor *= 10 }
            return Math.round(this * factor) / factor
        }
    }
}

/**
 * Live handle on an imported json file.
 *
 * Edits are written into the faithful document model, never into a re-serialised copy, so
 * formatting, key order and untouched literals survive.
 */
internal class JsonDocument(
    override val parser: SaveParser,
    private val root: JsonNode,
    private val style: JsonStyle,
    private val trailing: ByteArray,
    private val codec: CodecStrategy,
    private val checksum: ChecksumSpec?,
    initialTree: FieldTree,
    private val refs: Map<String, JsonRef>,
    override val originalSize: Int,
) : SaveDocument {

    override val format: SaveFormat = SaveFormat.JSON

    override var tree: FieldTree = initialTree
        private set

    /** Checksum state of the last [toBytes]; surfaced on the export card. */
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
            ref.write(jsonNodeFor(node, edit.newValue))
            updated[edit.path] = node.copy(
                value = edit.newValue,
                type = widenedType(node.type, edit.newValue),
            )
            applied += edit
        }

        if (applied.isNotEmpty()) tree = tree.withNodes(updated)
        return ApplyResult(tree, applied, rejected)
    }

    override fun toBytes(): ByteArray {
        var payload = JsonWriter(style).write(root, trailing)

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
                        checksumState = "checksum field ${spec.storedAtPath} not found; file written without recomputing it"
                        payload
                    } else {
                        // Two passes: digest the current payload, store it, serialise again.
                        val digest = if (spec.storedAsHexText) spec.digestText(payload) else spec.digestNumber(payload).toString()
                        ref.write(JNumber(digest, digest.toDoubleOrNull(), true))
                        checksumState = "recomputed ${spec.strategy.label} into ${spec.storedAtPath}"
                        JsonWriter(style).write(root, trailing)
                    }
                }
                else -> payload
            }
        }
        return codec.encode(payload)
    }

    private fun jsonNodeFor(node: Node, value: FieldValue): JsonNode = when (value) {
        FieldValue.Null -> JNull
        is FieldValue.Bool -> JBool(value.value)
        is FieldValue.Int64 -> {
            val raw = if (value.unsigned) java.lang.Long.toUnsignedString(value.value) else value.value.toString()
            JNumber(raw, value.value.toDouble(), integral = true)
        }
        is FieldValue.Real -> JNumber(formatReal(value.value), value.value, integral = false)
        is FieldValue.Text -> {
            // A numeric field edited through a text box still writes a json number.
            val numeric = node.type.isNumeric || node.value is FieldValue.Int64 || node.value is FieldValue.Real
            val asNumber = value.value.trim().toLongOrNull() ?: value.value.trim().toDoubleOrNull()
            if (numeric && asNumber != null) {
                val raw = value.value.trim()
                JNumber(raw, asNumber.toDouble(), integral = asNumber is Long)
            } else {
                JString(value.value, null)
            }
        }
    }

    /** Text formats have advisory widths: an int32 field holding 5e9 becomes i64, not an error. */
    private fun widenedType(current: FieldType, value: FieldValue): FieldType = when (value) {
        FieldValue.Null -> FieldType.NULL
        is FieldValue.Bool -> FieldType.BOOL
        is FieldValue.Int64 -> if (value.value in Int.MIN_VALUE..Int.MAX_VALUE && !value.unsigned) {
            FieldType.INT32
        } else {
            FieldType.INT64
        }
        is FieldValue.Real -> if (current == FieldType.FLOAT) FieldType.FLOAT else FieldType.DOUBLE
        is FieldValue.Text -> if (current.isNumeric && value.value.trim().toDoubleOrNull() != null) current else FieldType.STRING
    }
}
