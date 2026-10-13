package dev.savant.ui.finder

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.data.history.NamedFieldRepository
import dev.savant.data.session.SessionStore
import dev.savant.data.storage.FileLoadResult
import dev.savant.data.storage.SaveFileRepository
import dev.savant.domain.finder.Candidate
import dev.savant.domain.finder.SaveDiff
import dev.savant.domain.finder.ScanSpec
import dev.savant.domain.model.Endianness
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.Node
import dev.savant.domain.model.SaveFormat
import dev.savant.domain.model.displayText
import dev.savant.domain.model.formatBytes
import dev.savant.domain.parser.NodeLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Which of the three things the finder does right now. */
enum class FinderMode(val label: String) {
    /** Search the parsed field tree of the open save. */
    OPEN_FILE("open file"),

    /** Two saves of the same game, before and after a change. */
    COMPARE("compare saves"),

    /** One file and a value: every offset that holds it. */
    SCAN("scan bytes"),
}

/** Which of the three files a picker result belongs to. C is the optional third save. */
enum class ScanSlot(val label: String) {
    A("save a"),
    B("save b"),
    C("save c"),
}

/** Metadata for a picked file. The bytes stay in the view model, never in ui state. */
data class ScanFileState(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
) {
    val sizeLabel: String get() = formatBytes(sizeBytes)
}

data class FinderUiState(
    // ---- search of the open file (step 3) -----------------------------------------
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
    // ---- byte level finder (step 5) -------------------------------------------------
    val mode: FinderMode = FinderMode.OPEN_FILE,
    val fileA: ScanFileState? = null,
    val fileB: ScanFileState? = null,
    val fileC: ScanFileState? = null,
    val scanTarget: String = "",
    val types: Set<FieldType> = ScanSpec.DEFAULT_TYPES,
    val endianness: Set<Endianness> = setOf(Endianness.LITTLE, Endianness.BIG),
    val alignedOnly: Boolean = false,
    val candidates: List<Candidate> = emptyList(),
    val scanLog: List<String> = emptyList(),
    val working: Boolean = false,
    val narrowed: Boolean = false,
    /** Which candidate the name field is open for. */
    val namingKey: String? = null,
    /** Offsets already named on this device, so a row shows the name instead of asking again. */
    val namedOffsets: Map<String, String> = emptyMap(),
    val notice: String? = null,
) {
    val canCompare: Boolean get() = fileA != null && fileB != null && !working
    val canScan: Boolean get() = fileA != null && scanTarget.isNotBlank() && !working
    val canNarrow: Boolean get() = fileC != null && candidates.isNotEmpty() && !working
}

/**
 * Finder: search the open save by name, path, value, category or type — and, when the format is not
 * one savant can parse, find the value in the bytes instead.
 *
 * The byte level modes never write anything. They produce [Candidate] rows: an offset, a type, a
 * byte order, what was there before and after, and the reasons for the ranking. Naming a candidate
 * stores it per format, which is what step 7 turns into a plugin mapping.
 *
 * Parsing and scanning both run off the main thread; a 50 MB file must not cost a frame.
 */
