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
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/**
 * Google's `generateContent` dialect.
 *
 * The differences that matter: turns are `contents` with `user` / `model` roles made of typed
 * `parts`, a tool result is a `functionResponse` part inside a user turn, the tool list is wrapped
 * in `functionDeclarations`, and the key travels in the `x-goog-api-key` header rather than a query
 * parameter — so it never ends up in the request log or an error message.
 */
object GeminiProtocol : ProviderProtocol {

    override val kind: ProviderKind = ProviderKind.GEMINI

    override fun endpoint(profile: ProviderProfile, streaming: Boolean): String {
        val base = profile.baseUrl.ifBlank { profile.provider.defaultBaseUrl }
        val model = profile.model.ifBlank { "gemini-2.0-flash" }
        val method = if (streaming) "streamGenerateContent?alt=sse" else "generateContent"
        return ProviderProtocol.join(base, "models/$model:$method")
    }

    override fun headers(profile: ProviderProfile, apiKey: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        if (!apiKey.isNullOrBlank()) put("x-goog-api-key", apiKey)
    }

    override fun body(profile: ProviderProfile, request: LlmRequest, streaming: Boolean): String {
        val contents = mutableListOf<Json>()
        for (message in request.messages) {
            when (message.role) {
                LlmMessage.Role.SYSTEM -> Unit // carried in systemInstruction below
                LlmMessage.Role.USER -> contents += turn("user", listOf(part(text = message.text)))
                LlmMessage.Role.ASSISTANT -> contents += turn("model", assistantParts(message))
                LlmMessage.Role.TOOL -> {
                    val part = functionResponsePart(message)
                    val previous = contents.lastOrNull()
                    if (isFunctionResponseTurn(previous)) {
                        val merged = ((previous as Json.Obj).get("parts") as Json.Arr).items + part
                        contents[contents.lastIndex] = turn("user", merged)
                    } else {
                        contents += turn("user", listOf(part))
                    }
                }
            }
        }
        val payload = buildList<Pair<String, Json>> {
            val system = request.systemPrompt
                ?: request.messages.firstOrNull { it.role == LlmMessage.Role.SYSTEM }?.text
            if (!system.isNullOrBlank()) {
                add("systemInstruction" to obj("parts" to arr(part(text = system))))
            }
            add("contents" to arr(contents))
            if (request.tools.isNotEmpty()) {
                add("tools" to arr(obj("functionDeclarations" to arr(request.tools.map { toolJson(it) }))))
            }
            add(
                "generationConfig" to obj(
                    "temperature" to num(request.temperature.toDouble()),
                    "maxOutputTokens" to num(request.maxOutputTokens),
                    "candidateCount" to num(1),
                ),
            )
        }
        return JsonPayload.write(obj(payload))
    }

    override fun parse(body: String, profile: ProviderProfile): LlmResponse {
        val root = ProviderProtocol.tree(body)
        root.obj("error")?.let { error ->
            throw TransportException(error.string("message") ?: "the provider returned an error", retryable = false)
        }
        val candidate = root.list("candidates").firstOrNull() as? Json.Obj
            ?: throw TransportException("no candidates in the response", retryable = false)
        val parts = candidate.obj("content")?.list("parts").orEmpty()
        val text = parts.mapNotNull { (it as? Json.Obj)?.string("text") }.joinToString("")
            .takeIf { it.isNotEmpty() }
        val calls = parts.mapNotNull { part ->
            val partObj = part as? Json.Obj ?: return@mapNotNull null
            val call = partObj.obj("functionCall") ?: return@mapNotNull null
            val name = call.string("name") ?: return@mapNotNull null
            ToolCall(id = "call_$name", name = name, arguments = scalarMap(call.get("args")))
        }
        val usage = root.obj("usageMetadata")
        val blocked = candidate.string("finishReason") == "SAFETY" || candidate.string("finishReason") == "PROHIBITED_CONTENT"
        return LlmResponse(
            text = text,
            toolCalls = calls,
            stopReason = when {
                calls.isNotEmpty() -> StopReason.TOOL_USE
                blocked -> StopReason.REFUSAL
                candidate.string("finishReason") == "MAX_TOKENS" -> StopReason.LENGTH
                else -> StopReason.STOP
            },
            inputTokens = usage?.int("promptTokenCount"),
            outputTokens = usage?.int("candidatesTokenCount"),
            model = root.string("modelVersion") ?: profile.model,
        )
    }

