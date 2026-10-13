package dev.savant.ai

import dev.savant.ai.provider.HttpCall
import dev.savant.ai.provider.HttpResult
import dev.savant.ai.provider.HttpTransport
import dev.savant.ai.provider.NetworkEvent
import dev.savant.ai.provider.NetworkPolicy
import dev.savant.ai.provider.ProviderProtocol

/**
 * An [LlmClient] that talks to one configured profile.
 *
 * The client owns three rules and none of them is optional:
 * - generation settings come from the profile, not from the caller, so a slider in settings is the
 *   single place temperature and token limits are decided;
 * - a cleartext url is refused unless it points at the phone's own network;
 * - what gets logged is the shape of the call, never its contents, and never the key.
 *
 * It is constructed per call rather than injected as a singleton: the active profile, and the key
 * behind it, can change while the app is open, and a stale client would keep using the old one.
 */
class RemoteLlmClient(
    private val profile: ProviderProfile,
    private val apiKey: () -> String?,
    private val transport: HttpTransport,
    private val onEvent: (NetworkEvent) -> Unit = {},
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : LlmClient {

    private val protocol: ProviderProtocol = ProviderProtocol.forKind(profile.provider)

    override val providerId: String get() = profile.provider.id

    override val label: String get() = if (profile.name.isBlank()) profile.model else "${profile.name} · ${profile.model}"

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val call = protocol.request(withProfileSettings(request), profile, apiKey())
        val verdict = NetworkPolicy.assess(call.url)
        if (verdict is NetworkPolicy.Verdict.Refused) {
            return LlmResponse(text = verdict.reason, stopReason = StopReason.ERROR)
        }
        val result = transport.execute(call, timeoutMillis)
        val response = protocol.response(result)
        record(call, result, response, verdict == NetworkPolicy.Verdict.LocalCleartext)
        return response
    }

    override suspend fun ping(): ProviderPing {
        val call = protocol.pingRequest(profile, apiKey())
        val verdict = NetworkPolicy.assess(call.url)
        if (verdict is NetworkPolicy.Verdict.Refused) {
            return ProviderPing(
                ok = false,
                detail = verdict.reason,
                latencyMillis = 0,
                model = profile.model,
                endpoint = NetworkPolicy.redactUrl(call.url),
            )
        }
        val result = transport.execute(call, PING_TIMEOUT_MILLIS)
        record(call, result, null, verdict == NetworkPolicy.Verdict.LocalCleartext)
        return protocol.ping(result, profile, apiKey())
    }

    private fun withProfileSettings(request: LlmRequest): LlmRequest = request.copy(
        temperature = profile.temperature,
        maxOutputTokens = profile.maxOutputTokens,
    )

    /** The log entry. Built from the call and the status line, never from the bodies. */
    private fun record(call: HttpCall, result: HttpResult, response: LlmResponse?, cleartext: Boolean) {
        onEvent(
            NetworkEvent(
                atMillis = System.currentTimeMillis(),
                provider = profile.provider.id,
                model = response?.model ?: profile.model,
                method = call.method,
                url = call.loggedUrl,
                status = result.status,
                latencyMillis = result.latencyMillis,
                requestBytes = call.body.length,
                responseBytes = result.body.length,
                keyMask = NetworkPolicy.redactKey(apiKey()),
                cleartext = cleartext,
                error = if (result.ok) null else result.message.take(200),
                tokensIn = response?.inputTokens,
                tokensOut = response?.outputTokens,
            ),
        )
    }

    companion object {
        /** Long enough for a tool loop turn on a slow provider, short enough to not hang the chat. */
        const val DEFAULT_TIMEOUT_MILLIS = 45_000L
        const val PING_TIMEOUT_MILLIS = 15_000L
    }
}
