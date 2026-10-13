package dev.savant.parsers.binary

import dev.savant.codecs.ChecksumException
import dev.savant.codecs.ChecksumSpec
import dev.savant.codecs.CodecException
import dev.savant.codecs.CodecStrategy
import dev.savant.codecs.GzipCodec
import dev.savant.codecs.IdentityCodec
import dev.savant.codecs.ZlibCodec
import dev.savant.domain.finder.BinaryValues
import dev.savant.domain.model.Confidence
import dev.savant.domain.model.EditValidator
import dev.savant.domain.model.Endianness
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.Node
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.model.asDouble
import dev.savant.domain.model.asLong
import dev.savant.domain.model.displayText
import dev.savant.domain.parser.ApplyResult
import dev.savant.domain.parser.CorruptFileException
import dev.savant.domain.parser.NodeLimits
import dev.savant.domain.parser.ParseLogLine
import dev.savant.domain.parser.ParseRequest
import dev.savant.domain.parser.ParseResult
import dev.savant.domain.parser.RejectedEdit
import dev.savant.domain.parser.SaveDocument
import dev.savant.domain.parser.SaveParser
import dev.savant.parsers.FormatDetector
import dev.savant.parsers.categorize.CategoryClassifier
import dev.savant.parsers.categorize.Humanizer
import dev.savant.plugins.PluginDocument
import dev.savant.plugins.PluginField
import dev.savant.plugins.PluginFieldType
import dev.savant.plugins.PluginMatch
import dev.savant.plugins.PluginValidator

/**
 * A save file read through a game definition instead of a format.
 *
 * This is the parser a plugin with `"parser": "binary"` describes: fields at fixed offsets, arrays
 * at a pointer plus a stride, an optional codec the payload is wrapped in and an optional digest
 * that has to be recomputed after every edit. It is the only way a file savant cannot parse as
 * text becomes writable — without a definition, a compressed payload stays read-only and says so.
 *
 * Everything about a file this parser writes is decided by the document: the width and endianness
 * of each value, how long a string field is, whether a digest has to be recomputed and where. There
 * is no expression to evaluate and no code to run, so a definition cannot do anything except
 * address bytes.
 */
