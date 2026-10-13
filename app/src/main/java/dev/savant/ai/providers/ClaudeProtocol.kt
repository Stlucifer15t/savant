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
import dev.savant.ai.ToolSpec
import dev.savant.ai.net.TransportException
import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import dev.savant.parsers.json.arr
import dev.savant.parsers.json.bool
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/**
 * The anthropic `messages` dialect.
 *
 * Three differences from openai that a naive adapter gets wrong: the system prompt is a top level
 * field rather than a message, tools carry an `input_schema` instead of a `function` wrapper, and a
 * tool result is a *user* turn containing `tool_result` blocks — so two results from one assistant
 * turn have to be merged into a single turn or the conversation is rejected.
 */
object ClaudeProtocol : ProviderProtocol {

    /** Pinned: this api changes shape between versions and savant only speaks this one. */
    private const val API_VERSION = "2023-06-01"

    override val kind: ProviderKind = ProviderKind.CLAUDE

    override fun endpoint(profile: ProviderProfile, streaming: Boolean): String {
        val base = profile.baseUrl.ifBlank { profile.provider.defaultBaseUrl }
        return ProviderProtocol.join(base, "messages")
    }

    override fun headers(profile: ProviderProfile, apiKey: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("anthropic-version", API_VERSION)
        if (!apiKey.isNullOrBlank()) put("x-api-key", apiKey)
    }

    override fun body(profile: ProviderProfile, request: LlmRequest, streaming: Boolean): String {
        val turns = mutableListOf<Json>()
        for (message in request.messages) {
            when (message.role) {
                // Carried in `system` below, never as a turn.
                LlmMessage.Role.SYSTEM -> Unit

                LlmMessage.Role.USER -> turns += obj("role" to str("user"), "content" to str(message.text))

                LlmMessage.Role.ASSISTANT -> turns += assistantTurn(message)

                LlmMessage.Role.TOOL -> {
                    val result = toolResultBlock(message)
                    val previous = turns.lastOrNull()
                    if (isToolResultTurn(previous)) {
                        val merged = ((previous as Json.Obj).get("content") as Json.Arr).items + result
                        turns[turns.lastIndex] = obj("role" to str("user"), "content" to arr(merged))
                    } else {
                        turns += obj("role" to str("user"), "content" to arr(result))
                    }
                }
            }
        }
        val payload = buildList<Pair<String, Json>> {
            add("model" to str(profile.model))
            add("max_tokens" to num(request.maxOutputTokens))
            add("temperature" to num(request.temperature.toDouble()))
            val system = request.systemPrompt
                ?: request.messages.firstOrNull { it.role == LlmMessage.Role.SYSTEM }?.text
            if (!system.isNullOrBlank()) add("system" to str(system))
            add("messages" to arr(turns))
            if (request.tools.isNotEmpty()) {
                add("tools" to arr(request.tools.map { toolJson(it) }))
                add("tool_choice" to obj("type" to str("auto")))
            }
            if (streaming) add("stream" to bool(true))
        }
        return JsonPayload.write(obj(payload))
    }

    override fun parse(body: String, profile: ProviderProfile): LlmResponse {
        val root = ProviderProtocol.tree(body)
        root.obj("error")?.let { error ->
            throw TransportException(error.string("message") ?: "the provider returned an error", retryable = false)
        }
        val blocks = root.list("content")
        val text = blocks
            .mapNotNull { block ->
                val blockObj = block as? Json.Obj ?: return@mapNotNull null
                if (blockObj.string("type") != "text") null else blockObj.string("text")
            }
            .joinToString("")
            .takeIf { it.isNotEmpty() }
        val calls = blocks.mapNotNull { block ->
            val blockObj = block as? Json.Obj ?: return@mapNotNull null
            if (blockObj.string("type") != "tool_use") return@mapNotNull null
            val name = blockObj.string("name") ?: return@mapNotNull null
            ToolCall(
                id = blockObj.string("id") ?: "tool_use_$name",
                name = name,
                arguments = scalarMap(blockObj.get("input")),
            )
        }
        val usage = root.obj("usage")
        return LlmResponse(
            text = text,
            toolCalls = calls,
            stopReason = stopReason(root.string("stop_reason")),
            inputTokens = usage?.int("input_tokens"),
            outputTokens = usage?.int("output_tokens"),
            model = root.string("model") ?: profile.model,
        )
    }

    override fun error(status: Int, body: String): ProviderError {
        val root = ProviderProtocol.tree(body)
        val detail = root.obj("error")?.string("message")
            ?: root.string("message")
            ?: body.takeIf { it.isNotBlank() && it.length <= 300 }?.trim()
        return ProviderProtocol.describe(status, detail)
    }

    override fun modelsEndpoint(profile: ProviderProfile): String =
        ProviderProtocol.join(profile.baseUrl.ifBlank { profile.provider.defaultBaseUrl }, "models")

    override fun parseModels(body: String): List<String> = ProviderProtocol.idsFromDataArray(body)

    override fun streamReader(profile: ProviderProfile): StreamReader = Reader(profile.model)

    // ---- shared shapes ---------------------------------------------------------------

