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
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/**
 * Gemini's generateContent.
 *
 * The differences that matter: the model goes in the url, roles are `user` and `model`, a system
 * prompt is an instruction rather than a message, tool calls are `functionCall` parts with no id,
 * and results come back as `functionResponse` parts matched by name. Because there is no id, savant
 * synthesises one per call so the shared [dev.savant.ai.ToolCall] shape still works.
 */
object GeminiProtocol : ProviderProtocol {

    override val kind: ProviderKind get() = ProviderKind.GEMINI

    override fun endpoint(profile: ProviderProfile): String =
        ProviderProtocol.joinUrl(profile.effectiveBaseUrl, "models/${profile.model}:generateContent")

    override fun headers(profile: ProviderProfile, apiKey: String?): List<Pair<String, String>> = buildList {
        add("content-type" to "application/json")
        // The key can also travel as ?key=, which would end up in a url. It does not.
        if (!apiKey.isNullOrBlank()) add("x-goog-api-key" to apiKey)
    }

    override fun request(request: LlmRequest, profile: ProviderProfile, apiKey: String?): HttpCall {
        val body = obj(
            "contents" to arr(request.messages.map { contentJson(it) }),
            "generationConfig" to obj(
                "temperature" to num(request.temperature.toDouble()),
                "maxOutputTokens" to num(request.maxOutputTokens),
            ),
        ).let { base ->
            val system = request.systemPrompt ?: profile.systemPromptOverride
            val withSystem = if (system.isNullOrBlank()) base
            else base.with("systemInstruction", obj("parts" to arr(obj("text" to str(system)))))
            if (request.tools.isEmpty()) withSystem
            else withSystem.with(
                "tools",
                arr(obj("functionDeclarations" to arr(request.tools.map { declarationJson(it) }))),
            )
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
        val candidate = json.list("candidates").firstOrNull() as? Json.Obj
        val parts = candidate?.obj("content")?.list("parts")?.mapNotNull { it as? Json.Obj } ?: emptyList()
        val text = parts.mapNotNull { it.string("text") }.joinToString("\n").ifBlank { null }
        val calls = parts.mapIndexedNotNull { index, part ->
            val call = part.obj("functionCall") ?: return@mapIndexedNotNull null
            val name = call.string("name") ?: return@mapIndexedNotNull null
            ToolCall(
                id = "gemini_${index}_$name",
                name = name,
                arguments = ProviderProtocol.flatten(call.obj("args") ?: Json.Null),
            )
        }
        val usage = json.obj("usageMetadata")
        return LlmResponse(
            text = text,
            toolCalls = calls,
            stopReason = ProviderProtocol.stopReason(candidate?.string("finishReason"), calls.isNotEmpty()),
            inputTokens = usage?.int("promptTokenCount"),
            outputTokens = usage?.int("candidatesTokenCount"),
            model = json.string("modelVersion"),
        )
    }

    private fun contentJson(message: LlmMessage): Json.Obj = when (message.role) {
        // Gemini has no system role in `contents`; it is carried as the instruction instead.
        LlmMessage.Role.SYSTEM -> obj("role" to str("user"), "parts" to arr(obj("text" to str(message.text))))

        LlmMessage.Role.USER -> obj("role" to str("user"), "parts" to arr(obj("text" to str(message.text))))

        LlmMessage.Role.ASSISTANT -> {
            val parts = mutableListOf<Json>()
            if (message.text.isNotBlank()) parts += obj("text" to str(message.text))
            message.toolCalls.forEach { call ->
                parts += obj(
                    "functionCall" to obj(
                        "name" to str(call.name),
                        "args" to obj(call.arguments.map { (key, value) -> key to str(value) }),
                    ),
                )
            }
            if (parts.isEmpty()) parts += obj("text" to str(""))
            obj("role" to str("model"), "parts" to arr(parts))
        }

        LlmMessage.Role.TOOL -> obj(
            "role" to str("user"),
            "parts" to arr(
                obj(
                    "functionResponse" to obj(
                        "name" to str(message.toolName ?: ""),
                        "response" to obj("result" to str(message.text)),
                    ),
                ),
            ),
        )
    }

    private fun declarationJson(spec: ToolSpec): Json.Obj = obj(
        "name" to str(spec.name),
        "description" to str(spec.description),
        "parameters" to ProviderProtocol.schemaJson(spec.parameters),
    )
}
