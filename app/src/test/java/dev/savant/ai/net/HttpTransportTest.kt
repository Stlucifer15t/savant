package dev.savant.ai.net

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.GZIPOutputStream

/**
 * [UrlConnectionTransport] against a real socket.
 *
 * Loopback, no network, no provider: a `ServerSocket` that answers one request from a script. This
 * is the only place the http plumbing is exercised for real — the rest of the provider suite runs
 * against [TestTransport] — and it is where gzip, the error stream and server-sent events are
 * proven rather than assumed.
 */
class HttpTransportTest {

    /** Answers every connection with one prepared raw response, and records what it was asked. */
    private class Server(private val rawResponse: (String) -> ByteArray) : AutoCloseable {

        private val socket = ServerSocket(0)
        val received = mutableListOf<String>()

        val port: Int get() = socket.localPort

        private val worker = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { connection -> handle(connection) }
                } catch (closed: IOException) {
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        private fun handle(connection: Socket) {
            val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val name = line.substringBefore(':').trim().lowercase()
                headers[name] = line.substringAfter(':').trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = CharArray(length).also { buffer ->
                var read = 0
                while (read < length) {
                    val count = reader.read(buffer, read, length - read)
                    if (count < 0) break
                    read += count
                }
            }.concatToString()
            received += "$requestLine\n${headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }}\n$body"
            connection.getOutputStream().write(rawResponse(body))
            connection.getOutputStream().flush()
        }

        override fun close() {
            socket.close()
            worker.interrupt()
        }
    }

    private fun response(
        status: String = "200 OK",
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray {
        val head = buildString {
            append("HTTP/1.1 $status\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray()
        return head + body
    }

    private fun json(body: String) = response(body = body.toByteArray(), headers = mapOf("Content-Type" to "application/json"))

    @Test
    fun aPostCarriesItsBodyAndHeadersAndReadsTheAnswer() {
        Server { json("""{"ok":true}""") }.use { server ->
            val transport = UrlConnectionTransport()
            val answer = runBlocking {
                transport.send(
                    HttpRequest(
                        url = "http://127.0.0.1:${server.port}/v1/chat/completions",
                        headers = mapOf("Authorization" to "Bearer sk-test", "Content-Type" to "application/json"),
                        body = """{"model":"gpt-4o-mini"}""",
                    ),
                )
            }
            assertEquals(200, answer.status)
            assertEquals("""{"ok":true}""", answer.body)
            assertTrue(answer.ok)
            val seen = server.received.first()
            assertTrue(seen.startsWith("POST /v1/chat/completions"))
            assertTrue(seen.contains("bearer sk-test"))
            assertTrue(seen.contains("""{"model":"gpt-4o-mini"}"""))
            assertTrue(seen.contains(UrlConnectionTransport.USER_AGENT.lowercase()))
        }
    }

    @Test
    fun aProviderErrorIsAResponseNotAnException() {
        Server { response("401 Unauthorized", """{"error":{"message":"bad key"}}""".toByteArray()) }.use { server ->
            val answer = runBlocking {
                UrlConnectionTransport().send(HttpRequest(url = "http://127.0.0.1:${server.port}/v1/messages", body = "{}"))
            }
            // The status and the provider's own words survive: the client decides what to say.
            assertEquals(401, answer.status)
            assertEquals("""{"error":{"message":"bad key"}}""", answer.body)
        }
    }

    @Test
    fun aGzippedAnswerIsDecoded() {
        val sink = java.io.ByteArrayOutputStream()
        GZIPOutputStream(sink).use { it.write("""{"choices":[]}""".toByteArray()) }
        val compressed = sink.toByteArray()
        Server { response(body = compressed, headers = mapOf("Content-Encoding" to "gzip")) }.use { server ->
            val answer = runBlocking {
                UrlConnectionTransport().send(HttpRequest(url = "http://127.0.0.1:${server.port}/v1/chat/completions", body = "{}"))
            }
            assertEquals("""{"choices":[]}""", answer.body)
        }
    }

    @Test
    fun serverSentEventsArriveOnePayloadAtATimeWithCommentsDropped() {
        val stream = buildString {
            append(": ping\n\n")
            append("data: {\"n\":1}\n\n")
            append("data: {\"n\":2}\ndata: {\"n\":3}\n\n")
            append("data: [DONE]\n\n")
        }
        Server { response(body = stream.toByteArray(), headers = mapOf("Content-Type" to "text/event-stream")) }.use { server ->
            val events = runBlocking {
                UrlConnectionTransport()
                    .stream(HttpRequest(url = "http://127.0.0.1:${server.port}/v1/chat/completions", body = "{}", accept = "text/event-stream"))
                    .toList()
            }
            // Multi line data fields join with a newline, exactly as the spec says they do.
            assertEquals(listOf("""{"n":1}""", "{\"n\":2}\n{\"n\":3}", "[DONE]"), events)
        }
    }

    @Test
    fun aStreamedErrorStatusIsReportedWithItsBody() {
        Server { response("429 Too Many Requests", """{"error":{"message":"slow down"}}""".toByteArray()) }.use { server ->
            val thrown = runCatching {
                runBlocking {
                    UrlConnectionTransport()
                        .stream(HttpRequest(url = "http://127.0.0.1:${server.port}/v1/chat/completions", body = "{}"))
                        .toList()
                }
            }.exceptionOrNull()
            assertTrue(thrown is TransportException)
            assertTrue((thrown as TransportException).message!!.contains("slow down"))
            assertEquals(429, thrown.status)
            assertTrue(thrown.retryable)
        }
    }

    @Test
    fun aDeadEndpointFailsAsATransportProblem() {
        val port = ServerSocket(0).use { it.localPort }
        val thrown = runCatching {
            runBlocking { UrlConnectionTransport().send(HttpRequest(url = "http://127.0.0.1:$port/v1", body = "{}")) }
        }.exceptionOrNull()
        assertTrue(thrown is TransportException)
        assertTrue((thrown as TransportException).retryable)
    }

    @Test
    fun somethingThatIsNotAUrlSaysSoInsteadOfThrowingSomethingOdd() {
        val thrown = runCatching {
            runBlocking { UrlConnectionTransport().send(HttpRequest(url = "not a url", body = "{}")) }
        }.exceptionOrNull()
        assertTrue(thrown is TransportException)
        assertTrue(thrown!!.message!!.contains("not a usable url"))
    }
}
