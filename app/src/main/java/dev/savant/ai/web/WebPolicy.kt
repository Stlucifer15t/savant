package dev.savant.ai.web

import dev.savant.ai.provider.NetworkPolicy
import java.net.URI

/**
 * What savant will fetch, and how much of it.
 *
 * Four limits, all of them there because the alternative is worse:
 * - https only, except for a machine on the phone's own network (the same gate the providers use);
 * - a domain allowlist, when the user has written one. An empty list does not mean "anything goes
 *   silently": it means every fetch is confirmed first;
 * - 500 kb, enforced while reading rather than after, so a 200 mb page never lands in memory;
 * - 15 seconds, because a chat turn should not hang on somebody else's server.
 */
object WebPolicy {

    const val MAX_FETCH_BYTES: Long = 500L * 1024
    const val TIMEOUT_MILLIS: Long = 15_000
    const val MAX_TEXT_CHARS = 8_000
    const val MAX_SEARCH_HITS = 8

    private const val USER_AGENT = "savant/0.1 (a save file editor; one request per user action)"

    val defaultHeaders: List<Pair<String, String>> = listOf(
        "user-agent" to USER_AGENT,
        "accept" to "text/html,application/xhtml+xml,application/json;q=0.9,text/plain;q=0.8,*/*;q=0.5",
        "accept-language" to "en",
    )

    /** Comma, space or newline separated, blank entries dropped, all lower case. */
    fun parseAllowList(text: String): List<String> =
        text.split(',', ' ', '\n', ';', '\t').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    /** `example.com` matches itself and its subdomains; `*.example.org` does the same; `*` matches all. */
    fun hostMatches(pattern: String, host: String): Boolean {
        val wanted = pattern.trim().lowercase().removePrefix("*.")
        if (wanted == "*") return true
        val actual = host.lowercase()
        return actual == wanted || actual.endsWith(".$wanted")
    }

    fun host(url: String): String = runCatching { URI(url).host ?: "" }.getOrDefault("")

    sealed interface Verdict {
        data class Allowed(val note: String) : Verdict
        data class Refused(val reason: String) : Verdict
    }

    fun assess(url: String, allowList: List<String>): Verdict {
        val transport = NetworkPolicy.assess(url)
        if (transport is NetworkPolicy.Verdict.Refused) return Verdict.Refused(transport.reason)

        val host = host(url)
        if (host.isBlank()) return Verdict.Refused("'$url' has no host savant can check")

        if (allowList.isEmpty()) {
            return Verdict.Allowed("no allowlist set — every fetch is confirmed first")
        }
        val matched = allowList.any { hostMatches(it, host) }
        return if (matched) {
            Verdict.Allowed("allowlisted")
        } else {
            Verdict.Refused("$host is not in the allowlist (${allowList.joinToString()}) — add it in settings → privacy")
        }
    }

    /** The line the confirmation chip shows: what would be fetched, with no query string. */
    fun describe(url: String): String = NetworkPolicy.redactUrl(url)
}