    private fun isToolResultTurn(turn: Json?): Boolean {
        val turnObj = turn as? Json.Obj ?: return false
        if (turnObj.string("role") != "user") return false
        val content = turnObj.get("content") as? Json.Arr ?: return false
        return content.items.isNotEmpty() &&
            content.items.all { (it as? Json.Obj)?.string("type") == "tool_result" }
    }

    private fun assistantTurn(message: LlmMessage): Json {
        val blocks = mutableListOf<Json>()
        if (message.text.isNotBlank()) blocks += obj("type" to str("text"), "text" to str(message.text))
        message.toolCalls.forEach { call ->
            blocks += obj(
                "type" to str("tool_use"),
                "id" to str(call.id),
                "name" to str(call.name),
                "input" to toolArguments(ToolCatalog.byName(call.name), call.arguments),
            )
        }
        // An assistant turn may not be empty: a provider that sent one gets a placeholder.
        if (blocks.isEmpty()) blocks += obj("type" to str("text"), "text" to str("(no content)"))
        return obj("role" to str("assistant"), "content" to arr(blocks))
    }

    private fun toolResultBlock(message: LlmMessage): Json = obj(
        "type" to str("tool_result"),
        "tool_use_id" to str(message.toolCallId ?: ""),
        "content" to str(message.text),
    )

    private fun toolJson(spec: ToolSpec): Json = obj(
        "name" to str(spec.name),
        "description" to str(spec.description),
        "input_schema" to jsonSchema(spec.parameters),
    )

    private fun scalarMap(value: Json?): Map<String, String> {
        val valueObj = value as? Json.Obj ?: return emptyMap()
        return valueObj.members.mapNotNull { (key, child) ->
            when (child) {
                Json.Null -> null
                is Json.Str -> key to child.value
                is Json.Num -> key to child.raw
                is Json.Bool -> key to child.value.toString()
                is Json.Arr -> key to child.items.joinToString(", ") { scalarText(it) }
                is Json.Obj -> key to JsonPayload.write(child)
            }
        }.toMap()
    }

    private fun scalarText(value: Json): String = when (value) {
        Json.Null -> ""
        is Json.Str -> value.value
        is Json.Num -> value.raw
        is Json.Bool -> value.value.toString()
        else -> JsonPayload.write(value)
    }

    private fun stopReason(reason: String?): StopReason = when (reason) {
        "tool_use" -> StopReason.TOOL_USE
        "max_tokens" -> StopReason.LENGTH
        "refusal" -> StopReason.REFUSAL
        else -> StopReason.STOP
    }

    /**
     * Anthropic names its own events and sends typed json rather than index keyed deltas:
     * `content_block_start` opens a tool call and `input_json_delta` feeds its arguments, while
     * visible text arrives as `text_delta` inside `content_block_delta`.
     */
    private class Reader(model: String) : BaseStreamReader(model) {

        private class PartialCall(val id: String?, val name: String)

        private val partial = linkedMapOf<Int, PartialCall>()
        private val arguments = linkedMapOf<Int, StringBuilder>()

        override fun feed(data: String): LlmChunk? {
            val payload = data.trim()
            if (payload.isEmpty()) return null
            val event = ProviderProtocol.tree(payload)
            return when (event.string("type")) {
                "error" -> throw TransportException(
                    event.obj("error")?.string("message") ?: "the stream returned an error",
                    retryable = false,
                )

                "message_start" -> {
                    event.obj("message")?.obj("usage")?.int("input_tokens")?.let { inputTokens = it }
                    null
                }

                "content_block_start" -> {
                    val block = event.obj("content_block")
                    if (block?.string("type") == "tool_use") {
                        val index = event.int("index") ?: partial.size
                        partial[index] = PartialCall(id = block.string("id"), name = block.string("name").orEmpty())
                        arguments[index] = StringBuilder()
                    }
                    null
                }

                "content_block_delta" -> {
                    val index = event.int("index") ?: 0
                    val deltaObj = event.obj("delta")
                    when (deltaObj?.string("type")) {
                        "text_delta" -> deltaObj.string("text")?.let { piece -> emitText(piece) }
                        "input_json_delta" -> {
                            arguments.getOrPut(index) { StringBuilder() }
                                .append(deltaObj.string("partial_json").orEmpty())
                            null
                        }
                        else -> null
                    }
                }

                "message_delta" -> {
                    event.obj("delta")?.string("stop_reason")?.let { stopReason = stopReasonOf(it) }
                    event.obj("usage")?.int("output_tokens")?.let { outputTokens = it }
                    null
                }

                else -> null
            }
        }

        override fun finish(): LlmResponse {
            val calls = partial.mapNotNull { (index, call) ->
                if (call.name.isBlank()) return@mapNotNull null
                val raw = arguments[index]?.toString().orEmpty().ifBlank { "{}" }
                val parsed = runCatching { JsonPayload.read(raw) }.getOrNull()
                ToolCall(
                    id = call.id ?: "tool_use_${call.name}_$index",
                    name = call.name,
                    arguments = scalarMap(parsed),
                )
            }
            return LlmResponse(
                text = text.toString().takeIf { it.isNotEmpty() },
                toolCalls = calls,
                stopReason = if (calls.isNotEmpty()) StopReason.TOOL_USE else stopReason,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                model = model,
            )
        }

        private fun emitText(piece: String): LlmChunk? = delta(piece)

        private fun stopReasonOf(value: String) = stopReason(value)
    }
}
