package dev.savant.parsers.json

import dev.savant.domain.model.FieldNode
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.classifyField
import dev.savant.domain.model.displayNameForKey
import dev.savant.domain.parser.ChecksumStrategy
import dev.savant.domain.parser.Confidence
import dev.savant.domain.parser.CorruptSaveException
import dev.savant.domain.parser.NoChecksumStrategy
import dev.savant.domain.parser.SaveParser
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

class JsonSaveParser @Inject constructor() : SaveParser {
    override val id: String = "json"
    override val displayName: String = "JSON"
    override val extensions: Set<String> = setOf("json", "sav", "save")
    override val checksumStrategy: ChecksumStrategy = NoChecksumStrategy

    private val json = Json {
        isLenient = false
        allowSpecialFloatingPointValues = false
        prettyPrint = true
    }

    override fun detect(bytes: ByteArray): Confidence {
        if (bytes.isEmpty()) return Confidence.None
        val prefix = bytes.decodeToString(0, minOf(bytes.size, 4096)).removePrefix("\uFEFF").trimStart()
        if (prefix.isEmpty()) return Confidence.None
        return when (prefix.first()) {
            '{', '[' -> Confidence.of(0.93f)
            '"', '-', in '0'..'9' -> Confidence.of(0.54f)
            't', 'f', 'n' -> Confidence.of(0.48f)
            else -> Confidence.None
        }
    }

    override fun parse(bytes: ByteArray): FieldTree {
        val source = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val element = try {
            json.parseToJsonElement(source)
        } catch (error: SerializationException) {
            throw CorruptSaveException("invalid JSON: ${error.message.orEmpty().lineSafe()}", error)
        } catch (error: IllegalArgumentException) {
            throw CorruptSaveException("invalid JSON: ${error.message.orEmpty().lineSafe()}", error)
        }
        return FieldTree(
            formatId = id,
            roots = listOf(toNode(element, key = "root", path = "$")),
        )
    }

    override fun write(tree: FieldTree): ByteArray {
        require(tree.formatId == id) { "JSON parser cannot write '${tree.formatId}'" }
        val root = tree.roots.singleOrNull()
            ?: throw CorruptSaveException("JSON tree must contain exactly one root")
        return json.encodeToString(JsonElement.serializer(), toJson(root)).encodeToByteArray()
    }

    private fun toNode(element: JsonElement, key: String, path: String): FieldNode = when (element) {
        is JsonObject -> FieldNode(
            path = path,
            displayName = displayNameForKey(key),
            type = FieldType.OBJECT,
            category = classifyField(path, key, FieldType.OBJECT),
            children = element.map { (childKey, child) ->
                toNode(child, childKey, objectPath(path, childKey))
            },
            metadata = mapOf("jsonKey" to key),
        )
        is JsonArray -> FieldNode(
            path = path,
            displayName = displayNameForKey(key),
            type = FieldType.LIST,
            category = classifyField(path, key, FieldType.LIST),
            children = element.mapIndexed { index, child ->
                toNode(child, index.toString(), "$path[$index]")
            },
            metadata = mapOf("jsonKey" to key),
        )
        is JsonNull -> scalarNode(
            path = path,
            key = key,
            type = FieldType.NULL,
            value = FieldValue.NullValue,
            editable = false,
        )
        is JsonPrimitive -> primitiveNode(element, key, path)
    }

    private fun primitiveNode(primitive: JsonPrimitive, key: String, path: String): FieldNode {
        if (primitive.isString) {
            return scalarNode(path, key, FieldType.STRING, FieldValue.StringValue(primitive.content))
        }
        primitive.booleanOrNull?.let {
            return scalarNode(path, key, FieldType.BOOL, FieldValue.BooleanValue(it))
        }
        primitive.longOrNull?.let {
            return scalarNode(path, key, FieldType.INT, FieldValue.IntegerValue(it))
        }
        primitive.doubleOrNull?.takeIf(Double::isFinite)?.let {
            return scalarNode(path, key, FieldType.FLOAT, FieldValue.FloatValue(it))
        }
        throw CorruptSaveException("unsupported value at $path")
    }

    private fun scalarNode(
        path: String,
        key: String,
        type: FieldType,
        value: FieldValue,
        editable: Boolean = true,
    ) = FieldNode(
        path = path,
        displayName = displayNameForKey(key),
        type = type,
        value = value,
        category = classifyField(path, key, type),
        editable = editable,
        metadata = mapOf("jsonKey" to key),
    )

    private fun toJson(node: FieldNode): JsonElement = when (node.type) {
        FieldType.OBJECT -> JsonObject(
            node.children.associate { child ->
                child.metadata["jsonKey"].orEmpty() to toJson(child)
            },
        )
        FieldType.LIST -> JsonArray(node.children.map(::toJson))
        FieldType.INT -> JsonPrimitive((node.value as FieldValue.IntegerValue).value)
        FieldType.FLOAT -> JsonPrimitive((node.value as FieldValue.FloatValue).value)
        FieldType.BOOL -> JsonPrimitive((node.value as FieldValue.BooleanValue).value)
        FieldType.STRING -> JsonPrimitive((node.value as FieldValue.StringValue).value)
        FieldType.ENUM -> JsonPrimitive((node.value as FieldValue.EnumValue).value)
        FieldType.NULL -> JsonNull
    }

    private fun objectPath(parent: String, key: String): String =
        if (key.matches(Identifier)) "$parent.$key"
        else "$parent[\"${key.replace("\\", "\\\\").replace("\"", "\\\"")}\"]"

    private fun String.lineSafe(): String = lineSequence().firstOrNull()?.take(180).orEmpty()

    private companion object {
        val Identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