    override fun error(status: Int, body: String): ProviderError {
        val root = ProviderProtocol.tree(body)
        val detail = root.obj("error")?.string("message")
            ?: body.takeIf { it.isNotBlank() && it.length <= 300 }?.trim()
        return ProviderProtocol.describe(status, detail)
    }

    override fun streamReader(profile: ProviderProfile): StreamReader = Reader(profile.model)

    // ---- shared shapes ---------------------------------------------------------------

    private fun turn(role: String, parts: List<Json>): Json = obj("role" to str(role), "parts" to arr(parts))

    private fun part(text: String): Json = obj("text" to str(text))

    private fun assistantParts(message: LlmMessage): List<Json> = buildList {
        if (message.text.isNotBlank()) add(part(message.text))
        message.toolCalls.forEach { call ->
            add(
                obj(
                    "functionCall" to obj(
                        "name" to str(call.name),
                        "args" to toolArguments(ToolCatalog.byName(call.name), call.arguments),
                    ),
                ),
            )
        }
        if (isEmpty()) add(part("(no content)"))
    }

    /**
     * Google's function responses are addressed by *name*, not by call id, which is why [LlmMessage]
     * carries `toolName` next to `toolCallId`.
     */
    private fun functionResponsePart(message: LlmMessage): Json = obj(
        "functionResponse" to obj(
            "name" to str(message.toolName ?: "tool"),
            "response" to obj("result" to str(message.text)),
        ),
    )

    private fun isFunctionResponseTurn(turn: Json?): Boolean {
        val turnObj = turn as? Json.Obj ?: return false
        val parts = turnObj.get("parts") as? Json.Arr ?: return false
        return parts.items.isNotEmpty() && parts.items.all { (it as? Json.Obj)?.get("functionResponse") != null }
    }

    private fun toolJson(spec: ToolSpec): Json = obj(
        "name" to str(spec.name),
        "description" to str(spec.description),
        "parameters" to geminiSchema(spec.parameters),
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

    /**
     * With `alt=sse` every chunk is shaped like a whole response, so the reader is the non streaming
     * parse plus a text delta: `functionCall` parts arrive complete, never split.
     */
    private class Reader(model: String) : BaseStreamReader(model) {

        private val calls = mutableListOf<ToolCall>()

        override fun feed(data: String): LlmChunk? {
            val payload = data.trim()
            if (payload.isEmpty()) return null
            val root = ProviderProtocol.tree(payload)
            root.obj("error")?.let { error ->
                throw TransportException(error.string("message") ?: "the stream returned an error", retryable = false)
            }
            root.obj("usageMetadata")?.let { usage ->
                usage.int("promptTokenCount")?.let { inputTokens = it }
                usage.int("candidatesTokenCount")?.let { outputTokens = it }
            }
            val candidate = root.list("candidates").firstOrNull() as? Json.Obj ?: return null
            candidate.string("finishReason")?.let { finish ->
                stopReason = when (finish) {
                    "MAX_TOKENS" -> StopReason.LENGTH
                    "SAFETY", "PROHIBITED_CONTENT" -> StopReason.REFUSAL
                    else -> StopReason.STOP
                }
            }
            val parts = candidate.obj("content")?.list("parts").orEmpty()
            val pieces = mutableListOf<String>()
            for (part in parts) {
                val partObj = part as? Json.Obj ?: continue
                partObj.string("text")?.takeIf { it.isNotEmpty() }?.let { piece -> pieces += piece }
                partObj.obj("functionCall")?.let { call ->
                    call.string("name")?.let { name ->
                        calls += ToolCall(id = "call_$name", name = name, arguments = scalarMap(call.get("args")))
                    }
                }
            }
            // The reader answers with one chunk per event, so several text parts become one delta.
            return if (pieces.isEmpty()) null else delta(pieces.joinToString(""))
        }

        override fun finish(): LlmResponse = LlmResponse(
            text = text.toString().takeIf { it.isNotEmpty() },
            toolCalls = calls,
            stopReason = if (calls.isNotEmpty()) StopReason.TOOL_USE else stopReason,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            model = model,
        )
    }
}