class BinarySaveParser(
    val plugin: PluginDocument,
    private val codec: CodecStrategy = IdentityCodec,
    private val checksum: ChecksumSpec? = null,
) : SaveParser {

    override val format: SaveFormat = SaveFormat.BINARY

    /** "plugin \"example quest\"" — what the parse log and the chat call this reader. */
    val label: String get() = "plugin \"${plugin.gameName}\""

    /**
     * How well this definition explains the file.
     *
     * Two things can vouch for a definition: the file name matches one of its globs, or the digest
     * it declares actually matches the payload. Nothing else is evidence — a definition without a
     * glob and without a checksum never claims a file, because guessing would steal files from the
     * parser that can really read them.
     */
    override fun detect(bytes: ByteArray, fileName: String?): Confidence {
        val glob = PluginMatch.firstMatch(plugin.fileMatch, fileName)
        val magic = magicMatches(bytes)
        if (magic == false) {
            return Confidence.none("${codec.label} header expected, not found")
        }

        if (bytes.size > PROBE_LIMIT) {
            return if (glob != null) {
                Confidence.of(0.6f, "file name matches \"$glob\" (too large to check the payload)")
            } else {
                Confidence.none("no file name match and the file is too large to probe")
            }
        }

        val payload = runCatching { decode(bytes) }.getOrNull()
        if (payload == null) {
            return if (glob != null) {
                Confidence.of(0.5f, "file name matches \"$glob\", but the ${codec.label} payload does not decode")
            } else {
                Confidence.none("the ${codec.label} payload does not decode")
            }
        }

        val digestMatches = checksum != null && checksum.matches(payload)
        val fieldsFit = fieldsFit(payload)
        val name = glob?.let { "file name matches \"$it\"" }
        val digest = if (checksum != null) "${checksum.strategy.label} matches the stored digest" else null

        return when {
            digestMatches && name != null -> Confidence.certain("$name and the $digest")
            digestMatches -> Confidence.of(0.9f, "$digest, from ${label}")
            name != null && fieldsFit -> Confidence.of(0.7f, "$name, from ${label}")
            name != null -> Confidence.of(0.35f, "$name, but some mapped fields are outside the payload")
            else -> Confidence.none("no file name match and no digest to check")
        }
    }

    override fun parse(request: ParseRequest): ParseResult {
        val bytes = request.bytes
        val log = mutableListOf<ParseLogLine>()
        val warnings = mutableListOf<String>()

        if (bytes.size > NodeLimits.MAX_FILE_BYTES) {
            throw CorruptFileException(
                "this file is ${bytes.size / 1024 / 1024} mb; savant reads up to ${NodeLimits.MAX_FILE_BYTES / 1024 / 1024} mb",
                SaveFormat.BINARY,
            )
        }

        val payload = try {
            val decoded = decode(bytes)
            if (decoded !== bytes) log += ParseLogLine("decoded ${codec.label} payload (${bytes.size} -> ${decoded.size} bytes)")
            decoded
        } catch (error: CodecException) {
            throw CorruptFileException(
                "${codec.label} payload could not be decoded: ${error.message}",
                SaveFormat.BINARY,
                error,
            )
        }

        checksum?.let { spec ->
            when {
                spec.matches(payload) -> log += ParseLogLine("stored ${spec.strategy.label} matches the payload")
                else -> warnings += "the stored ${spec.strategy.label} does not match this payload — a different game build?"
            }
        }

        val built = buildTree(payload, request.hints.maxNodes, warnings, log)
        val document = BinaryDocument(
            parser = this,
            plugin = plugin,
            codec = codec,
            checksum = checksum,
            payload = payload,
            spans = built.spans,
            initialTree = built.tree,
            originalSize = bytes.size,
        )
        return ParseResult(
            document = document,
            tree = built.tree,
            detection = Confidence.of(0.99f, "read with ${label}"),
            log = log,
        )
    }

    // ---- detection helpers ------------------------------------------------------------

    /** `null` when this codec has no magic to check. */
    private fun magicMatches(bytes: ByteArray): Boolean? = when (codec) {
        GzipCodec -> FormatDetector.hasGzipMagic(bytes)
        ZlibCodec -> FormatDetector.hasZlibMagic(bytes)
        else -> null
    }

    private fun decode(bytes: ByteArray): ByteArray =
        if (codec === IdentityCodec) bytes else codec.decode(bytes)

    private fun fieldsFit(payload: ByteArray): Boolean = plugin.fields.all { field ->
        val offset = field.offset ?: return@all false
        val width = field.type.sizeBytes ?: field.length ?: return@all false
        offset >= 0 && offset + width <= payload.size
    }

    // ---- the tree ---------------------------------------------------------------------

    private class Built(
        val tree: FieldTree,
        val spans: Map<String, FieldSpan>,
    )

    /** Where one field's bytes are, and how to read and write them. */
    internal data class FieldSpan(
        val offset: Int,
        val type: PluginFieldType,
        val endianness: Endianness,
        val length: Int?,
    )

    private fun buildTree(
        payload: ByteArray,
        maxNodes: Int,
        warnings: MutableList<String>,
        log: MutableList<ParseLogLine>,
    ): Built {
        val spans = LinkedHashMap<String, FieldSpan>()
        val leafByPath = LinkedHashMap<String, Node>()
        val containerChildren = LinkedHashMap<String, MutableList<Node>>()
        val readOnlyReason = checksumOutsidePayload(payload)

        var budget = maxNodes.coerceAtMost(NodeLimits.MAX_NODES)
        var emitted = 0
        var truncated = false

        for (field in plugin.fields) {
            if (field.isArray) {
                val parent = field.path.substringBefore(PluginField.ELEMENT)
                val count = elementCount(payload, field, warnings)
                val capped = count.coerceAtMost(PluginValidator.MAX_ARRAY_ELEMENTS)
                if (count > capped) {
                    warnings += "${field.path}: showing the first $capped of $count elements"
                }
                if (capped == 0) {
                    log += ParseLogLine("${field.path}: the declared element count is 0")
                    continue
                }
                for (index in 0 until capped) {
                    if (emitted >= budget) {
                        warnings += "${field.path}: stopped at element $index, the ${NodeLimits.MAX_NODES} node budget is used up"
                        truncated = true
                        break
                    }
                    val element = elementAt(field, index) ?: continue
                    val width = field.type.sizeBytes ?: field.length ?: continue
                    if (!inBounds(payload, element, width)) {
                        warnings += "${field.path}: element $index is outside the ${payload.size} byte payload"
                        break
                    }
                    val path = field.pathFor(index)
                    val node = nodeFor(path, field, element, payload, readOnlyReason) ?: continue
                    leafByPath[path] = node
                    containerChildren.getOrPut(parent) { mutableListOf() } += node
                    spans[path] = FieldSpan(element, field.type, field.endianness, field.length)
                    emitted++
                }
                continue
            }

            val offset = field.offset ?: continue
            val width = field.type.sizeBytes ?: field.length ?: continue
            if (!inBounds(payload, offset, width)) {
                warnings += "${field.path}: bytes $offset..${offset + width} are outside the ${payload.size} byte payload"
                continue
            }
            if (emitted >= budget) {
                warnings += "stopped at ${field.path}, the ${NodeLimits.MAX_NODES} node budget is used up"
                truncated = true
                break
            }
            val at = offset.toInt()
            val node = nodeFor(field.path, field, at, payload, readOnlyReason) ?: continue
            leafByPath[field.path] = node
            spans[field.path] = FieldSpan(at, field.type, field.endianness, field.length)
            emitted++
        }

        // Roots keep the order of the document: a plain field is a root, an array is a container
        // whose children are its elements.
        val roots = mutableListOf<Node>()
        val placed = mutableSetOf<String>()
        for (field in plugin.fields) {
            if (field.isArray) {
                val parent = field.path.substringBefore(PluginField.ELEMENT)
                if (!placed.add(parent)) continue
                val children = containerChildren[parent] ?: continue
                roots += Node(
                    path = parent,
                    displayName = displayName(parent, plugin),
                    type = FieldType.LIST,
                    value = null,
                    category = CategoryClassifier.classify(parent, keyOf(parent), null, FieldType.LIST),
                    editable = false,
                    children = children,
                    sourceKey = keyOf(parent),
                )
            } else {
                if (!placed.add(field.path)) continue
                leafByPath[field.path]?.let { roots += it }
            }
        }

        val tree = FieldTree(
            format = SaveFormat.BINARY,
            roots = roots,
            warnings = warnings.toList(),
            truncated = truncated,
            readOnlyReason = readOnlyReason,
        )
        log += ParseLogLine("read $emitted field(s) with ${label}")
        if (readOnlyReason != null) log += ParseLogLine(readOnlyReason)
        return Built(tree, spans)
    }

    /** The element count a `pointer` declares, or 0 when it cannot be read. */
    private fun elementCount(payload: ByteArray, field: PluginField, warnings: MutableList<String>): Int {
        val pointer = field.pointer ?: return 0
        if (!inBounds(payload, pointer, 4)) {
            warnings += "${field.path}: the element count at $pointer is outside the ${payload.size} byte payload"
            return 0
        }
        val raw = BinaryValues.bits(payload, pointer.toInt(), 4, field.endianness)
        return when {
            raw <= 0 -> 0
            raw > Int.MAX_VALUE -> Int.MAX_VALUE
            else -> raw.toInt()
        }
    }

    private fun elementAt(field: PluginField, index: Int): Int? {
        val base = field.offset ?: return null
        val stride = field.stride ?: return null
        val at: Long = base + stride.toLong() * index
        return if (at > Int.MAX_VALUE) null else at.toInt()
    }

    private fun inBounds(payload: ByteArray, offset: Long, width: Int): Boolean =
        offset >= 0 && width > 0 && offset + width <= payload.size

    private fun inBounds(payload: ByteArray, offset: Int, width: Int): Boolean =
        inBounds(payload, offset.toLong(), width)

    private fun nodeFor(
        path: String,
        field: PluginField,
        offset: Int,
        payload: ByteArray,
        readOnlyReason: String?,
    ): Node? {
        val type = fieldType(field.type)
        val value = readValue(payload, offset, field) ?: return null
        val key = keyOf(path)
        return Node(
            path = path,
            displayName = displayName(path, plugin),
            type = type,
            value = value,
            category = CategoryClassifier.classify(path, key, value, type),
            editable = readOnlyReason == null,
            endianness = field.endianness,
            min = type.range?.start,
            max = type.range?.endInclusive,
            byteOffset = offset.toLong(),
            byteLength = field.type.sizeBytes ?: field.length,
            sourceKey = key,
            fixedWidth = true,
        )
    }

    /**
     * A definition may declare a digest that lives outside the payload it covers — most often a
     * header that the codec does not include. savant will read such a file but refuse to write it,
     * because an edit would leave a stale digest behind.
     */
    private fun checksumOutsidePayload(payload: ByteArray): String? {
        val spec = checksum ?: return null
        val offset = spec.storedAtOffset ?: return null
        val width = spec.storedWidthBytes
        if (offset + width <= payload.size) return null
        return "the ${spec.strategy.label} digest is stored at offset $offset, outside the ${payload.size} byte payload; " +
            "savant will not rewrite a file whose checksum it cannot recompute"
    }

    companion object {
        /** Files above this size are only sniffed, never fully read during detection. */
        const val PROBE_LIMIT = 512 * 1024

        fun fieldType(type: PluginFieldType): FieldType = when (type) {
            PluginFieldType.I8 -> FieldType.INT8
            PluginFieldType.U8 -> FieldType.UINT8
            PluginFieldType.I16 -> FieldType.INT16
            PluginFieldType.U16 -> FieldType.UINT16
            PluginFieldType.I32 -> FieldType.INT32
            PluginFieldType.U32 -> FieldType.UINT32
            PluginFieldType.I64 -> FieldType.INT64
            PluginFieldType.U64 -> FieldType.UINT64
            PluginFieldType.F32 -> FieldType.FLOAT
            PluginFieldType.F64 -> FieldType.DOUBLE
            PluginFieldType.BOOL -> FieldType.BOOL
            PluginFieldType.STR -> FieldType.STRING
        }

        private fun lastSegment(path: String): String =
            path.substringAfterLast('.').substringAfterLast('[').trimEnd(']')

        fun keyOf(path: String): String = lastSegment(path)

        fun displayName(path: String, plugin: PluginDocument): String {
            plugin.rename[path]?.let { return it }
            val key = lastSegment(path)
            val humanized = Humanizer.humanize(key)
            val bracket = path.lastIndexOf('[')
            if (bracket < 0) return humanized
            val index = path.substring(bracket).trim('[', ']')
            return "$humanized $index"
        }

        /** Reads one value; `null` when the bytes do not describe one. */
        fun readValue(payload: ByteArray, offset: Int, field: PluginField): FieldValue? {
            val type = field.type
            val size = type.sizeBytes
            return when (type) {
                PluginFieldType.BOOL -> FieldValue.Bool(payload[offset].toInt() != 0)
                PluginFieldType.STR -> {
                    val length = field.length ?: return null
                    if (offset + length > payload.size) return null
                    val slice = payload.copyOfRange(offset, offset + length)
                    val end = slice.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) slice.size else it }
                    FieldValue.Text(String(slice, 0, end, Charsets.UTF_8))
                }

                PluginFieldType.F32 -> {
                    val bits = BinaryValues.bits(payload, offset, size!!, field.endianness).toInt()
                    FieldValue.Real(Float.fromBits(bits).toDouble(), float32 = true)
                }

                PluginFieldType.F64 -> {
                    val bits = BinaryValues.bits(payload, offset, size!!, field.endianness)
                    FieldValue.Real(Double.fromBits(bits))
                }

                else -> {
                    val raw = BinaryValues.bits(payload, offset, size!!, field.endianness)
                    val value = if (type.signed) BinaryValues.signExtend(raw, size) else raw
                    FieldValue.Int64(value, unsigned = !type.signed)
                }
            }
        }

        /** Writes one value into [target]; returns a reason when it cannot be written. */
        internal fun writeValue(target: ByteArray, span: FieldSpan, value: FieldValue): String? {
            when (span.type) {
                PluginFieldType.BOOL -> {
                    val bool = when (value) {
                        is FieldValue.Bool -> value.value
                        is FieldValue.Int64 -> value.value != 0L
                        is FieldValue.Text -> value.value.equals("true", true)
                        else -> return "needs true or false"
                    }
                    target[span.offset] = if (bool) 1 else 0
                    return null
                }

                PluginFieldType.STR -> {
                    val length = span.length ?: return "this string field has no declared length"
                    val text = when (value) {
                        is FieldValue.Text -> value.value
                        else -> value.displayText()
                    }
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    if (bytes.size > length) {
                        return "does not fit the $length byte(s) this field has room for (needs ${bytes.size})"
                    }
                    java.util.Arrays.fill(target, span.offset, span.offset + length, 0)
                    bytes.copyInto(target, span.offset)
                    return null
                }

                PluginFieldType.F32 -> {
                    val number = value.asDouble() ?: return "needs a number"
                    writeBits(target, span.offset, 4, span.endianness, number.toFloat().toRawBits().toLong())
                    return null
                }

                PluginFieldType.F64 -> {
                    val number = value.asDouble() ?: return "needs a number"
                    writeBits(target, span.offset, 8, span.endianness, number.toRawBits())
                    return null
                }

                else -> {
                    val size = span.type.sizeBytes ?: return "a value needs a fixed width"
                    val number = value.asLong()
                        ?: value.asDouble()?.takeIf { it == it.toLong().toDouble() }?.toLong()
                        ?: return "not a whole number"
                    writeBits(target, span.offset, size, span.endianness, number)
                    return null
                }
            }
        }

        private fun writeBits(target: ByteArray, offset: Int, size: Int, endian: Endianness, raw: Long) {
            for (i in 0 until size) {
                val shift = if (endian == Endianness.LITTLE) 8 * i else 8 * (size - 1 - i)
                target[offset + i] = ((raw ushr shift) and 0xff).toByte()
            }
        }
    }
}

