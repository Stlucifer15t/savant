package dev.savant.plugins

import dev.savant.codecs.ChecksumSpec
import dev.savant.codecs.CodecStrategy
import dev.savant.domain.finder.BinaryValues
import dev.savant.domain.model.Confidence
import dev.savant.domain.model.EditValidator
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.Node
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.model.AiGuess
import dev.savant.domain.model.Endianness
import dev.savant.domain.parser.ApplyResult
import dev.savant.domain.parser.ParseLogLine
import dev.savant.domain.parser.ParseRequest
import dev.savant.domain.parser.ParseResult
import dev.savant.domain.parser.RejectedEdit
import dev.savant.domain.parser.SaveDocument
import dev.savant.domain.parser.SaveParser
import dev.savant.domain.parser.SavantParserException
import dev.savant.parsers.categorize.CategoryClassifier
import dev.savant.parsers.categorize.Humanizer

/** One field a plugin resolved to a concrete offset in this file. */
internal data class ResolvedField(
    val path: String,
    val offset: Int,
    val type: FieldType,
    val endianness: Endianness,
    val mapping: FieldMapping,
    /** Bytes this field occupies: the type's width for numbers, the mapping's `length` for text. */
    val width: Int,
)

/**
 * A binary save, read through a plugin.
 *
 * This is the parser for every format savant does not understand natively: the plugin says where the
 * fields are, and the reading, writing, checksum repair and codec handling are the same deterministic
 * Kotlin the built in parsers use. Nothing here is generated, scripted or downloaded — a plugin is
 * data, and this class is the only thing that turns it into bytes.
 *
 * The order of operations on write is the reverse of the read: values into the payload, checksum over
 * the payload, then the codec around it. Getting that backwards is the classic way to produce a save
 * a game refuses to load.
 */
class BinaryPluginParser(private val plugin: GamePlugin) : SaveParser {

    override val format: SaveFormat get() = SaveFormat.BINARY

    override val pluginId: String get() = plugin.id

    override fun detect(bytes: ByteArray, fileName: String?): Confidence =
        PluginMatcher.score(plugin, fileName, bytes)

    override fun parse(request: ParseRequest): ParseResult {
        val (codec, codecProblem) = PluginCodec.codecFor(plugin)
        if (codec == null) {
            throw SavantParserException(codecProblem ?: "codec unavailable", format)
        }

        val payload = runCatching { codec.decode(request.bytes) }.getOrElse { error ->
            throw SavantParserException(
                "plugin ${plugin.gameName}: ${codec.label} could not decode this file (${error.message})",
                format,
            )
        }

        val log = mutableListOf<ParseLogLine>()
        val warnings = mutableListOf<String>()
        log += ParseLogLine("plugin ${plugin.gameName} · ${plugin.fields.size} mapping(s)")
        log += ParseLogLine("decoded ${payload.size} bytes with ${codec.label}")

        val checksum = plugin.checksum?.toSpec(plugin.endianness)
        var readOnlyReason: String? = null
        if (plugin.checksum != null && checksum == null) {
            readOnlyReason = "plugin declares checksum '${plugin.checksum.strategy}', which savant does not implement"
            warnings += readOnlyReason
        } else if (checksum != null) {
            val ok = runCatching { checksum.matches(payload) }.getOrDefault(false)
            log += ParseLogLine("checksum ${checksum.strategy.id}: ${if (ok) "matches" else "does not match, will be recomputed"}")
            if (!ok) {
                warnings += "the stored ${checksum.strategy.id} does not match the payload; savant recomputes it on export"
            }
        }

        val resolved = resolveFields(payload, warnings)
        if (resolved.isEmpty()) {
            throw SavantParserException(
                "plugin ${plugin.gameName} found none of its fields in this ${payload.size} byte file",
                format,
            )
        }
        log += ParseLogLine("resolved ${resolved.size} field(s) at their offsets")

        val nodes = resolved.map { field -> nodeFor(field, payload) }
        val tree = FieldTree(
            format = format,
            roots = nodes,
            warnings = warnings,
            readOnlyReason = readOnlyReason,
        )
        log += ParseLogLine("indexed ${tree.totalCount} values, ${tree.editableCount} editable")

        val document = BinaryPluginDocument(
            plugin = plugin,
            parser = this,
            codec = codec,
            checksum = checksum,
            payload = payload,
            fields = resolved.associateBy { it.path },
            tree = tree,
        )
        return ParseResult(document = document, tree = tree, detection = detect(request.bytes, request.fileName), log = log)
    }

