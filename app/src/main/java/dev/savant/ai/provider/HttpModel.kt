package dev.savant.ai.provider

import dev.savant.ai.AiPayload

/**
 * One http request, described as data.
 *
 * The protocols build these and a transport executes them, which keeps the request shape testable
 * without a socket: a test asserts on the url, the headers and the body text, and no provider ever
 * has to be reachable for that.
 */
data class HttpCall(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: String = "",
    /**
     * Stop reading the response after this many bytes; negative means read all of it.
     *
     * Fetching a web page is the one call savant makes to a server it does not control, and a 200 mb
     * html document is not a thing to hold on a phone. The limit is enforced while reading, not after.
     */
    val maxResponseBytes: Long = -1L,
) {
    val hasBody: Boolean get() = body.isNotEmpty()

    /** The url without query parameters, which is the only part that is safe to log. */
    val loggedUrl: String get() = NetworkPolicy.redactUrl(url)
}

/** What came back. [error] is set for transport level failures, where there is no status at all. */
data class HttpResult(
    val status: Int,
    val body: String,
    val error: String? = null,
    val latencyMillis: Long = 0,
    /**
     * Response headers, lower cased keys.
     *
     * Only one thing reads them today: an mcp server hands back a session id and expects it on every
     * request after the handshake.
     */
    val headers: Map<String, String> = emptyMap(),
) {
    val ok: Boolean get() = status in 200..299
    val isTransportFailure: Boolean get() = status == 0

    /** The provider's own error text, with anything key shaped scrubbed out of it. */
    val message: String
        get() = AiPayload.redactText(
            error ?: body.lineSequence().firstOrNull { it.isNotBlank() }?.take(400) ?: "no response",
        )
}

/** Executes an [HttpCall]. One implementation on device, fakes in tests. */
interface HttpTransport {
    suspend fun execute(call: HttpCall, timeoutMillis: Long): HttpResult
}
