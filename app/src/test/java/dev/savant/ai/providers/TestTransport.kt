package dev.savant.ai.providers

import dev.savant.ai.net.HttpRequest
import dev.savant.ai.net.HttpResponse
import dev.savant.ai.net.HttpTransport
import dev.savant.ai.net.TransportException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A transport that answers from a script.
 *
 * Every provider test needs the same three things: what was sent, a canned answer, and a way to
 * fail on purpose. No sockets, no ports, no flakiness — and because [HttpTransport] is an
 * interface, the code under test cannot tell the difference.
 */
class TestTransport : HttpTransport {

    private val responses = ArrayDeque<Any>()
    val sent = mutableListOf<HttpRequest>()

    fun willAnswer(status: Int = 200, body: String, headers: Map<String, String> = emptyMap()) = apply {
        responses.addLast(HttpResponse(status, body, headers))
    }

    fun willStream(vararg lines: String) = apply {
        responses.addLast(lines.toList())
    }

    fun willFail(message: String, status: Int? = null, retryable: Boolean = true) = apply {
        responses.addLast(TransportException(message, status, retryable))
    }

    override suspend fun send(request: HttpRequest): HttpResponse {
        sent += request
        return when (val next = responses.removeFirstOrNull() ?: error("no scripted response left")) {
            is HttpResponse -> next
            is TransportException -> throw next
            else -> error("scripted a stream for a non streaming call")
        }
    }

    override fun stream(request: HttpRequest): Flow<String> = flow {
        sent += request
        when (val next = responses.removeFirstOrNull() ?: error("no scripted response left")) {
            is List<*> -> next.forEach { emit(it as String) }
            is TransportException -> throw next
            else -> error("scripted a response for a streaming call")
        }
    }

    val last: HttpRequest get() = sent.last()
}
