package dev.savant.data.ai

import dev.savant.ai.provider.NetworkEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The network activity list, newest first, in memory.
 *
 * Not persisted, on purpose: this is a debugging aid for the session you are in, and a log of every
 * provider call is exactly the kind of thing that should not outlive the app being closed. Entries
 * are [NetworkEvent]s, which carry no prompt text, no field names and no key — only the shape of the
 * call and how it went.
 */
@Singleton
class NetworkLog @Inject constructor() {

    private val _entries = MutableStateFlow<List<NetworkEvent>>(emptyList())
    val entries: StateFlow<List<NetworkEvent>> = _entries.asStateFlow()

    fun record(event: NetworkEvent) {
        _entries.update { current -> (listOf(event) + current).take(MAX_ENTRIES) }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** How many calls failed, for the settings row that says whether anything went wrong. */
    val failures: Int get() = _entries.value.count { !it.ok }

    companion object {
        const val MAX_ENTRIES = 100
    }
}
