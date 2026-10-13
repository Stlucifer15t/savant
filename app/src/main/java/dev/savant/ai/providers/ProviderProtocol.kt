package dev.savant.ai.providers

import dev.savant.ai.LlmChunk
import dev.savant.ai.LlmRequest
import dev.savant.ai.LlmResponse
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderProfile
import dev.savant.ai.ToolCall
import dev.savant.ai.ToolParameter
import dev.savant.ai.ToolSpec
import dev.savant.domain.model.FieldValue
import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import dev.savant.parsers.json.arr
import dev.savant.parsers.json.bool
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/** What went wrong, in a sentence the chat can print. [retryable] drives the "try again" hint. */
data class ProviderError(val message: String, val retryable: Boolean = false)

/**
 * One provider dialect: how a request is shaped, where it goes, and how the answer comes back.
 *
 * Everything above this interface — the tool loop, the privacy rules, the diff gate — is shared, so
 * adding a provider is one class with no privileged access to savant. Implementations are pure: no
 * sockets, no android, no state. That is what lets every request body and every stream event be
 * asserted in a unit test with no key and no network.
 */
interface ProviderProtocol {

    val kind: ProviderKind

    val label: String get() = kind.label

    /** Full url for [profile], including any key carried in a query parameter. */
    fun endpoint(profile: ProviderProfile, streaming: Boolean): String

    /** Auth and version headers. [apiKey] is null when the user has not entered one. */
    fun headers(profile: ProviderProfile, apiKey: String?): Map<String, String>

    fun body(profile: ProviderProfile, request: LlmRequest, streaming: Boolean): String

    /** A 2xx body. Throws [dev.savant.ai.net.TransportException] when the payload is not the dialect. */
    fun parse(body: String, profile: ProviderProfile): LlmResponse

    /** A non-2xx body, turned into the sentence the user sees. */
    fun error(status: Int, body: String): ProviderError

    /** Fresh accumulator for one streaming exchange. */
    fun streamReader(profile: ProviderProfile): StreamReader

    /**
     * Where this dialect lists the models an account may use. Savant reads it so a model name is
     * picked from what the endpoint actually offers instead of being typed from memory.
     *
     * A `GET`, never a `POST`: listing models must not be able to cost a completion.
     */
    fun modelsEndpoint(profile: ProviderProfile): String

    /**
     * The model ids in a `/models` answer, in the order the answer gave them.
     * Returns an empty list for a payload that is not this dialect's listing shape.
     */
    fun parseModels(body: String): List<String>

    companion object {

        fun of(kind: ProviderKind): ProviderProtocol = when (kind) {
            ProviderKind.CLAUDE -> ClaudeProtocol
            ProviderKind.GEMINI -> GeminiProtocol
            // openrouter and any self hosted endpoint speak the openai chat completions shape.
            ProviderKind.OPENAI, ProviderKind.OPENROUTER, ProviderKind.CUSTOM -> OpenAiProtocol
        }

        /** `https://host/v1/chat/completions` -> `host/v1/chat/completions`, for the request log. */
        fun endpointLabel(url: String): String = url
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('?')

        /**
         * Appends the path to a base url without doubling or dropping slashes.
         * A user who pastes `https://host/v1/` gets `https://host/v1/chat/completions`.
         */
        fun join(baseUrl: String, path: String): String =
            baseUrl.trimEnd('/') + "/" + path.trimStart('/')

        /** 401, 429 and 5xx in one place, so the copy is identical across providers. */
        fun describe(status: Int, detail: String?): ProviderError {
            val trimmed = detail?.trim()?.takeIf { it.isNotEmpty() }
            val message = when (status) {
                401, 403 -> "the key was rejected (http $status)" + tail(trimmed)
                404 -> "no such model or endpoint (http 404)" + tail(trimmed)
                408 -> "the provider timed out (http 408)" + tail(trimmed)
                413 -> "the request was too large (http 413)" + tail(trimmed)
                429 -> "rate limited or out of quota (http 429)" + tail(trimmed)
                in 500..599 -> "the provider is having trouble (http $status)" + tail(trimmed)
                else -> "the provider refused the request (http $status)" + tail(trimmed)
            }
            return ProviderError(message, retryable = status == 429 || status == 408 || status >= 500)
        }

        private fun tail(detail: String?): String = if (detail == null) "" else ": $detail"

        /** json payload -> typed tree, with the dialect's own reader and the same lenient parser. */
        fun tree(text: String): Json = JsonPayload.readOrEmpty(text)

        /**
         * The `data[].id` shape shared by the openai and anthropic listings, sorted and de-duplicated
         * so the picker is stable between two reads of the same endpoint.
         */
        fun idsFromDataArray(body: String): List<String> {
            val root = tree(body)
            if (root.obj("error") != null) return emptyList()
            return root.list("data")
                .mapNotNull { entry -> (entry as? Json.Obj)?.string("id") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        }
    }
}

/**
 * Accumulates one streamed answer.
 *
 * Streaming exists so the user watches words arrive instead of a frozen screen, but the *result*
 * has to be identical to the non streaming call: same text, same tool calls. So each dialect feeds
 * raw `data:` payloads in and, at the end, [finish] hands back one ordinary [LlmResponse].
 */
interface StreamReader {

