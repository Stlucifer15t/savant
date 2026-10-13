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
 * Anthropic's messages api.
 *
 * Two shapes differ from the openai dialect and both are handled here rather than leaking upwards:
 * the system prompt is a top level string, and a tool result is a `tool_result` block inside a
 * *user* message, not a message with its own role.
 */
object AnthropicProtocol : ProviderProtocol {

    private const val API_VERSION = "2023-06-01"

    override val kind: ProviderKind get() = ProviderKind.CLAUDE

    override fun endpoint(profile: ProviderProfile): String =
        ProviderProtocol.joinUrl(profile.effectiveBaseUrl, "messages")

    override fun headers(profile: ProviderProfile, apiKey: String?): List<Pair<String, String>> = buildList {
        add("content-type" to "application/json")
        add("anthropic-version" to API_VERSION)
        if (!apiKey.isNullOrBlank()) add("x-api-key" to apiKey)
    }

    override fun request(request: LlmRequest, profile: ProviderProfile, apiKey: String?): HttpCall {
        val body = obj(
            "model" to str(profile.model),
            // max_tokens is required by this api, not optional.
            "max_tokens" to num(request.maxOutputTokens),
            "temperature" to num(request.temperature.toDouble()),
            "messages" to arr(mergeMessages(request.messages)),
        ).let { base ->
            val system = request.systemPrompt ?: profile.systemPromptOverride
            val withSystem = if (system.isNullOrBlank()) base else base.with("system", str(system))
            if (request.tools.isEmpty()) withSystem
            else withSystem.with("tools", arr(request.tools.map { toolJson(it) }))
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
        val blocks = json.list("content").mapNotNull { it as? Json.Obj }
        val text = blocks.filter { it.string("type") == "text" }.mapNotNull { it.string("text") }
            .joinToString("\n").ifBlank { null }
        val calls = blocks.filter { it.string("type") == "tool_use" }.mapNotNull { block ->
            val name = block.string("name") ?: return@mapNotNull null
            ToolCall(
                id = block.string("id") ?: name,
                name = name,
                arguments = ProviderProtocol.flatten(block.obj("input") ?: Json.Null),
            )
        }
        val usage = json.obj("usage")
        return LlmResponse(
            text = text,
            toolCalls = calls,
            stopReason = ProviderProtocol.stopReason(json.string("stop_reason"), calls.isNotEmpty()),
            inputTokens = usage?.int("input_tokens"),
            outputTokens = usage?.int("output_tokens"),
            model = json.string("model"),
        )
    }

    /**
     * Assistant text and its tool calls belong to one message here, and tool results belong to the
     * next user message, so consecutive savant messages are folded into anthropic turns.
     */
    private fun mergeMessages(messages: List<LlmMessage>): List<Json.Obj> {
        val turns = mutableListOf<Json.Obj>()
        for (message in messages) {
            when (message.role) {
                LlmMessage.Role.SYSTEM -> Unit // carried top level, not as a turn

                LlmMessage.Role.USER -> turns += obj(
                    "role" to str("user"),
                    "content" to arr(obj("type" to str("text"), "text" to str(message.text))),
                )

                LlmMessage.Role.ASSISTANT -> {
                    val blocks = mutableListOf<Json>()
                    if (message.text.isNotBlank()) {
                        blocks += obj("type" to str("text"), "text" to str(message.text))
                    }
                    message.toolCalls.forEach { call ->
                        blocks += obj(
                            "type" to str("tool_use"),
                            "id" to str(call.id),
                            "name" to str(call.name),
                            "input" to argumentsObject(call.arguments),
                        )
                    }
                    if (blocks.isEmpty()) blocks += obj("type" to str("text"), "text" to str(""))
                    turns += obj("role" to str("assistant"), "content" to arr(blocks))
                }

                LlmMessage.Role.TOOL -> turns += obj(
                    "role" to str("user"),
                    "content" to arr(
                        obj(
                            "type" to str("tool_result"),
                            "tool_use_id" to str(message.toolCallId ?: message.toolName ?: ""),
                            "content" to str(message.text),
                        ),
                    ),
                )
            }
        }
        return turns
    }

    /** Tool input is a json object, not a string, in this dialect. */
    private fun argumentsObject(arguments: Map<String, String>): Json =
        obj(arguments.map { (key, value) -> key to str(value) })

    private fun toolJson(spec: ToolSpec): Json.Obj = obj(
        "name" to str(spec.name),
        "description" to str(spec.description),
        "input_schema" to ProviderProtocol.schemaJson(spec.parameters),
    )
}