    /** Expands every mapping into the concrete offsets this file has, skipping what does not fit. */
    private fun resolveFields(payload: ByteArray, warnings: MutableList<String>): List<ResolvedField> {
        val out = mutableListOf<ResolvedField>()
        for (mapping in plugin.fields) {
            val type = mapping.fieldType
            if (type == null) {
                warnings += "field '${mapping.path}' has type '${mapping.type}', which savant does not know"
                continue
            }
            val size = when {
                type.sizeBytes != null -> type.sizeBytes
                type == FieldType.STRING -> mapping.length ?: 0
                type == FieldType.BOOL -> mapping.length ?: 1
                else -> 0
            }
            if (size <= 0) {
                warnings += "field '${mapping.path}' has type '${mapping.type}' and no length, so savant cannot size it"
                continue
            }
            val endianness = mapping.endianness ?: plugin.endianness
            val pointerAt = mapping.pointer
            if (pointerAt != null) {
                val base = readPointer(payload, mapping, pointerAt, warnings) ?: continue
                val stride = (mapping.stride ?: size).coerceAtLeast(1)
                val room = ((payload.size - base - mapping.recordOffset) / stride).coerceAtLeast(0)
                val count = (mapping.count ?: room).coerceIn(0, MAX_ARRAY_ELEMENTS)
                if (mapping.count == null && count < room) warnings += "'${mapping.path}' capped at $count elements"
                if (count == 0) {
                    warnings += "'${mapping.path}' points at $base, which leaves no room for a record"
                    continue
                }
                for (index in 0 until count) {
                    val offset = base + mapping.recordOffset + index * stride
                    if (offset < 0 || offset + size > payload.size) break
                    out += ResolvedField(expandPath(mapping.path, index), offset, type, endianness, mapping, size)
                }
            } else if (mapping.offset != null) {
                if (mapping.offset < 0 || mapping.offset + size > payload.size) {
                    warnings += "'${mapping.path}' at ${mapping.offset} does not fit in ${payload.size} bytes"
                    continue
                }
                out += ResolvedField(mapping.path, mapping.offset, type, endianness, mapping, size)
            } else {
                warnings += "'${mapping.path}' has neither an offset nor a pointer"
            }
        }
        return out
    }

    /**
     * The value at `pointer` is treated as an offset into the decoded payload, minus `pointerBase`
     * for games that store a load address instead.
     */
    private fun readPointer(
        payload: ByteArray,
        mapping: FieldMapping,
        pointerAt: Int,
        warnings: MutableList<String>,
    ): Int? {
        val pointerType = FieldType.fromTag(mapping.pointerType) ?: FieldType.UINT32
        val size = pointerType.sizeBytes ?: 4
        if (pointerAt + size > payload.size) {
            warnings += "'${mapping.path}' reads its pointer at $pointerAt, past the end of the payload"
            return null
        }
        val raw = BinaryValues.read(payload, pointerAt, pointerType, mapping.endianness ?: plugin.endianness)
        val value = raw?.asPointerValue() ?: 0L
        val base = (value - mapping.pointerBase).toInt()
        if (base < 0 || base >= payload.size) {
            warnings += "'${mapping.path}' points at $base, which is outside the ${payload.size} byte payload"
            return null
        }
        return base
    }

