package dev.savant.ai.provider

import dev.savant.ai.AiPayload
import dev.savant.ai.LlmRequest
import dev.savant.ai.LlmResponse
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderProfile
import dev.savant.ai.ProviderPing
import dev.savant.ai.StopReason
import dev.savant.ai.ToolParameter
import dev.savant.parsers.json.Json
import dev.savant.parsers.json.JsonPayload
import dev.savant.parsers.json.arr
import dev.savant.parsers.json.num
import dev.savant.parsers.json.obj
import dev.savant.parsers.json.str

/**
 * One provider's dialect: where to call, how to authenticate, how the request is shaped and how the
 * answer comes back.
 *
 * Everything above this is shared — the tool list, the trimming rule, the "propose, never write"
 * contract — so switching provider changes the wire format and nothing else. Implementations are
 * pure functions over data: no sockets, no android, and no key ever reaches a log.
 */
interface ProviderProtocol {

    val kind: ProviderKind

    /** The url this profile calls for a completion. */
    fun endpoint(profile: ProviderProfile): String

    /** Auth and content headers. [apiKey] may be null, which produces an unauthenticated call. */
    fun headers(profile: ProviderProfile, apiKey: String?): List<Pair<String, String>>

    fun request(request: LlmRequest, profile: ProviderProfile, apiKey: String?): HttpCall

    /** A connectivity check that carries no save data: the provider's model list. */
    fun pingRequest(profile: ProviderProfile, apiKey: String?): HttpCall = HttpCall(
        method = "GET",
        url = joinUrl(profile.effectiveBaseUrl, modelsPath),
        headers = headers(profile, apiKey),
    )

    fun response(result: HttpResult): LlmResponse

    fun ping(result: HttpResult, profile: ProviderProfile, apiKey: String?): ProviderPing {
        val detail = when {
            result.isTransportFailure -> result.message
            result.ok -> "reachable"
            result.status == 401 || result.status == 403 -> "the key was refused (${result.status})"
            result.status == 404 -> "no model list at that url — check the base url"
            result.status == 429 -> "rate limited (${result.status})"
            else -> "http ${result.status}: ${result.message}"
        }
        return ProviderPing(
            ok = result.ok,
            detail = detail,
            latencyMillis = result.latencyMillis,
            model = profile.model,
            endpoint = NetworkPolicy.redactUrl(endpoint(profile)),
        )
    }

    /** Where the model list lives, relative to the base url. */
    val modelsPath: String get() = "models"

    companion object {

        fun forKind(kind: ProviderKind): ProviderProtocol = when (kind) {
            ProviderKind.CLAUDE -> AnthropicProtocol
            ProviderKind.GEMINI -> GeminiProtocol
            ProviderKind.OPENAI, ProviderKind.OPENROUTER, ProviderKind.CUSTOM -> OpenAiProtocol
        }

        /** `https://api.example.com/v1/` + `chat/completions` without doubling the slash. */
        fun joinUrl(base: String, path: String): String {
            val left = base.trimEnd('/')
            val right = path.trimStart('/')
            return if (left.isEmpty()) right else "$left/$right"
        }

        /** The json schema a provider needs for one tool. */
        fun schemaJson(parameters: List<ToolParameter>): Json.Obj {
            val schema = obj(
                "type" to str("object"),
                "properties" to obj(parameters.map { it.name to parameterJson(it) }),
            )
            val required = parameters.filter { it.required }.map { it.name }
            return if (required.isEmpty()) schema else schema.with("required", arr(required.map { str(it) }))
        }

        private fun parameterJson(parameter: ToolParameter): Json.Obj {
            val base = obj(
                "type" to str(parameter.type.json),
                "description" to str(parameter.description),
            )
            return if (parameter.options.isEmpty()) base else base.with("enum", arr(parameter.options.map { str(it) }))
        }

        /** Tool arguments are flat string maps; this is the json a provider expects to see. */
        fun argumentsJson(arguments: Map<String, String>): String =
            JsonPayload.write(obj(arguments.map { (key, value) -> key to str(value) }))

        /** And this is the flat map savant works with, from whatever a provider returned. */
        fun flatten(json: Json): Map<String, String> = when (json) {
            is Json.Obj -> json.members.associate { (key, value) ->
                key to (value.asText ?: JsonPayload.write(value))
            }

            else -> emptyMap()
        }

        /** The provider's own error message, or the first line of the body if it did not send one. */
        fun errorText(result: HttpResult): String {
            if (result.error != null) return result.message
            val json = JsonPayload.readOrEmpty(result.body)
            val fromError = json.obj("error")?.string("message")
                ?: json.list("error").firstOrNull()?.let { (it as? Json.Obj)?.string("message") }
            return AiPayload.redactText(fromError ?: "http ${result.status}")
        }

        fun stopReason(raw: String?, hasToolCalls: Boolean): StopReason = when (raw?.lowercase()) {
            "tool_calls", "tool_use", "function_call" -> StopReason.TOOL_USE
            "length", "max_tokens" -> StopReason.LENGTH
            "content_filter", "safety", "refusal", "recitation" -> StopReason.REFUSAL
            "stop", "end_turn", "stop_sequence" -> StopReason.STOP
            null -> if (hasToolCalls) StopReason.TOOL_USE else StopReason.STOP
            else -> if (hasToolCalls) StopReason.TOOL_USE else StopReason.STOP
        }
    }
}
