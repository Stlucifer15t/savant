package dev.savant.ai.provider

import dev.savant.ai.AiPayload
import java.net.URI

/**
 * What savant will and will not send, and what it writes down about it.
 *
 * Three rules, all of them the user's:
 * - a key is never logged whole — four characters is enough to recognise your own key and not
 *   enough to use it;
 * - a url is logged without its query string, because Gemini and a few proxies put the key there;
 * - cleartext http is for a machine on the same network as the phone (a local llama.cpp or ollama
 *   box). Anything further away has to be https, and savant says so instead of quietly sending a
 *   key over the internet in the open.
 */
object NetworkPolicy {

    /** Last four characters, or `****` when the key is short enough that four would be most of it. */
    fun redactKey(key: String?): String = AiPayload.redactKey(key)

    /** Drops the query string, where gemini and some proxies carry the key. */
    fun redactUrl(url: String): String {
        val cut = url.indexOf('?')
        return if (cut < 0) url else url.substring(0, cut) + "?…"
    }

    fun host(url: String): String = runCatching { URI(url).host ?: "" }.getOrDefault("")

    /** localhost, loopback, link local and the three private ranges. */
    fun isLocal(url: String): Boolean {
        val host = host(url).lowercase().removeSurrounding("[", "]")
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "loopback" || host == "::1") return true
        if (host.startsWith("127.")) return true
        if (host.startsWith("10.")) return true
        if (host.startsWith("192.168.")) return true
        if (host == "10.0.2.2") return true // the android emulator's name for the host machine
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val second = parts[1].toIntOrNull() ?: -1
            if (parts[0] == "172" && second in 16..31) return true
            if (parts[0] == "169" && second == 254) return true
        }
        return false
    }

    /** Whether the url is https, or is allowed to be plain http because it never leaves the network. */
    fun assess(url: String): Verdict {
        val scheme = url.substringBefore("://", "").lowercase()
        return when {
            scheme == "https" -> Verdict.Secure
            scheme == "http" && isLocal(url) -> Verdict.LocalCleartext
            scheme == "http" -> Verdict.Refused(
                "http to ${host(url)} is not allowed — a key sent in the open can be read on the way. " +
                    "use https, or a machine on your own network",
            )

            else -> Verdict.Refused("'$scheme' is not a scheme savant can call")
        }
    }

    sealed interface Verdict {
        val allowed: Boolean get() = this !is Refused

        /** https, key in a header, nothing to warn about. */
        data object Secure : Verdict

        /** Plain http to a machine on the same network. Allowed, and the ui says so. */
        data object LocalCleartext : Verdict {
            val warning: String
                get() = "cleartext http to a local address — fine on your own network, and the key is still sent unencrypted"
        }

        data class Refused(val reason: String) : Verdict
    }
}