    private fun FieldValue.asPointerValue(): Long = when (this) {
        is FieldValue.Int64 -> if (unsigned) java.lang.Long.toUnsignedString(value).toLongOrNull() ?: value else value
        is FieldValue.Real -> value.toLong()
        is FieldValue.Text -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun expandPath(path: String, index: Int): String =
        if (path.contains("[*]")) path.replace("[*]", "[$index]") else "$path[$index]"

    /** Numbers come from [BinaryValues]; text and flags are length based, so they are read here. */
    private fun valueFor(field: ResolvedField, payload: ByteArray): FieldValue? = when (field.type) {
        FieldType.STRING -> {
            val to = (field.offset + field.width).coerceAtMost(payload.size)
            if (field.offset >= to) null
            else FieldValue.Text(String(payload, field.offset, to - field.offset, Charsets.UTF_8).trimEnd('\u0000'))
        }

        FieldType.BOOL -> FieldValue.Bool(BinaryValues.bits(payload, field.offset, field.width, field.endianness) != 0L)

        else -> BinaryValues.read(payload, field.offset, field.type, field.endianness)
    }

    /** Writes a text field, NUL padded to its own width. Too long is refused, never truncated. */
    private fun writeText(payload: ByteArray, field: ResolvedField, value: FieldValue): String? {
        val text = (value as? FieldValue.Text)?.value ?: return "a ${field.type.tag} field needs text"
        val encoded = text.toByteArray(Charsets.UTF_8)
        if (encoded.size > field.width) {
            return "${encoded.size} bytes does not fit in the ${field.width} byte field"
        }
        for (i in 0 until field.width) {
            payload[field.offset + i] = if (i < encoded.size) encoded[i] else 0
        }
        return null
    }

    /** Writes a flag as [width] bytes of zero or one. */
    private fun writeFlag(payload: ByteArray, field: ResolvedField, value: FieldValue): Boolean {
        val flag = when (value) {
            is FieldValue.Bool -> if (value.value) 1L else 0L
            is FieldValue.Int64 -> value.value
            else -> return false
        }
        if (field.offset < 0 || field.offset + field.width > payload.size) return false
        for (i in 0 until field.width) {
            val shift = if (field.endianness == Endianness.BIG) 8 * (field.width - 1 - i) else 8 * i
            payload[field.offset + i] = ((flag ushr shift) and 0xFF).toByte()
        }
        return true
    }

    private fun nodeFor(field: ResolvedField, payload: ByteArray): Node {
        val mapping = field.mapping
        val value = valueFor(field, payload)
        val key = mapping.path.substringAfterLast('.').removeSuffix("[*]")
        val declared = mapping.declaredCategory
        return Node(
            path = field.path,
            displayName = mapping.displayName ?: Humanizer.humanize(key),
            type = field.type,
            value = value,
            category = declared ?: CategoryClassifier.classify(field.path, key, value, field.type),
            editable = mapping.editable,
            endianness = field.endianness,
            min = mapping.min ?: field.type.range?.start,
            max = mapping.max ?: field.type.range?.endInclusive,
            byteOffset = field.offset.toLong(),
            byteLength = field.width,
            sourceKey = mapping.path,
            fixedWidth = true,
            aiGuess = mapping.displayName?.let { AiGuess(it, 1f, "plugin ${plugin.gameName}") },
        )
    }

    companion object {
        /** A plugin array without a declared count is inferred, and inference needs a ceiling. */
        const val MAX_ARRAY_ELEMENTS = 512
    }
}

/**
 * The live document behind a plugin parse.
 *
 * Edits go straight into the decoded payload at the resolved offset, so an untouched file is written
 * back byte for byte and a touched one changes only where the user asked. The checksum is recomputed
 * over the payload and the codec is applied last, every time.
 */
internal class BinaryPluginDocument(
    private val plugin: GamePlugin,
    override val parser: SaveParser,
    private val codec: CodecStrategy,
    private val checksum: ChecksumSpec?,
    private val payload: ByteArray,
    private val fields: Map<String, ResolvedField>,
    override var tree: FieldTree,
) : SaveDocument {

    override val format: SaveFormat get() = SaveFormat.BINARY

    override val originalSize: Int get() = payload.size

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
            val field = fields[edit.path]
            if (field == null) {
                rejected += RejectedEdit(edit, "not writable: ${edit.path}")
                continue
            }
            val validation = EditValidator.validate(node, edit.newValue)
            if (!validation.ok) {
                rejected += RejectedEdit(edit, validation.reason ?: "invalid value")
                continue
            }
            val failure = when (field.type) {
                FieldType.STRING -> writeText(payload, field, edit.newValue)
                FieldType.BOOL -> if (writeFlag(payload, field, edit.newValue)) {
                    null
                } else {
                    "that is not a flag value"
                }

                else -> if (BinaryValues.writeInto(payload, field.offset, edit.newValue, field.type, field.endianness)) {
                    null
                } else {
                    "offset ${field.offset} is outside the ${payload.size} byte payload"
                }
            }
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
        val spec = checksum
        val sealed = if (spec == null) {
            payload
        } else {
            runCatching { spec.apply(payload) }.getOrElse { error ->
                throw dev.savant.codecs.ChecksumException(
                    "plugin ${plugin.gameName}: ${error.message ?: "the checksum could not be written"}",
                    error,
                )
            }
        }
        return codec.encode(sealed)
    }
}
