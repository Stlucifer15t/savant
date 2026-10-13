package dev.savant.ai.providers

import dev.savant.ai.AiPayload
import dev.savant.ai.LlmChunk
import dev.savant.ai.LlmClient
import dev.savant.ai.LlmEvent
import dev.savant.ai.LlmRequest
import dev.savant.ai.LlmResponse
import dev.savant.ai.NetworkLog
import dev.savant.ai.NetworkRecord
import dev.savant.ai.ProviderKind
import dev.savant.ai.ProviderPing
import dev.savant.ai.ProviderProfile
import dev.savant.ai.ProviderProfiles
import dev.savant.ai.net.HttpRequest
import dev.savant.ai.net.HttpTransport
import dev.savant.ai.net.TransportException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One profile, one dialect, one transport. */
class HttpLlmClient(
    val profile: ProviderProfile,
    private val transport: HttpTransport,
    private val apiKey: () -> String? = { null },
    private val protocol: ProviderProtocol = ProviderProtocol.of(profile.provider),
    private val log: NetworkLog = NetworkLog(enabled = { false }),
) : LlmClient {

    override val providerId: String = profile.provider.id

    override val label: String = displayLabel(profile)

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val key = requireKey()
        val body = protocol.body(profile, request, streaming = false)
        val started = System.currentTimeMillis()
        val response = try {
            transport.send(
                HttpRequest(
                    url = protocol.endpoint(profile, streaming = false),
                    headers = protocol.headers(profile, key),
                    body = body,
                ),
            )
        } catch (failure: TransportException) {
            record(request, body, status = failure.status, streaming = false, detail = failure.message)
            throw failure
        }
        if (!response.ok) {
            val error = protocol.error(response.status, response.body)
            record(request, body, response.status, streaming = false, detail = error.message)
            throw TransportException(error.message, response.status, error.retryable)
        }
        val parsed = protocol.parse(response.body, profile)
        record(
            request = request,
            body = body,
            status = response.status,
            streaming = false,
            detail = null,
            response = parsed,
            startedAtMillis = started,
        )
        return parsed
    }

    override fun events(request: LlmRequest): Flow<LlmEvent> = flow {
        val key = requireKey()
        val streaming = profile.streaming
        val body = protocol.body(profile, request, streaming = streaming)
        if (!streaming) {
            // A profile with streaming off still emits one delta, so the caller has one code path.
            val response = complete(request)
            response.text?.takeIf { it.isNotEmpty() }?.let { emit(LlmEvent.Delta(LlmChunk(it, done = true))) }
            emit(LlmEvent.Completed(response))
            return@flow
        }
        val reader = protocol.streamReader(profile)
        val started = System.currentTimeMillis()
        var status: Int? = null
        try {
            transport.stream(
                HttpRequest(
                    url = protocol.endpoint(profile, streaming = true),
                    headers = protocol.headers(profile, key),
                    body = body,
                    accept = "text/event-stream",
                ),
            ).collect { data ->
                reader.feed(data)?.let { chunk -> emit(LlmEvent.Delta(chunk)) }
            }
            status = 200
        } catch (failure: TransportException) {
            status = failure.status
            record(request, body, failure.status, streaming = true, detail = failure.message)
            throw failure
        }
        val response = reader.finish()
        record(request, body, status, streaming = true, detail = null, response = response, startedAtMillis = started)
        emit(LlmEvent.Completed(response))
    }

    override suspend fun stream(request: LlmRequest): Flow<LlmChunk> = events(request)
        .let { upstream -> flow { upstream.collect { if (it is LlmEvent.Delta) emit(it.chunk) } } }

    /**
     * One token in, one token out — the connectivity check.
     *
     * It deliberately carries no save content, no field names and no values: pressing "test
     * connection" must never be the moment a file leaves the device.
     */
    override suspend fun ping(): ProviderPing {
        val started = System.currentTimeMillis()
        val endpoint = protocol.endpoint(profile, streaming = false)
        return try {
            val response = complete(
                LlmRequest(
                    messages = listOf<dev.savant.ai.LlmMessage>(
                        dev.savant.ai.LlmMessage(
                            role = dev.savant.ai.LlmMessage.Role.USER,
                            text = "reply with the single word: ok",
                        ),
                    ),
                    tools = emptyList(),
                    systemPrompt = "you are a connectivity check. answer with one word.",
                    temperature = 0f,
                    maxOutputTokens = 16,
                ),
            )
            ProviderPing(
                ok = true,
                detail = response.text?.trim()?.takeIf { it.isNotEmpty() } ?: "connected, empty answer",
                latencyMillis = System.currentTimeMillis() - started,
                model = response.model ?: profile.model,
                endpoint = ProviderProtocol.endpointLabel(endpoint),
            )
        } catch (failure: TransportException) {
            ProviderPing(
                ok = false,
                detail = AiPayload.redactText(failure.message ?: "the request failed"),
                latencyMillis = System.currentTimeMillis() - started,
                model = profile.model,
                endpoint = ProviderProtocol.endpointLabel(endpoint),
            )
        }
    }

    /**
     * A self hosted endpoint (ollama, llama.cpp, a proxy) needs no key; every hosted provider does,
     * and saying so before the request is better than a 401 the user has to interpret.
     */
    private fun requireKey(): String? {
        val key = apiKey()?.trim()?.takeIf { it.isNotEmpty() }
        if (key == null && ProviderProfiles.needsKey(profile)) {
            throw TransportException(
                "no api key for ${profile.provider.label} — add one in settings → ai provider",
                retryable = false,
            )
        }
        return key
    }

    private fun record(
        request: LlmRequest,
        body: String,
        status: Int?,
        streaming: Boolean,
        detail: String?,
        response: LlmResponse? = null,
        startedAtMillis: Long = System.currentTimeMillis(),
    ) {
        log.record(
            NetworkRecord(
                atMillis = startedAtMillis,
                provider = profile.provider.label,
                endpoint = ProviderProtocol.endpointLabel(protocol.endpoint(profile, streaming)),
                status = status,
                bytesSent = body.toByteArray(Charsets.UTF_8).size,
                fieldsSent = request.payloadNote.fields,
                valuesSent = request.payloadNote.values,
                streaming = streaming,
                inputTokens = response?.inputTokens,
                outputTokens = response?.outputTokens,
                keyMask = AiPayload.redactKey(apiKey()),
                detail = detail?.let { AiPayload.redactText(it) },
            ),
        )
    }

    companion object {
        fun displayLabel(profile: ProviderProfile): String =
            if (profile.model.isBlank()) profile.provider.label else "${profile.provider.label} · ${profile.model}"
    }
}