@HiltViewModel
class FinderViewModel @Inject constructor(
    private val sessions: SessionStore,
    private val files: SaveFileRepository,
    private val namedFields: NamedFieldRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FinderUiState())
    val state: StateFlow<FinderUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** Loaded bytes, held only while a scan can use them and dropped when the mode changes. */
    private var bytesA: ByteArray? = null
    private var bytesB: ByteArray? = null
    private var bytesC: ByteArray? = null

    init {
        viewModelScope.launch {
            sessions.session.collect { session ->
                _state.update {
                    it.copy(
                        hasSession = session != null,
                        fileName = session?.file?.name,
                        formatBadge = session?.formatBadge,
                        editableCount = session?.tree?.editableCount ?: 0,
                        totalCount = session?.tree?.totalCount ?: 0,
                        readOnly = session?.readOnly ?: false,
                    )
                }
                // A new or edited file invalidates the last result set.
                runSearch(_state.value.query)
            }
        }
        viewModelScope.launch {
            namedFields.observe().collect { rows ->
                val offsets = rows
                    .filter { it.format == SaveFormat.BINARY.name }
                    .associate { it.nameKey to it.displayName }
                _state.update { it.copy(namedOffsets = offsets) }
            }
        }
    }

    // ---- search of the open file ---------------------------------------------------

    fun onQueryChanged(value: String) {
        _state.update { it.copy(query = value, expandedPath = if (value.isBlank()) null else it.expandedPath) }
        runSearch(value)
    }

    fun onFilter(category: FieldCategory) {
        val next = "category:${category.label}"
        _state.update { it.copy(query = next) }
        runSearch(next)
    }

    fun onClear() {
        _state.update { it.copy(query = "", results = emptyList(), expandedPath = null) }
    }

    fun onToggle(path: String) {
        _state.update { it.copy(expandedPath = if (it.expandedPath == path) null else path) }
    }

    /** Hands the field to the chat, where the diff preview lives. */
    fun onSendToChat(node: Node) {
        sessions.submitDraft("set ${node.path} to ")
    }

    private fun runSearch(text: String) {
        searchJob?.cancel()
        val tree = sessions.session.value?.tree
        if (tree == null || text.isBlank()) {
            _state.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            try {
                val hits = withContext(Dispatchers.Default) { tree.search(text) }
                _state.update { it.copy(results = hits, searching = false) }
            } catch (error: Exception) {
                _state.update { it.copy(results = emptyList(), searching = false, notice = error.message ?: "search failed") }
            }
        }
    }

    // ---- byte level finder ---------------------------------------------------------

    fun onMode(mode: FinderMode) {
        _state.update {
            it.copy(mode = mode, candidates = emptyList(), scanLog = emptyList(), notice = null, namingKey = null)
        }
    }

    fun onScanTargetChanged(value: String) {
        _state.update { it.copy(scanTarget = value) }
    }

    fun onPickFile(slot: ScanSlot, uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, notice = null) }
            val limit = if (_state.value.mode == FinderMode.COMPARE) COMPARE_FILE_BYTES else NodeLimits.MAX_FILE_BYTES
            when (val loaded = files.load(uri, limit)) {
                is FileLoadResult.Loaded -> {
                    val record = ScanFileState(uri = uri.toString(), name = loaded.name, sizeBytes = loaded.bytes.size.toLong())
                    when (slot) {
                        ScanSlot.A -> {
                            bytesA = loaded.bytes
                            _state.update { it.copy(fileA = record, working = false, candidates = emptyList(), narrowed = false) }
                        }

                        ScanSlot.B -> {
                            bytesB = loaded.bytes
                            _state.update { it.copy(fileB = record, working = false, candidates = emptyList(), narrowed = false) }
                        }

                        ScanSlot.C -> {
                            bytesC = loaded.bytes
                            _state.update { it.copy(fileC = record, working = false, narrowed = false) }
                        }
                    }
                }

                is FileLoadResult.TooLarge -> _state.update {
                    it.copy(
                        working = false,
                        notice = "that file is ${formatBytes(loaded.sizeBytes)} — the limit here is ${formatBytes(loaded.limitBytes)}",
                    )
                }

                is FileLoadResult.Failed -> _state.update { it.copy(working = false, notice = loaded.reason) }
            }
        }
    }

    /** Fills a slot from the file that is already open in chat, without another picker. */
    fun onUseOpenFile(slot: ScanSlot) {
        val uri = sessions.session.value?.file?.uri ?: return
        onPickFile(slot, Uri.parse(uri))
    }

    fun onToggleType(type: FieldType) {
        _state.update { state ->
            val next = state.types.toMutableSet()
            if (!next.remove(type)) next.add(type)
            // An empty type set would scan for nothing, which reads as a hang.
            if (next.isEmpty()) state else state.copy(types = next)
        }
    }

    fun onToggleEndianness(endianness: Endianness) {
        _state.update { state ->
            val next = state.endianness.toMutableSet()
            if (!next.remove(endianness)) next.add(endianness)
            if (next.isEmpty()) state else state.copy(endianness = next)
        }
    }

    fun onToggleAligned() {
        _state.update { it.copy(alignedOnly = !it.alignedOnly) }
    }

    fun onOnlyIntegers() {
        _state.update { it.copy(types = ScanSpec.INTEGERS) }
    }

    fun onAllTypes() {
        _state.update { it.copy(types = ScanSpec.DEFAULT_TYPES) }
    }

    /** Two saves, one change: rank every typed value that moved. */
    fun onRunCompare() {
        val a = bytesA
        val b = bytesB
        if (a == null || b == null) {
            _state.update { it.copy(notice = "pick two saves of the same game first") }
            return
        }
        val spec = spec()
        viewModelScope.launch {
            _state.update { it.copy(working = true, notice = null, namingKey = null) }
            val report = withContext(Dispatchers.Default) { SaveDiff.compare(a, b, spec) }
            _state.update {
                it.copy(
                    working = false,
                    candidates = report.candidates,
                    scanLog = report.lines,
                    narrowed = false,
                    notice = if (report.isEmpty) "no candidate survived the ranking" else null,
                )
            }
        }
    }

    /** One file and a number the user knows: every offset that holds it. */
    fun onRunScan() {
        val a = bytesA
        val state = _state.value
        if (a == null) {
            _state.update { it.copy(notice = "pick a file first") }
            return
        }
        val target = FieldValue.parse(state.scanTarget)
        if (target == FieldValue.Null || (target as? FieldValue.Text)?.value?.isBlank() == true) {
            _state.update { it.copy(notice = "type the value you are looking for — a number, or text in quotes") }
            return
        }
        val spec = spec()
        viewModelScope.launch {
            _state.update { it.copy(working = true, notice = null, namingKey = null) }
            val hits = withContext(Dispatchers.Default) { SaveDiff.findValue(a, target, spec) }
            _state.update {
                it.copy(
                    working = false,
                    candidates = hits,
                    narrowed = false,
                    scanLog = listOf(
                        "file · ${state.fileA?.name ?: "?"} · ${formatBytes(a.size.toLong())}",
                        "looking for ${target.displayText()} as ${spec.typeCount} type(s) × ${spec.endianness.size} byte order(s)",
                        if (hits.isEmpty()) {
                            "no offset holds that value — try another type, or the other byte order"
                        } else {
                            "${hits.size} offset(s) hold it" + if (hits.size >= spec.limit) ", capped at ${spec.limit}" else ""
                        },
                    ),
                    notice = if (hits.isEmpty()) "nothing holds that value" else null,
                )
            }
        }
    }

    /** A third save: keep what held, promote what moved again, drop what went back. */
    fun onNarrow() {
        val c = bytesC
        if (c == null) {
            _state.update { it.copy(notice = "pick a third save first") }
            return
        }
        val before = _state.value.candidates
        if (before.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(working = true, notice = null) }
            val kept = withContext(Dispatchers.Default) { SaveDiff.narrow(before, c) }
            _state.update {
                it.copy(
                    working = false,
                    candidates = kept,
                    narrowed = true,
                    scanLog = it.scanLog + SaveDiff.narrowSummary(before.size, kept.size),
                    notice = if (kept.isEmpty()) "every candidate went back to its old value" else null,
                )
            }
        }
    }

    fun onNameIt(candidate: Candidate) {
        _state.update { it.copy(namingKey = if (it.namingKey == candidate.key) null else candidate.key) }
    }

    /**
     * Stores a name for an offset.
     *
     * This is the whole output of the byte finder: nothing was written to any file, and the name is
     * what step 7 turns into a plugin field mapping.
     */
    fun onSaveName(candidate: Candidate, name: String) {
        val fileName = _state.value.fileA?.name
        viewModelScope.launch {
            val saved = namedFields.rememberCandidate(candidate, name, fileName)
            _state.update {
                it.copy(
                    namingKey = null,
                    notice = if (saved == null) "a name cannot be empty" else "saved ${candidate.describeAs(saved.displayName)}",
                )
            }
        }
    }

    fun onForgetScan() {
        bytesA = null
        bytesB = null
        bytesC = null
        _state.update {
            it.copy(
                fileA = null,
                fileB = null,
                fileC = null,
                candidates = emptyList(),
                scanLog = emptyList(),
                notice = null,
                narrowed = false,
                namingKey = null,
            )
        }
    }

    fun onNoticeShown() {
        _state.update { it.copy(notice = null) }
    }

    private fun spec(): ScanSpec = ScanSpec(
        types = _state.value.types,
        endianness = _state.value.endianness,
        alignedOnly = _state.value.alignedOnly,
        limit = CANDIDATE_LIMIT,
    )

    companion object {
        /**
         * Two files are in memory at once here, so the cap is lower than the parser's: 64 MB of save
         * data is already a lot to ask of a phone, and a save bigger than that is a database.
         */
        const val COMPARE_FILE_BYTES: Long = 32L * 1024 * 1024
        const val CANDIDATE_LIMIT = 400
    }
}
