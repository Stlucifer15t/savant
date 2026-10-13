package dev.savant.ui.formats

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.data.plugins.ImportOutcome
import dev.savant.data.plugins.PluginManager
import dev.savant.data.plugins.PluginStore
import dev.savant.data.history.NamedFieldRepository
import dev.savant.data.storage.FileLoadResult
import dev.savant.data.storage.SaveFileRepository
import dev.savant.domain.model.SaveFormat
import dev.savant.plugins.GamePlugin
import dev.savant.plugins.PluginDraft
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the formats screen asks the activity to do, because only an activity can start one. */
sealed interface FormatsEvent {
    data class Share(val uri: Uri, val fileName: String) : FormatsEvent
    data class CreateDocument(val fileName: String, val mimeType: String) : FormatsEvent
}

data class FormatsUiState(
    val plugins: List<GamePlugin> = emptyList(),
    val registeredParsers: Int = 0,
    /** Offsets the finder named, which can be turned into a binary plugin. */
    val namedOffsets: List<NamedOffset> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
    val drafting: Boolean = false,
    val draftName: String = "",
) {
    /** A named offset, reduced to what a plugin needs. */
    data class NamedOffset(val name: String, val key: String, val label: String)

    val binaryPlugins: Int get() = plugins.count { it.parser == dev.savant.plugins.PluginParserKind.BINARY }
}

/**
 * Formats: what savant can open, and what plugins have added to it.
 *
 * Import reads a json document, validates it, and refuses it with reasons when it is not a plugin —
 * nothing is executed, fetched or interpreted as code. Export writes the same document back out, so a
 * plugin can be shared, backed up or edited by hand. The third way in is the finder: offsets somebody
 * named become a binary plugin for that game, which is what naming them was for.
 */
