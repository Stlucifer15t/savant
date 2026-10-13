package dev.savant.ai.provider

import dev.savant.ai.LlmMessage
import dev.savant.ai.LlmRequest
import dev.savant.ai.LlmResponse
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderProfile
import dev.savant.ai.StopReason
import dev.savant.ai.ToolCall
import dev.savant.ai.ToolSpec
import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import dev.savant.parsers.json.arr
import dev.savant.parsers.json.bool
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/**
 * OpenAI chat completions, which is also the dialect openrouter, llama.cpp, ollama's openai
 * endpoint, vllm, lm studio and most self hosted gateways speak. One implementation covers all of
 * them; only the base url and the key change.
 */
object OpenAiProtocol : ProviderProtocol {

    override val kind: ProviderKind get() = ProviderKind.OPENAI

    override fun endpoint(profile: ProviderProfile): String =
        ProviderProtocol.joinUrl(profile.effectiveBaseUrl, "chat/completions")

    override fun headers(profile: ProviderProfile, apiKey: String?): List<Pair<String, String>> = buildList {
        add("content-type" to "application/json")
        if (!apiKey.isNullOrBlank()) add("authorization" to "Bearer $apiKey")
        if (profile.provider == ProviderKind.OPENROUTER) {
            // Openrouter asks for these to attribute traffic; they carry no save data.
            add("http-referer" to "https://github.com/Stlucifer15t/savant")
            add("x-title" to "savant")
        }
    }

    override fun request(request: LlmRequest, profile: ProviderProfile, apiKey: String?): HttpCall {
        val messages = mutableListOf<Json>()
        val system = request.systemPrompt ?: profile.systemPromptOverride
        if (!system.isNullOrBlank()) messages += obj("role" to str("system"), "content" to str(system))
        messages += request.messages.map { messageJson(it) }

        val body = obj(
            "model" to str(profile.model),
            "messages" to arr(messages),
            "temperature" to num(request.temperature.toDouble()),
            "max_tokens" to num(request.maxOutputTokens),
            "stream" to bool(false),
        ).let { base ->
            if (request.tools.isEmpty()) base
            else base.with("tools", arr(request.tools.map { toolJson(it) }))
        }

        return HttpCall(
            method = "POST",
            url = endpoint(profile),
            headers = headers(profile, apiKey),
            body = JsonPayload.write(body),
        )
    }

    override fun response(result: HttpResult): LlmResponse {
        if (!result.ok) return LlmResponse(text = ProviderProtocol.errorText(result), stopReason = StopReason.ERROR)
        val json = JsonPayload.readOrEmpty(result.body)
        val choice = json.list("choices").firstOrNull() as? Json.Obj
        val message = choice?.obj("message")
        val calls = message?.list("tool_calls")?.mapNotNull { call -> toolCall(call) } ?: emptyList()
        val usage = json.obj("usage")
        return LlmResponse(
            text = message?.string("content")?.takeIf { it.isNotBlank() },
            toolCalls = calls,
            stopReason = ProviderProtocol.stopReason(choice?.string("finish_reason"), calls.isNotEmpty()),
            inputTokens = usage?.int("prompt_tokens"),
            outputTokens = usage?.int("completion_tokens"),
            model = json.string("model"),
        )
    }

    private fun toolCall(json: Json): ToolCall? {
        val call = json as? Json.Obj ?: return null
        val function = call.obj("function") ?: return null
        val name = function.string("name") ?: return null
        return ToolCall(
            id = call.string("id") ?: name,
            name = name,
            arguments = ProviderProtocol.flatten(JsonPayload.readOrEmpty(function.string("arguments"))),
        )
    }

    private fun toolJson(spec: ToolSpec): Json.Obj = obj(
        "type" to str("function"),
        "function" to obj(
            "name" to str(spec.name),
            "description" to str(spec.description),
            "parameters" to ProviderProtocol.schemaJson(spec.parameters),
        ),
    )

    private fun messageJson(message: LlmMessage): Json.Obj = when (message.role) {
        LlmMessage.Role.SYSTEM -> obj("role" to str("system"), "content" to str(message.text))
        LlmMessage.Role.USER -> obj("role" to str("user"), "content" to str(message.text))
        LlmMessage.Role.ASSISTANT -> {
            val base = obj("role" to str("assistant"), "content" to str(message.text))
            if (message.toolCalls.isEmpty()) base else base.with(
                "tool_calls",
                arr(
                    message.toolCalls.map { call ->
                        obj(
                            "id" to str(call.id),
                            "type" to str("function"),
                            "function" to obj(
                                "name" to str(call.name),
                                "arguments" to str(ProviderProtocol.argumentsJson(call.arguments)),
                            ),
                        )
                    },
                ),
            )
        }

        LlmMessage.Role.TOOL -> obj(
            "role" to str("tool"),
            "tool_call_id" to str(message.toolCallId ?: ""),
            "content" to str(message.text),
        )
    }
}