/**
 * Live handle on a plugin-described file.
 *
 * Edits are written straight into the decoded payload — a plugin field is a fixed span of bytes,
 * there is no structure to preserve — and [toBytes] recomputes the digest and re-applies the codec
 * afterwards, in that order, because the digest covers the decoded data.
 */
internal class BinaryDocument(
    override val parser: SaveParser,
    private val plugin: PluginDocument,
    private val codec: CodecStrategy,
    private val checksum: ChecksumSpec?,
    payload: ByteArray,
    private val spans: Map<String, BinarySaveParser.FieldSpan>,
    initialTree: FieldTree,
    override val originalSize: Int,
) : SaveDocument {

    private var payload: ByteArray = payload
    private var dirty = false

    override val format: SaveFormat = SaveFormat.BINARY

    override var tree: FieldTree = initialTree
        private set

    /** Last thing that happened to the digest, for the parse log. */
    var checksumState: String = if (checksum == null) {
        "this definition declares no checksum"
    } else {
        "ready to recompute ${checksum.strategy.label}"
    }
        private set

    override fun applyEdits(edits: List<FieldEdit>): ApplyResult {
        val reason = tree.readOnlyReason
        if (reason != null) {
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
            val span = spans[edit.path]
            if (span == null) {
                rejected += RejectedEdit(edit, "not writable: ${edit.path}")
                continue
            }
            val validation = EditValidator.validate(node, edit.newValue)
            if (!validation.ok) {
                rejected += RejectedEdit(edit, validation.reason ?: "invalid value")
                continue
            }
            if (!dirty) {
                // Copy on the first write, so a rejected edit never touches the payload.
                payload = payload.copyOf()
                dirty = true
            }
            val failure = BinarySaveParser.writeValue(payload, span, edit.newValue)
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
        var data = payload
        val spec = checksum
        if (spec != null) {
            data = try {
                val stamped = spec.apply(data)
                checksumState = "recomputed ${spec.strategy.label} at offset ${spec.storedAtOffset}"
                stamped
            } catch (error: ChecksumException) {
                checksumState = "checksum not recomputed: ${error.message}"
                data
            }
        }
        return if (codec === IdentityCodec) data else codec.encode(data)
    }

    /** The definition behind this file, for the UI line that says what read it. */
    val document: PluginDocument get() = plugin
}