@HiltViewModel
class FormatsViewModel @Inject constructor(
    private val store: PluginStore,
    private val manager: PluginManager,
    private val namedFields: NamedFieldRepository,
    private val files: SaveFileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FormatsUiState())
    val state: StateFlow<FormatsUiState> = _state.asStateFlow()

    private val events = Channel<FormatsEvent>(Channel.BUFFERED)
    val formatsEvents = events.receiveAsFlow()

    /** Bytes waiting for the user to choose where to save them. */
    private var pendingSave: Pair<String, ByteArray>? = null

    init {
        viewModelScope.launch {
            store.plugins.collect { plugins ->
                _state.update { it.copy(plugins = plugins, registeredParsers = plugins.size) }
            }
        }
        viewModelScope.launch {
            namedFields.observe().collect { rows ->
                val offsets = rows
                    .filter { it.format == SaveFormat.BINARY.name }
                    .map { row -> FormatsUiState.NamedOffset(name = row.displayName, key = row.nameKey, label = row.path) }
                _state.update { it.copy(namedOffsets = offsets) }
            }
        }
    }

    fun onImport(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, notice = null) }
            when (val loaded = files.load(uri, MAX_IMPORT_BYTES)) {
                is FileLoadResult.Loaded -> {
                    val text = loaded.bytes.toString(Charsets.UTF_8)
                    when (val outcome = store.importJson(text)) {
                        is ImportOutcome.Imported -> {
                            outcome.plugins.forEach { manager.applyNow(it) }
                            _state.update {
                                it.copy(
                                    busy = false,
                                    notice = buildString {
                                        append("imported ${outcome.plugins.joinToString { plugin -> plugin.gameName }}")
                                        if (outcome.skipped.isNotEmpty()) append(" · skipped ${outcome.skipped.size}")
                                    },
                                )
                            }
                        }

                        is ImportOutcome.Refused -> _state.update {
                            it.copy(busy = false, notice = outcome.reasons.first())
                        }
                    }
                }

                is FileLoadResult.TooLarge -> _state.update {
                    it.copy(busy = false, notice = "that is ${loaded.sizeBytes} bytes — a plugin is a small json document")
                }

                is FileLoadResult.Failed -> _state.update { it.copy(busy = false, notice = loaded.reason) }
            }
        }
    }

    /** Shares one plugin, or all of them when [id] is null. */
    fun onExport(id: String?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val json = store.exportJson(id)
            val name = fileNameFor(id)
            val uri = files.stageForShare(name, json.toByteArray(Charsets.UTF_8))
            _state.update { it.copy(busy = false) }
            if (uri == null) {
                _state.update { it.copy(notice = "could not stage that plugin for sharing") }
            } else {
                events.send(FormatsEvent.Share(uri, name))
            }
        }
    }

    /** Offers a save-as dialog; the bytes are written when the picker comes back. */
    fun onSave(id: String?) {
        viewModelScope.launch {
            val json = store.exportJson(id)
            val name = fileNameFor(id)
            pendingSave = name to json.toByteArray(Charsets.UTF_8)
            events.send(FormatsEvent.CreateDocument(name, "application/json"))
        }
    }

    fun onSaveResult(uri: Uri?) {
        val pending = pendingSave ?: return
        pendingSave = null
        if (uri == null) return
        viewModelScope.launch {
            val written = files.writeTo(uri, pending.second)
            _state.update { it.copy(notice = if (written) "saved ${pending.first}" else "that location could not be written") }
        }
    }

    fun onDelete(id: String) {
        viewModelScope.launch {
            val name = _state.value.plugins.firstOrNull { it.id == id }?.gameName ?: "plugin"
            store.delete(id)
            manager.removeNow(id)
            _state.update { it.copy(notice = "deleted $name — files it claimed fall back to savant's own parsers") }
        }
    }

    fun onClearAll() {
        viewModelScope.launch {
            store.clear()
            _state.update { it.copy(notice = "every plugin removed") }
        }
    }

    // ---- building a plugin from named offsets ---------------------------------------

    fun onStartDraft() {
        _state.update { it.copy(drafting = true, draftName = "", notice = null) }
    }

    fun onCancelDraft() {
        _state.update { it.copy(drafting = false, draftName = "") }
    }

    fun onDraftNameChanged(value: String) {
        _state.update { it.copy(draftName = value) }
    }

    /**
     * Turns the offsets named in the finder into a binary plugin.
     *
     * The offsets are the user's own, from their own saves. The draft is an ordinary plugin after
     * this: it can be exported, edited by hand, and it registers beside the built in parsers.
     */
    fun onCreateDraft() {
        val state = _state.value
        val offsets = state.namedOffsets
        if (offsets.isEmpty()) {
            _state.update { it.copy(notice = "nothing named yet — long press a candidate in the finder") }
            return
        }
        val (plugin, skipped) = PluginDraft.fromNamedOffsets(
            gameName = state.draftName.ifBlank { "named offsets" },
            offsets = offsets.map { PluginDraft.NamedOffset(it.name, it.key) },
        )
        viewModelScope.launch {
            store.save(plugin)
            manager.applyNow(plugin)
            _state.update {
                it.copy(
                    drafting = false,
                    draftName = "",
                    notice = buildString {
                        append("plugin '${plugin.gameName}' with ${plugin.fields.size} field(s)")
                        if (skipped.isNotEmpty()) append(" · ${skipped.size} name(s) were not offsets")
                        append(" · edit the file match before relying on it")
                    },
                )
            }
        }
    }

    fun onNoticeShown() {
        _state.update { it.copy(notice = null) }
    }

    private fun fileNameFor(id: String?): String {
        val plugin = _state.value.plugins.firstOrNull { it.id == id }
        return when {
            plugin != null -> "${plugin.gameName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}.savant-plugin.json"
            else -> "savant-plugins.json"
        }
    }

    companion object {
        /** A plugin is a small document; anything bigger is not one. */
        const val MAX_IMPORT_BYTES: Long = 512 * 1024
    }
}
