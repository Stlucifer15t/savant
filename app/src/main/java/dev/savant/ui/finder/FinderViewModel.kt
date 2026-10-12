package dev.savant.ui.finder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.data.session.SessionStore
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class FinderUiState(
    val query: String = "",
    val results: List<Node> = emptyList(),
    val searching: Boolean = false,
    val hasSession: Boolean = false,
    val fileName: String? = null,
    val formatBadge: String? = null,
    val editableCount: Int = 0,
    val totalCount: Int = 0,
    val readOnly: Boolean = false,
    val expandedPath: String? = null,
)

/**
 * Finder: search the open save by name, path, value, category or type.
 *
 * Step 3 searches one file. Step 5 adds the value-diff finder across two saves and the typed
 * scanner for raw binaries; the row shape here is already the one [dev.savant.ui.components.CandidateRow]
 * renders, so those results drop into the same list.
 */
@HiltViewModel
class FinderViewModel @Inject constructor(
    private val sessions: SessionStore,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val results = MutableStateFlow<List<Node>>(emptyList())
    private val searching = MutableStateFlow(false)
    private val expanded = MutableStateFlow<String?>(null)
    private var searchJob: Job? = null

    val state: StateFlow<FinderUiState> = combine(
        sessions.session,
        query,
        results,
        searching,
        expanded,
    ) { session, text, hits, busy, open ->
        FinderUiState(
            query = text,
            results = hits,
            searching = busy,
            hasSession = session != null,
            fileName = session?.file?.name,
            formatBadge = session?.formatBadge,
            editableCount = session?.tree?.editableCount ?: 0,
            totalCount = session?.tree?.totalCount ?: 0,
            readOnly = session?.readOnly ?: false,
            expandedPath = open,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinderUiState())

    init {
        // A new or edited file invalidates the last result set.
        viewModelScope.launch {
            sessions.session.collect { runSearch(query.value) }
        }
    }

    fun onQueryChanged(value: String) {
        query.value = value
        if (value.isBlank()) expanded.value = null
        runSearch(value)
    }

    fun onFilter(category: FieldCategory) {
        val next = "category:${category.label}"
        query.value = next
        runSearch(next)
    }

    fun onClear() {
        query.value = ""
        results.value = emptyList()
        expanded.value = null
    }

    fun onToggle(path: String) {
        expanded.value = if (expanded.value == path) null else path
    }

    /** Hands the field to the chat, where the diff preview lives. */
    fun onSendToChat(node: Node) {
        sessions.submitDraft("set ${node.path} to ")
    }

    private fun runSearch(text: String) {
        searchJob?.cancel()
        val tree = sessions.session.value?.tree
        if (tree == null || text.isBlank()) {
            results.value = emptyList()
            searching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            searching.value = true
            try {
                results.value = withContext(Dispatchers.Default) { tree.search(text) }
            } finally {
                searching.value = false
            }
        }
    }
}
