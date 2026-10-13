package dev.savant.parsers.json

import dev.savant.domain.model.FieldValue

/**
 * A small public json value tree.
 *
 * The faithful model in this package is `internal` on purpose — it exists to reproduce a save file
 * byte for byte. Savant also needs to read and write its *own* json documents: chat history
 * payloads, plugin definitions, exported field maps. Those do not need byte fidelity, they need a
 * pleasant api, so they go through [Json] and [JsonPayload] and are encoded and decoded by the same
 * tested reader and writer.
 */
sealed interface Json {

    data object Null : Json

    data class Bool(val value: Boolean) : Json

    /** [raw] is what gets written back, so an integer never comes out as `1.0`. */
    data class Num(val value: Double, val raw: String = format(value)) : Json {
        val asLong: Long get() = value.toLong()
        val integral: Boolean get() = value == asLong.toDouble() && !raw.contains('.') && !raw.contains('e') && !raw.contains('E')

        companion object {
            fun format(value: Double): String =
                if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) value.toLong().toString()
                else value.toString()
        }
    }

    data class Str(val value: String) : Json

    data class Arr(val items: List<Json> = emptyList()) : Json

    /** Ordered members; duplicate keys survive a round trip, first one wins on lookup. */
    data class Obj(val members: List<Pair<String, Json>> = emptyList()) : Json {
        operator fun get(key: String): Json? = members.firstOrNull { it.first == key }?.second
        fun with(key: String, value: Json): Obj {
            val index = members.indexOfFirst { it.first == key }
            return if (index < 0) Obj(members + (key to value))
            else Obj(members.mapIndexed { i, pair -> if (i == index) pair.first to value else pair })
        }
    }

    // ---- accessors, so callers never write a cast -------------------------------

    fun string(key: String): String? = (child(key) as? Str)?.value
    fun stringOrNull(key: String): String? = string(key)
    fun bool(key: String): Boolean? = (child(key) as? Bool)?.value
    fun double(key: String): Double? = (child(key) as? Num)?.value
    fun long(key: String): Long? = (child(key) as? Num)?.asLong
    fun int(key: String): Int? = long(key)?.toInt()
    fun obj(key: String): Obj? = child(key) as? Obj
    fun arr(key: String): Arr? = child(key) as? Arr
    fun list(key: String): List<Json> = (child(key) as? Arr)?.items ?: emptyList()
    fun strings(key: String): List<String> = list(key).mapNotNull { (it as? Str)?.value }

    private fun child(key: String): Json? = when (this) {
        is Obj -> this[key]
        else -> null
    }

    /** `Str` or `Num` as text, which is what a tolerant reader wants. */
    val asText: String?
        get() = when (this) {
            is Str -> value
            is Num -> raw
            is Bool -> value.toString()
            else -> null
        }
}

/** Builders that keep call sites short: `obj("name" to str("save.json"))`. */
fun str(value: String): Json.Str = Json.Str(value)
fun str(value: String?): Json = if (value == null) Json.Null else Json.Str(value)
fun num(value: Long): Json.Num = Json.Num(value.toDouble(), value.toString())
fun num(value: Int): Json.Num = Json.Num(value.toDouble(), value.toString())
fun num(value: Double): Json.Num = Json.Num(value)
fun num(value: Long?): Json = if (value == null) Json.Null else num(value)
fun num(value: Int?): Json = if (value == null) Json.Null else num(value)
fun num(value: Double?): Json = if (value == null) Json.Null else num(value)
fun bool(value: Boolean): Json.Bool = Json.Bool(value)
fun bool(value: Boolean?): Json = if (value == null) Json.Null else Json.Bool(value)
fun arr(vararg items: Json): Json.Arr = Json.Arr(items.toList())
fun arr(items: List<out Json>): Json.Arr = Json.Arr(items.toList())
fun obj(vararg members: Pair<String, Json>): Json.Obj = Json.Obj(members.toList())
fun obj(members: List<out Pair<String, Json>>): Json.Obj = Json.Obj(members.toList())

/** A field value as json, and back — used by history payloads and plugin field maps. */
fun jsonOf(value: FieldValue?): Json = when (value) {
    null, FieldValue.Null -> Json.Null
    is FieldValue.Bool -> Json.Bool(value.value)
    is FieldValue.Text -> Json.Str(value.value)
    is FieldValue.Int64 -> Json.Num(value.value.toDouble(), value.value.toString())
    is FieldValue.Real -> if (value.float32) Json.Num(value.value, value.value.toFloat().toString()) else Json.Num(value.value)
}

/**
 * The field value a payload holds.
 *
 * [type] is the type the *field* declares, and it is the only thing allowed to reinterpret a stored
 * value: with no type, a string stays a string. Without that rule `"007"` comes back as the number
 * 7 and `"true"` as a boolean, which is exactly the kind of quiet corruption a transcript must not
 * have — history has to read back as what was written.
 */
fun jsonToValue(json: Json?, type: dev.savant.domain.model.FieldType? = null): FieldValue {
    val textual = type == dev.savant.domain.model.FieldType.STRING || type == dev.savant.domain.model.FieldType.ENUM
    return when (json) {
        null, Json.Null -> FieldValue.Null
        is Json.Bool -> if (textual) FieldValue.Text(json.value.toString()) else FieldValue.Bool(json.value)
        is Json.Str -> when {
            type == null || textual -> FieldValue.Text(json.value)
            else -> FieldValue.parse(json.value, type)
        }
        is Json.Num -> when {
            textual -> FieldValue.Text(json.raw)
            json.integral -> FieldValue.Int64(json.asLong)
            else -> FieldValue.Real(json.value)
        }
        is Json.Arr, is Json.Obj -> FieldValue.Text(JsonPayload.write(json))
    }
}

/**
 * Encode and decode savant's own json documents.
 *
 * Compact output, no trailing newline: these payloads live in a database column, not in a file a
 * human diffs. Decoding is the lenient [JsonReader], so a payload written by an older build still
 * reads.
 */
object JsonPayload {

    fun write(value: Json): String = String(JsonWriter(JsonStyle.COMPACT).write(toNode(value)), Charsets.UTF_8)

    fun read(text: String): Json = fromNode(JsonReader(text.toByteArray(Charsets.UTF_8)).read())

    /** Never throws: a corrupt payload degrades to [Json.Null] instead of taking the screen down. */
    fun readOrEmpty(text: String?): Json =
        if (text.isNullOrBlank()) Json.Null else runCatching { read(text) }.getOrDefault(Json.Null)

    private fun toNode(value: Json): JsonNode = when (value) {
        Json.Null -> JNull
        is Json.Bool -> JBool(value.value)
        is Json.Num -> JNumber(value.raw, value.value, value.integral)
        is Json.Str -> JString(value.value, null)
        is Json.Arr -> JArray(value.items.map { toNode(it) }.toMutableList())
        is Json.Obj -> JObject(
            value.members.map { (key, child) -> JMember(null, key, toNode(child)) }.toMutableList(),
        )
    }

    private fun fromNode(node: JsonNode): Json = when (node) {
        JNull -> Json.Null
        is JBool -> Json.Bool(node.value)
        is JNumber -> Json.Num(node.value ?: Double.NaN, node.raw)
        is JString -> Json.Str(node.value)
        is JArray -> Json.Arr(node.items.map { fromNode(it) })
        is JObject -> Json.Obj(node.members.map { it.key to fromNode(it.value) })
    }
}
