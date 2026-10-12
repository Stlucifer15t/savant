package dev.savant.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.savant.data.file.SafetyPreferences
import dev.savant.data.file.SaveFileRepository
import dev.savant.data.file.SourceFileMetadata
import dev.savant.domain.model.EditResult
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldChange
import dev.savant.domain.model.FieldEditor
import dev.savant.domain.model.FieldNode
import dev.savant.domain.model.FieldTree
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.asDisplayString
import dev.savant.domain.parser.DetectedParser
import dev.savant.domain.parser.ParserRegistry
import dev.savant.domain.parser.SaveParser
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val files: SaveFileRepository,
    private val parsers: ParserRegistry,
    private val safetyPreferences: SafetyPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ChatUiState(showSafetyWarning = safetyPreferences.shouldShowWarning()),
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ChatEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<ChatEffect> = _effects.asSharedFlow()

    private var importJob: Job? = null
    private var session: LoadedSession? = null
    private var exportBytes: ByteArray? = null

    fun importFile(uri: Uri) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            session = null
            exportBytes = null
            _state.value = ChatUiState(
                isImporting = true,
                showSafetyWarning = _state.value.showSafetyWarning,
                parseLogs = listOf("opening document…"),
            )
            try {
                val metadata = files.metadata(uri)
                _state.update { current -> current.copy(source = metadata.toUi(), parseLogs = listOf("reading header…")) }
                val bytes = files.read(uri, metadata.size)
                _state.update { current ->
                    current.copy(
                        source = current.source?.copy(size = bytes.size.toLong()),
                        parseLogs = current.parseLogs + "read ${formatBytes(bytes.size.toLong())}",
                    )
                }
                shortStagePause()

                val detected = withContext(Dispatchers.Default) {
                    parsers.detect(metadata.name, bytes)
                }
                _state.update { current ->
                    current.copy(
                        detectedFormat = detected.parser.displayName,
                        confidence = detected.confidence.percent,
                        parseLogs = current.parseLogs + "matched ${detected.parser.displayName.lowercase()} · ${detected.confidence.percent}% confidence",
                    )
                }
                shortStagePause()

                _state.update { current -> current.copy(parseLogs = current.parseLogs + "walking field tree…") }
                val tree = withContext(Dispatchers.Default) { detected.parser.parse(bytes) }
                val editableCount = tree.editableNodes().size
                _state.update { current -> current.copy(parseLogs = current.parseLogs + "found $editableCount editable values") }
                shortStagePause()

                var backupReady = false
                var backupWarning: String? = null
                try {
                    files.createSessionBackup(metadata.name, bytes)
                    backupReady = true
                    _state.update { current -> current.copy(parseLogs = current.parseLogs + "protected original copy") }
                } catch (_: Exception) {
                    backupWarning = "couldn't protect an original copy. editing is disabled for this file."
                }

                val loaded = LoadedSaveUi(
                    fileName = metadata.name,
                    size = bytes.size.toLong(),
                    formatId = detected.parser.id,
                    formatName = detected.parser.displayName,
                    confidence = detected.confidence.percent,
                    tree = tree,
                    backupReady = backupReady,
                )
                session = LoadedSession(detected, tree, metadata)
                _state.update { current ->
                    current.copy(
                        isImporting = false,
                        loaded = loaded,
                        expandedCategories = initialExpandedCategories(tree),
                        conversation = backupWarning?.let { listOf(ChatLine.assistant(it)) }.orEmpty(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                session = null
                _state.update { current ->
                    current.copy(
                        isImporting = false,
                        importError = userFacingError(error),
                        parseLogs = current.parseLogs + "parse stopped",
                    )
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun setCategoryFilter(category: FieldCategory?) {
        _state.update { it.copy(categoryFilter = category) }
    }

    fun toggleCategory(category: FieldCategory) {
        _state.update { current ->
            val expanded = current.expandedCategories.toMutableSet()
            if (!expanded.add(category)) expanded.remove(category)
            current.copy(expandedCategories = expanded)
        }
    }

    /** Returns a validation message, or null when the diff was opened. */
    fun proposeEdit(path: String, input: String): String? {
        val current = _state.value
        val loaded = current.loaded ?: return "attach a save first"
        if (!loaded.backupReady) return "editing is blocked until an original copy can be protected"
        val node = loaded.tree.find(path) ?: return "field no longer exists"
        return when (val result = FieldEditor.parse(node, input)) {
            is EditResult.Invalid -> result.reason
            is EditResult.Valid -> {
                if (node.value == result.value) return "value is unchanged"
                queueChanges(listOf(node.toChange(result.value)))
                null
            }
        }
    }

    fun submitCommand(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return
        _state.update { current ->
            current.copy(conversation = current.conversation + ChatLine.user(command))
        }
        val loaded = _state.value.loaded
        if (loaded == null) {
            appendAssistant("attach a save before requesting an edit.")
            return
        }
        if (!loaded.backupReady) {
            appendAssistant("editing is blocked because the original copy could not be protected.")
            return
        }

        val setMatch = SetCommand.matchEntire(command)
        if (setMatch != null) {
            val target = setMatch.groupValues[1].trim().trim('"', '\'')
            val requestedValue = setMatch.groupValues[2].trim()
            val matches = findFields(loaded.tree.editableNodes(), target)
            when {
                matches.isEmpty() -> appendAssistant("no editable field matches “$target”.")
                matches.size > 1 -> {
                    val examples = matches.take(3).joinToString { it.path }
                    appendAssistant("$target matches ${matches.size} fields. use a path: $examples")
                }
                else -> {
                    val node = matches.single()
                    when (val parsed = FieldEditor.parse(node, requestedValue)) {
                        is EditResult.Invalid -> appendAssistant("${node.displayName}: ${parsed.reason}.")
                        is EditResult.Valid -> {
                            if (node.value == parsed.value) appendAssistant("${node.displayName} is already ${parsed.value.asDisplayString()}.")
                            else queueChanges(listOf(node.toChange(parsed.value)))
                        }
                    }
                }
            }
            return
        }

        if (MaxStatsCommand.matches(command)) {
            val changes = loaded.tree.editableNodes()
                .filter { it.category == FieldCategory.STATS }
                .mapNotNull { node ->
                    val maximum = FieldEditor.maximumValue(node) ?: return@mapNotNull null
                    val valid = FieldEditor.parse(node, maximum) as? EditResult.Valid ?: return@mapNotNull null
                    if (valid.value == node.value) null else node.toChange(valid.value)
                }
            if (changes.isEmpty()) {
                appendAssistant("no declared stat limits were found, so nothing was changed.")
            } else {
                queueChanges(changes)
            }
            return
        }

        appendAssistant("local commands support “set field to value”. provider-backed commands arrive in step 5.")
    }

    fun removePendingChange(path: String) {
        _state.update { current ->
            val remaining = current.pendingChanges.filterNot { it.path == path }
            current.copy(pendingChanges = remaining, showDiff = remaining.isNotEmpty())
        }
    }

    fun dismissDiff() {
        _state.update { it.copy(pendingChanges = emptyList(), showDiff = false) }
    }

    fun confirmChanges() {
        val currentState = _state.value
        val currentSession = session ?: return
        val changes = currentState.pendingChanges
        if (changes.isEmpty() || currentState.isApplying) return
        if (currentState.loaded?.backupReady != true) {
            appendAssistant("edit blocked: the protected original copy is unavailable.")
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isApplying = true) }
            try {
                val replacements = changes.associate { it.path to it.newValue }
                val editedTree = currentState.loaded.tree.withValues(replacements)
                val rebuilt = withContext(Dispatchers.Default) {
                    val encoded = currentSession.detected.parser.write(editedTree)
                    val checksummed = currentSession.detected.parser.checksumStrategy.apply(encoded, editedTree)
                    val verifiedTree = currentSession.detected.parser.parse(checksummed)
                    changes.forEach { change ->
                        check(verifiedTree.find(change.path)?.value == change.newValue) {
                            "rebuilt value did not validate at ${change.path}"
                        }
                    }
                    checksummed to verifiedTree
                }
                exportBytes = rebuilt.first
                val export = ExportUi(
                    fileName = editedFileName(currentSession.metadata.name),
                    size = rebuilt.first.size.toLong(),
                    checksumStatus = currentSession.detected.parser.checksumStrategy.displayName,
                )
                session = currentSession.copy(tree = rebuilt.second)
                _state.update { current ->
                    current.copy(
                        isApplying = false,
                        showDiff = false,
                        pendingChanges = emptyList(),
                        loaded = current.loaded?.copy(tree = rebuilt.second),
                        export = export,
                        conversation = current.conversation + ChatLine.assistant(
                            "applied ${changes.size} ${if (changes.size == 1) "change" else "changes"}. original remains untouched.",
                        ),
                    )
                }
                _effects.emit(ChatEffect.HapticTick)
            } catch (error: Exception) {
                _state.update { it.copy(isApplying = false) }
                appendAssistant("couldn't rebuild a valid save: ${userFacingError(error)}")
            }
        }
    }

    fun shareExport() {
        val bytes = exportBytes ?: return
        val export = _state.value.export ?: return
        viewModelScope.launch {
            try {
                val uri = files.cacheForSharing(export.fileName, bytes)
                _effects.emit(ChatEffect.ShareFile(uri, mimeTypeFor(_state.value.loaded?.formatId)))
                _effects.emit(ChatEffect.HapticTick)
            } catch (error: Exception) {
                _effects.emit(ChatEffect.Notify("share failed: ${userFacingError(error)}"))
            }
        }
    }

    fun saveExport(uri: Uri) {
        val bytes = exportBytes ?: return
        viewModelScope.launch {
            try {
                files.write(uri, bytes)
                _effects.emit(ChatEffect.Notify("saved a new copy"))
                _effects.emit(ChatEffect.HapticTick)
            } catch (error: Exception) {
                _effects.emit(ChatEffect.Notify("save failed: ${userFacingError(error)}"))
            }
        }
    }

    fun acknowledgeSafetyWarning() {
        safetyPreferences.acknowledgeWarning()
        _state.update { it.copy(showSafetyWarning = false) }
    }

    fun replaySafetyWarning() {
        safetyPreferences.requestWarningReplay()
        _state.update { it.copy(showSafetyWarning = true) }
    }

    private fun queueChanges(changes: List<FieldChange>) {
        _state.update { current ->
            val merged = (current.pendingChanges + changes)
                .associateBy(FieldChange::path)
                .values
                .toList()
            current.copy(pendingChanges = merged, showDiff = true)
        }
    }

    private fun appendAssistant(text: String) {
        _state.update { current ->
            current.copy(conversation = current.conversation + ChatLine.assistant(text))
        }
    }

    private fun findFields(fields: List<FieldNode>, target: String): List<FieldNode> {
        val needle = target.normalized()
        val exact = fields.filter { node ->
            node.displayName.normalized() == needle ||
                node.path.normalized() == needle ||
                node.metadata.values.any { it.normalized() == needle }
        }
        if (exact.isNotEmpty()) return exact
        return fields.filter { node ->
            node.displayName.normalized().contains(needle) || node.path.normalized().contains(needle)
        }
    }

    private fun FieldNode.toChange(newValue: FieldValue): FieldChange = FieldChange(
        path = path,
        displayName = displayName,
        type = type,
        oldValue = requireNotNull(value),
        newValue = newValue,
    )

    private fun SourceFileMetadata.toUi() = SourceFileUi(name, size ?: 0L, name.hashCode())

    private fun initialExpandedCategories(tree: FieldTree): Set<FieldCategory> {
        val present = tree.editableNodes().map(FieldNode::category).toSet()
        val preferred = linkedSetOf(FieldCategory.CURRENCY, FieldCategory.STATS).intersect(present)
        return if (preferred.isNotEmpty()) preferred else present.firstOrNull()?.let(::setOf).orEmpty()
    }

    private suspend fun shortStagePause() {
        delay(55)
    }

    private fun editedFileName(original: String): String {
        val dot = original.lastIndexOf('.')
        return if (dot > 0) "${original.substring(0, dot)}-edited${original.substring(dot)}"
        else "$original-edited"
    }

    private fun mimeTypeFor(formatId: String?): String = when (formatId) {
        "json" -> "application/json"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }

    private fun userFacingError(error: Throwable): String {
        val message = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return message.take(220).ifBlank { "unexpected file error" }
    }

    private fun String.normalized(): String = lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9.$@/]+"), "")

    private data class LoadedSession(
        val detected: DetectedParser,
        val tree: FieldTree,
        val metadata: SourceFileMetadata,
    )

    private companion object {
        val SetCommand = Regex("^set\\s+(.+?)\\s+(?:to|=)\\s*(.+)$", RegexOption.IGNORE_CASE)
        val MaxStatsCommand = Regex("^max(?:imize)?\\s+(?:all\\s+)?stats$", RegexOption.IGNORE_CASE)
    }
}

data class SourceFileUi(
    val name: String,
    val size: Long,
    val thumbnailSeed: Int,
)

data class LoadedSaveUi(
    val fileName: String,
    val size: Long,
    val formatId: String,
    val formatName: String,
    val confidence: Int,
    val tree: FieldTree,
    val backupReady: Boolean,
)

data class ExportUi(
    val fileName: String,
    val size: Long,
    val checksumStatus: String,
)

enum class MessageAuthor { USER, ASSISTANT }

data class ChatLine(
    val id: Long,
    val author: MessageAuthor,
    val text: String,
) {
    companion object {
        private var nextId = 0L
        fun user(text: String) = ChatLine(++nextId, MessageAuthor.USER, text)
        fun assistant(text: String) = ChatLine(++nextId, MessageAuthor.ASSISTANT, text)
    }
}

data class ChatUiState(
    val source: SourceFileUi? = null,
    val detectedFormat: String? = null,
    val confidence: Int? = null,
    val loaded: LoadedSaveUi? = null,
    val isImporting: Boolean = false,
    val parseLogs: List<String> = emptyList(),
    val importError: String? = null,
    val searchQuery: String = "",
    val categoryFilter: FieldCategory? = null,
    val expandedCategories: Set<FieldCategory> = emptySet(),
    val pendingChanges: List<FieldChange> = emptyList(),
    val showDiff: Boolean = false,
    val isApplying: Boolean = false,
    val export: ExportUi? = null,
    val conversation: List<ChatLine> = emptyList(),
    val showSafetyWarning: Boolean = false,
)

sealed interface ChatEffect {
    data class ShareFile(val uri: Uri, val mimeType: String) : ChatEffect
    data class Notify(val message: String) : ChatEffect
    data object HapticTick : ChatEffect
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1_024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1_024.0 * 1_024.0))
}
