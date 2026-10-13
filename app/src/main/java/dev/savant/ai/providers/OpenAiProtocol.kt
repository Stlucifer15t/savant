package dev.savant.ai.providers

import dev.savant.ai.LlmChunk
import dev.savant.ai.LlmMessage
import dev.savant.ai.LlmRequest
import dev.savant.ai.LlmResponse
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderProfile
import dev.savant.ai.StopReason
import dev.savant.ai.ToolCall
import dev.savant.ai.ToolCatalog
import dev.savant.ai.net.TransportException
import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import dev.savant.parsers.json.arr
import dev.savant.parsers.json.bool
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/**
 * The `chat completions` dialect: openai, openrouter, and anything self hosted that copied it.
 *
 * Tool calls arrive as a json *string* in `arguments`, which is why they are re-parsed before they
 * become [ToolCall]s — a provider is not allowed to hand savant a half typed value.
 */
object OpenAiProtocol : ProviderProtocol {

    override val kind: ProviderKind = ProviderKind.OPENAI

    override fun endpoint(profile: ProviderProfile, streaming: Boolean): String {
        val base = profile.baseUrl.ifBlank { profile.provider.defaultBaseUrl }
        return ProviderProtocol.join(base, "chat/completions")
    }

    override fun headers(profile: ProviderProfile, apiKey: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer $apiKey")
    }

    override fun body(profile: ProviderProfile, request: LlmRequest, streaming: Boolean): String {
        val turns = buildList {
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let { system ->
                add(LlmMessage(role = LlmMessage.Role.SYSTEM, text = system))
            }
            addAll(request.messages)
        }
        val payload = buildList<Pair<String, Json>> {
            add("model" to str(profile.model))
            add("messages" to arr(turns.map { message -> messageJson(message) }))
            if (request.tools.isNotEmpty()) {
                add("tools" to arr(request.tools.map { toolJson(it) }))
                add("tool_choice" to str("auto"))
            }
            add("temperature" to num(request.temperature.toDouble()))
            add(tokenLimitKey(profile.model) to num(request.maxOutputTokens))
            if (streaming) {
                add("stream" to bool(true))
                add("stream_options" to obj("include_usage" to bool(true)))
            }
        }
        return JsonPayload.write(obj(payload))
    }

    override fun parse(body: String, profile: ProviderProfile): LlmResponse {
        val root = ProviderProtocol.tree(body)
        val error = root.obj("error")
        if (error != null) {
            throw TransportException(error.string("message") ?: "the provider returned an error", retryable = false)
        }
        val choice = root.list("choices").firstOrNull() as? Json.Obj
            ?: throw TransportException("no choices in the response", retryable = false)
        val message = choice.obj("message") ?: Json.Obj()
        val usage = root.obj("usage")
        return LlmResponse(
            text = textOf(message.get("content")),
            toolCalls = toolCallsOf(message),
            stopReason = stopReason(choice.string("finish_reason")),
            inputTokens = usage?.int("prompt_tokens"),
            outputTokens = usage?.int("completion_tokens"),
            model = root.string("model") ?: profile.model,
        )
    }

    override fun error(status: Int, body: String): ProviderError {
        val root = ProviderProtocol.tree(body)
        val detail = root.obj("error")?.string("message")
            ?: root.obj("error")?.string("type")
            ?: body.takeIf { it.isNotBlank() && it.length <= 300 }?.trim()
        return ProviderProtocol.describe(status, detail)
    }

    override fun streamReader(profile: ProviderProfile): StreamReader = Reader(profile.model)

    override fun modelsEndpoint(profile: ProviderProfile): String =
        ProviderProtocol.join(profile.baseUrl.ifBlank { profile.provider.defaultBaseUrl }, "models")

    override fun parseModels(body: String): List<String> = ProviderProtocol.idsFromDataArray(body)

    // ---- request shape ---------------------------------------------------------------

    private fun messageJson(message: LlmMessage): Json = when (message.role) {
        LlmMessage.Role.SYSTEM -> obj("role" to str("system"), "content" to str(message.text))

        LlmMessage.Role.USER -> obj("role" to str("user"), "content" to str(message.text))

        LlmMessage.Role.ASSISTANT -> {
            val members = mutableListOf<Pair<String, Json>>("role" to str("assistant"))
            // A provider rejects an assistant turn with neither text nor calls: drop empty ones.
            members += "content" to str(message.text)
            if (message.toolCalls.isNotEmpty()) {
                members += "tool_calls" to arr(message.toolCalls.map { call ->
                    obj(
                        "id" to str(call.id),
                        "type" to str("function"),
                        "function" to obj(
                            "name" to str(call.name),
                            "arguments" to str(JsonPayload.write(toolArguments(ToolCatalog.byName(call.name), call.arguments))),
                        ),
                    )
                })
            }
            obj(members)
        }

        LlmMessage.Role.TOOL -> obj(
            "role" to str("tool"),
            "tool_call_id" to str(message.toolCallId ?: ""),
            "content" to str(message.text),
        )
    }

    private fun toolJson(spec: dev.savant.ai.ToolSpec): Json = obj(
        "type" to str("function"),
        "function" to obj(
            "name" to str(spec.name),
            "description" to str(spec.description),
            "parameters" to jsonSchema(spec.parameters),
        ),
    )