    /** One `data:` payload. Returns a chunk when it carried visible text, otherwise null. */
    fun feed(data: String): LlmChunk?

    fun finish(): LlmResponse
}

/** Text, usage and stop reason, shared by all three accumulators. */
internal abstract class BaseStreamReader(protected val model: String) : StreamReader {

    protected val text = StringBuilder()
    protected var stopReason = dev.savant.ai.StopReason.STOP
    protected var inputTokens: Int? = null
    protected var outputTokens: Int? = null

    protected fun delta(piece: String): LlmChunk? {
        if (piece.isEmpty()) return null
        text.append(piece)
        return LlmChunk(textDelta = piece)
    }

    override fun finish(): LlmResponse = LlmResponse(
        text = text.toString().takeIf { it.isNotEmpty() },
        toolCalls = emptyList(),
        stopReason = stopReason,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        model = model,
    )
}

/**
 * Tool arguments arrive as strings from the model — every call is re-typed against the tool schema
 * before it becomes json again, because `"10"` and `10` mean different things to a validator.
 */
internal fun toolArguments(spec: ToolSpec?, arguments: Map<String, String>): Json.Obj {
    val declared = spec?.parameters.orEmpty().associateBy { it.name }
    return obj(
        arguments.map { (name, raw) ->
            val parameter = declared[name]
            name to typedArgument(raw, parameter?.type, parameter?.options.orEmpty())
        },
    )
}

private fun typedArgument(raw: String, type: dev.savant.ai.ParamType?, options: List<String>): Json = when {
    type == dev.savant.ai.ParamType.INTEGER || type == dev.savant.ai.ParamType.NUMBER -> {
        val parsed = FieldValue.parse(raw).let { value ->
            when (value) {
                is FieldValue.Int64 -> num(value.value)
                is FieldValue.Real -> num(value.value)
                else -> null
            }
        }
        parsed ?: str(raw)
    }
    type == dev.savant.ai.ParamType.BOOLEAN -> {
        val value = raw.trim().lowercase()
        if (value == "true" || value == "false") bool(value == "true") else str(raw)
    }
    // An enum option list is a hint for the model, not for the transport: it stays a string.
    type != null && options.isNotEmpty() -> str(raw)
    else -> str(raw)
}

/** `{"type":"object","properties":{…},"required":[…]}` — openai and anthropic both want this. */
internal fun jsonSchema(parameters: List<ToolParameter>): Json.Obj {
    if (parameters.isEmpty()) return obj("type" to str("object"), "properties" to obj())
    val properties = parameters.map { parameter ->
        parameter.name to obj(
            "type" to str(parameter.type.json),
            "description" to str(
                if (parameter.options.isEmpty()) parameter.description
                else parameter.description + " (one of: ${parameter.options.joinToString(", ")})",
            ),
        )
    }
    return obj(
        "type" to str("object"),
        "properties" to obj(properties),
        "required" to arr(parameters.filter { it.required }.map { str(it.name) }),
    )
}

/** Gemini's schema dialect: same shape, upper case scalar names, required only when non empty. */
internal fun geminiSchema(parameters: List<ToolParameter>): Json.Obj {
    if (parameters.isEmpty()) return obj("type" to str("OBJECT"), "properties" to obj())
    val properties = parameters.map { parameter ->
        parameter.name to obj(
            "type" to str(geminiType(parameter.type)),
            "description" to str(
                if (parameter.options.isEmpty()) parameter.description
                else parameter.description + " (one of: ${parameter.options.joinToString(", ")})",
            ),
        )
    }
    val required = parameters.filter { it.required }.map { str(it.name) }
    return if (required.isEmpty()) {
        obj("type" to str("OBJECT"), "properties" to obj(properties))
    } else {
        obj("type" to str("OBJECT"), "properties" to obj(properties), "required" to arr(required))
    }
}

private fun geminiType(type: dev.savant.ai.ParamType): String = when (type) {
    dev.savant.ai.ParamType.STRING -> "STRING"
    dev.savant.ai.ParamType.NUMBER -> "NUMBER"
    dev.savant.ai.ParamType.INTEGER -> "INTEGER"
    dev.savant.ai.ParamType.BOOLEAN -> "BOOLEAN"
    dev.savant.ai.ParamType.ARRAY -> "ARRAY"
}

/** Tool calls come back as json objects from two dialects and as a json *string* from openai. */
internal fun toolCallFrom(name: String, id: String?, input: Json): ToolCall = ToolCall(
    id = id ?: "call_${name}_${input.hashCode().toUInt()}",
    name = name,
    arguments = flatten(input),
)

/** One level deep is all a tool schema here has ever needed: scalars and short arrays. */
private fun flatten(value: Json): Map<String, String> = when (value) {
    is Json.Obj -> value.members.mapNotNull { (key, child) ->
        val text = scalar(child) ?: return@mapNotNull null
        key to text
    }.toMap()
    else -> emptyMap()
}

private fun scalar(value: Json): String? = when (value) {
    Json.Null -> null
    is Json.Str -> value.value
    is Json.Num -> value.raw
    is Json.Bool -> value.value.toString()
    is Json.Arr -> value.items.mapNotNull { scalar(it) }.joinToString(", ").takeIf { it.isNotEmpty() }
    is Json.Obj -> JsonPayload.write(value)
}
