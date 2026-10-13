package dev.savant.ai.provider

/**
 * One provider call, as the network log shows it.
 *
 * The url is already stripped of its query string and the key is already four characters, because
 * the only thing that ever sees this object after the call is a list on a settings screen. Nothing
 * here carries a prompt, a field name or a value: what a request was *about* is the save file's
 * business, and the log is not.
 */
data class NetworkEvent(
    val atMillis: Long,
    val provider: String,
    val model: String?,
    val method: String,
    val url: String,
    val status: Int,
    val latencyMillis: Long,
    val requestBytes: Int,
    val responseBytes: Int,
    val keyMask: String,
    val cleartext: Boolean,
    val error: String? = null,
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
) {
    val ok: Boolean get() = status in 200..299

    /** `POST https://api.openai.com/v1/chat/completions · 200 · 812 ms · 41/118 tokens` */
    val summary: String
        get() = buildString {
            append(method)
            append(' ')
            append(url)
            append(" · ")
            append(if (status == 0) "failed" else status.toString())
            append(" · ")
            append("${latencyMillis} ms")
            if (tokensIn != null || tokensOut != null) append(" · ${tokensIn ?: 0}/${tokensOut ?: 0} tokens")
            if (cleartext) append(" · cleartext")
        }

    val whenLabel: String
        get() {
            val minutes = (System.currentTimeMillis() - atMillis) / 60_000
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 60 * 24 -> "${minutes / 60} h ago"
                else -> "${minutes / (60 * 24)} d ago"
            }
        }
}