    /**
     * `max_tokens` is the field every openai compatible server understands; the reasoning models
     * that replaced it with `max_completion_tokens` reject it, so the name picks the key. Both mean
     * the same thing to savant: the ceiling for one answer.
     */
    private fun tokenLimitKey(model: String): String {
        val name = model.lowercase()
        val modern = name.startsWith("o1") || name.startsWith("o3") || name.startsWith("o4") ||
            name.startsWith("gpt-5") || name.startsWith("gpt-6")
        return if (modern) "max_completion_tokens" else "max_tokens"
    }

    // ---- response shape --------------------------------------------------------------

    private fun textOf(content: Json?): String? = when (content) {
        null, Json.Null -> null
        is Json.Str -> content.value.takeIf { it.isNotEmpty() }
        // Some compatible servers answer with content parts instead of one string.
        is Json.Arr -> content.items.mapNotNull { part ->
            (part as? Json.Obj)?.string("text") ?: (part as? Json.Str)?.value
        }.joinToString("").takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun toolCallsOf(message: Json.Obj): List<ToolCall> = message.list("tool_calls").mapNotNull { raw ->
        val call = raw as? Json.Obj ?: return@mapNotNull null
        val function = call.obj("function") ?: return@mapNotNull null
        val name = function.string("name") ?: return@mapNotNull null
        val arguments = function.string("arguments")
        ToolCall(
            id = call.string("id") ?: "call_$name",
            name = name,
            arguments = parseArguments(arguments),
        )
    }

    /** The arguments string is json when it is well formed, and a single value when some servers send one. */
    private fun parseArguments(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val parsed = runCatching { JsonPayload.read(raw) }.getOrNull()
        return when (parsed) {
            null -> emptyMap()
            is Json.Obj -> parsed.members.mapNotNull { (key, value) ->
                val text = when (value) {
                    Json.Null -> null
                    is Json.Str -> value.value
                    is Json.Num -> value.raw
                    is Json.Bool -> value.value.toString()
                    is Json.Arr -> value.items.joinToString(", ") { scalarText(it) }
                    is Json.Obj -> JsonPayload.write(value)
                }
                text?.let { key to it }
            }.toMap()
            else -> emptyMap()
        }
    }

    private fun scalarText(value: Json): String = when (value) {
        Json.Null -> ""
        is Json.Str -> value.value
        is Json.Num -> value.raw
        is Json.Bool -> value.value.toString()
        else -> JsonPayload.write(value)
    }

    private fun stopReason(reason: String?): StopReason = when (reason) {
        "tool_calls", "function_call" -> StopReason.TOOL_USE
        "length" -> StopReason.LENGTH
        "content_filter" -> StopReason.REFUSAL
        null -> StopReason.STOP
        else -> StopReason.STOP
    }

    /**
     * Deltas: text arrives whole, tool calls arrive in pieces keyed by index — the id and the name
     * on the first piece, then the arguments string one fragment at a time.
     */
    private class Reader(model: String) : BaseStreamReader(model) {

        private val partial = linkedMapOf<Int, PartialCall>()

        private class PartialCall(val index: Int, val id: String?, val name: String, val arguments: StringBuilder)

        override fun feed(data: String): LlmChunk? {
            val payload = data.trim()
            if (payload.isEmpty()) return null
            if (payload == "[DONE]") return null
            val root = ProviderProtocol.tree(payload)
            root.obj("error")?.let { error ->
                throw TransportException(error.string("message") ?: "the stream returned an error", retryable = false)
            }
            root.obj("usage")?.let { usage ->
                usage.int("prompt_tokens")?.let { inputTokens = it }
                usage.int("completion_tokens")?.let { outputTokens = it }
            }
            val choice = root.list("choices").firstOrNull() as? Json.Obj ?: return null
            choice.string("finish_reason")?.let { stopReason = stopReasonOf(it) }
            val delta = choice.obj("delta") ?: return null
            delta.list("tool_calls").forEach { raw ->
                val call = raw as? Json.Obj ?: return@forEach
                val index = call.int("index") ?: partial.size
                val function = call.obj("function")
                val existing = partial[index]
                if (existing == null) {
                    partial[index] = PartialCall(
                        index = index,
                        id = call.string("id"),
                        name = function?.string("name").orEmpty(),
                        arguments = StringBuilder(function?.string("arguments").orEmpty()),
                    )
                } else {
                    function?.string("arguments")?.let { existing.arguments.append(it) }
                }
            }
            return delta.string("content")?.let { delta(it) }
        }

        override fun finish(): LlmResponse {
            val calls = partial.values.mapNotNull { call ->
                if (call.name.isBlank()) return@mapNotNull null
                ToolCall(
                    id = call.id ?: "call_${call.name}_${call.index}",
                    name = call.name,
                    arguments = parseArguments(call.arguments.toString()),
                )
            }
            if (calls.isNotEmpty()) stopReason = StopReason.TOOL_USE
            return LlmResponse(
                text = text.toString().takeIf { it.isNotEmpty() },
                toolCalls = calls,
                stopReason = stopReason,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                model = model,
            )
        }

        private fun stopReasonOf(finish: String) = stopReason(finish)
    }
}
