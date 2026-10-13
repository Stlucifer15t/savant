package dev.savant.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One request that left the device.
 *
 * This is a privacy surface, not a debug aid: it answers "what exactly did savant send, and with
 * which key" without anyone having to read the source. The key is only ever the masked tail, and
 * [detail] has been through [AiPayload.redactText] before it gets here.
 */
data class NetworkRecord(
    val atMillis: Long,
    val provider: String,
    val endpoint: String,
    val status: Int?,
    val bytesSent: Int,
    val fieldsSent: Int,
    val valuesSent: Boolean,
    val streaming: Boolean,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val keyMask: String = "",
    val detail: String? = null,
) {
    val ok: Boolean get() = status != null && status in 200..299

    /** The one line the settings screen prints. */
    val summary: String
        get() = buildString {
            append(if (ok) "sent" else "failed")
            append(" · ")
            append(endpoint)
            status?.let { append(" · http ").append(it) }
            append(" · ").append(if (bytesSent < 1024) "$bytesSent b" else "${bytesSent / 1024} kb")
            append(" out")
            if (fieldsSent > 0) append(" · $fieldsSent field(s)")
            if (!valuesSent) append(" · values withheld")
            if (inputTokens != null || outputTokens != null) {
                append(" · ").append(inputTokens ?: 0).append("/").append(outputTokens ?: 0).append(" tokens")
            }
            if (keyMask.isNotEmpty()) append(" · key ").append(keyMask)
        }
}

/**
 * A ring buffer of the last [capacity] requests.
 *
 * In memory only, and off by default ([enabled] follows the settings switch) — a log that survives
 * a restart is a log that can be read by whoever picks up the phone. Recording never throws and
 * never blocks a request: a provider answer matters more than a log line.
 */
class NetworkLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val enabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _records = MutableStateFlow<List<NetworkRecord>>(emptyList())
    val records: StateFlow<List<NetworkRecord>> = _records.asStateFlow()

    val latest: NetworkRecord? get() = _records.value.firstOrNull()

    /** Newest first. Returns false when logging is off, which is the common case. */
    fun record(record: NetworkRecord): Boolean {
        if (!enabled()) return false
        _records.value = (listOf(record.copy(atMillis = clock())) + _records.value).take(capacity)
        return true
    }

    fun clear() {
        _records.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 60
    }
}
