package dev.savant.ai.net

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * One http call, provider agnostic.
 *
 * There is no okhttp, retrofit or ktor here on purpose: savant ships four provider dialects, and
 * `java.net.HttpURLConnection` is on every android version savant runs on. Fewer jars also means
 * fewer things that can silently change how a save file is handled — the only thing that ever
 * leaves the device through this interface is a field summary and a conversation.
 */
data class HttpRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val accept: String = "application/json",
    /** Read timeout only; connecting is always capped at [CONNECT_TIMEOUT_MILLIS]. */
    val timeoutMillis: Int = HttpTransport.DEFAULT_TIMEOUT_MILLIS,
)

data class HttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val ok: Boolean get() = status in 200..299
}

/**
 * Anything that stopped a request from producing a response: no route, tls failure, dns, timeout,
 * a body that is not what the provider documented. The provider's own error *messages* are not
 * thrown here — they come back as a response with a status code, so the caller can show them
 * verbatim.
 */
class TransportException(
    message: String,
    val status: Int? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The seam every provider client talks to.
 *
 * Tests implement it with a scripted response list; the app uses [UrlConnectionTransport]. Nothing
 * above this interface knows whether a request went over the network, which is what lets the tool
 * loop, the privacy rules and the error copy be unit tested with no server and no key.
 */
interface HttpTransport {

    suspend fun send(request: HttpRequest): HttpResponse

    /**
     * Server-sent events, one emission per `data:` payload, in arrival order.
     *
     * The transport does not interpret the payloads — a provider's stream parser does that. Multi
     * line `data:` fields within one event are joined with newlines, as the spec requires, and
     * comment lines (`: ping`) are dropped, which is how providers keep a stream warm.
     */
    fun stream(request: HttpRequest): Flow<String>

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_TIMEOUT_MILLIS = 180_000

        /** A response body is not allowed to grow past this; a runaway stream cannot eat the heap. */
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
    }
}

/**
 * `HttpURLConnection`, wrapped.
 *
 * Two details that matter for providers: a non-2xx body is read from the error stream (all three
 * of them put the actual reason there), and gzip is asked for and decoded because provider
 * payloads with a field listing are big.
 */
class UrlConnectionTransport(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HttpTransport {

    override suspend fun send(request: HttpRequest): HttpResponse = withContext(dispatcher) {
        val connection = open(request)
        try {
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            HttpResponse(
                status = status,
                body = stream?.let { readText(it, connection.contentEncoding) }.orEmpty(),
                headers = connection.headerFields
                    .filterKeys { it != null }
                    .mapValues { (_, values) -> values.firstOrNull().orEmpty() },
            )
        } catch (io: IOException) {
            throw TransportException(
                message = io.message ?: "the request did not complete",
                retryable = true,
                cause = io,
            )
        } finally {
            connection.disconnect()
        }
    }

    override fun stream(request: HttpRequest): Flow<String> = flow {
        val connection = open(request)
        try {
            val status = connection.responseCode
            if (status >= 400) {
                val detail = connection.errorStream?.let { readText(it, connection.contentEncoding) }.orEmpty()
                throw TransportException(
                    message = detail.ifBlank { "stream refused with status $status" },
                    status = status,
                    retryable = status == 429 || status >= 500,
                )
            }
            val raw = connection.inputStream
            val stream = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val data = StringBuilder()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) {
                                emit(data.toString())
                                data.setLength(0)
                            }
                        }
                        line.startsWith(":") -> Unit
                        line.startsWith("data:") -> {
                            val payload = line.removePrefix("data:").removePrefix(" ")
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(payload)
                        }
                        // `event:`, `id:` and `retry:` are not needed: every provider savant talks to
                        // carries its own type inside the json payload.
                        else -> Unit
                    }
                }
                if (data.isNotEmpty()) emit(data.toString())
            }
        } catch (io: IOException) {
            throw TransportException(io.message ?: "the stream stopped", retryable = true, cause = io)
        } finally {
            connection.disconnect()
        }
    }.flowOn(dispatcher)

    private fun open(request: HttpRequest): HttpURLConnection {
        val url = try {
            URL(request.url)
        } catch (bad: Exception) {
            throw TransportException("that is not a usable url: ${request.url}", retryable = false, cause = bad)
        }
        val connection = try {
            url.openConnection() as HttpURLConnection
        } catch (io: IOException) {
            throw TransportException(io.message ?: "could not open ${url.host}", retryable = true, cause = io)
        }
        connection.requestMethod = request.method
        connection.connectTimeout = HttpTransport.CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = request.timeoutMillis
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", request.accept)
        connection.setRequestProperty("Accept-Encoding", "gzip")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return connection
    }

    private fun readText(stream: InputStream, encoding: String?): String {
        val decoded = if (encoding.equals("gzip", true)) GZIPInputStream(stream) else stream
        val bytes = decoded.use { it.readAtMost(HttpTransport.MAX_BODY_BYTES) }
        return String(bytes, Charsets.UTF_8)
    }

    /** Reads everything, but never more than [limit] bytes: a provider cannot be trusted with the heap. */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            if (buffer.size() + read > limit) {
                buffer.write(chunk, 0, limit - buffer.size())
                break
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private val HttpURLConnection.contentEncoding: String? get() = getHeaderField("Content-Encoding")

    companion object {
        /** Providers do not need to know more than this, and it keeps the log honest. */
        const val USER_AGENT = "savant/0.1 (android; save file workbench)"
    }
}
