package dev.savant.data.ai

import dev.savant.ai.provider.HttpCall
import dev.savant.ai.provider.HttpResult
import dev.savant.ai.provider.HttpTransport
import dev.savant.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [HttpTransport] over [HttpURLConnection].
 *
 * No http client dependency: savant makes a handful of json posts per turn, and the platform client
 * does that without adding a library to a build that has to stay reproducible. Streaming is not
 * implemented — a turn is one request and one response — which is also why the timeouts are generous
 * rather than tight.
 *
 * Every failure becomes an [HttpResult] with `status = 0` and the exception's own words in `error`,
 * so the chat can show "connect timed out" instead of a stack trace, and nothing here throws into a
 * view model.
 */
@Singleton
class UrlConnectionTransport @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : HttpTransport {

    override suspend fun execute(call: HttpCall, timeoutMillis: Long): HttpResult = withContext(io) {
        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        try {
            connection = (URI(call.url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = call.method
                connectTimeout = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                readTimeout = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                instanceFollowRedirects = false
                useCaches = false
                call.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            if (call.hasBody) {
                connection.doOutput = true
                connection.outputStream.use { out -> out.write(call.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            HttpResult(status = status, body = body, latencyMillis = System.currentTimeMillis() - started)
        } catch (error: Exception) {
            HttpResult(
                status = 0,
                body = "",
                error = "${error.javaClass.simpleName}: ${error.message ?: "no message"}",
                latencyMillis = System.currentTimeMillis() - started,
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}
