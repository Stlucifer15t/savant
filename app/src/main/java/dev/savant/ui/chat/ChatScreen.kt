package dev.savant.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.asDisplayString
import dev.savant.ui.theme.LocalSavantColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    contentPadding: PaddingValues,
    onAttach: () -> Unit,
    onSearchChange: (String) -> Unit,
    onCategoryFilter: (FieldCategory?) -> Unit,
    onToggleCategory: (FieldCategory) -> Unit,
    onProposeEdit: (String, String) -> String?,
    onRemovePending: (String) -> Unit,
    onDismissDiff: () -> Unit,
    onConfirmDiff: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onAcknowledgeWarning: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val editable = state.loaded?.tree?.editableNodes().orEmpty()
    val filtered = remember(editable, state.searchQuery, state.categoryFilter) {
        val needle = state.searchQuery.trim().lowercase()
        editable.filter { node ->
            val categoryMatches = state.categoryFilter == null || node.category == state.categoryFilter
            val queryMatches = needle.isEmpty() ||
                node.displayName.lowercase().contains(needle) ||
                node.path.lowercase().contains(needle) ||
                node.value?.asDisplayString()?.lowercase()?.contains(needle) == true
            categoryMatches && queryMatches
        }
    }
    val groups = remember(filtered) {
        FieldCategory.entries.mapNotNull { category ->
            val nodes = filtered.filter { it.category == category }
            if (nodes.isEmpty()) null else category to nodes
        }
    }

    LaunchedEffect(state.conversation.size, state.export, state.importError) {
        if (state.conversation.isNotEmpty() || state.export != null || state.importError != null) {
            delay(80)
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.source == null && !state.isImporting) {
            item(key = "intro") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "save workbench",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "inspect structured saves, stage exact edits, then export a new copy.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalSavantColors.current.muted,
                    )
                }
            }
            item(key = "drop-zone") {
                DropSaveZone(onClick = onAttach)
            }
            item(key = "empty-note") {
                AssistantBlock("JSON and XML are available in this build. the original file is never modified in place.")
            }
        }

        state.source?.let { source ->
            item(key = "source-file") {
                FileChip(
                    source = source,
                    formatName = state.detectedFormat,
                    confidence = state.confidence,
                )
            }
        }

        if (state.parseLogs.isNotEmpty()) {
            item(key = "parse-log") {
                ParseProgress(logs = state.parseLogs, active = state.isImporting)
            }
        }

        state.importError?.let { error ->
            item(key = "import-error") {
                AssistantBlock("couldn't parse this file: $error original was not changed.")
            }
        }

        state.loaded?.let { loaded ->
            item(key = "field-summary") {
                AssistantBlock(
                    "found ${editable.size} editable ${if (editable.size == 1) "value" else "values"}, grouped by category.",
                )
            }
            item(key = "field-search") {
                FieldSearch(
                    query = state.searchQuery,
                    category = state.categoryFilter,
                    onQueryChange = onSearchChange,
                    onCategoryChange = onCategoryFilter,
                )
            }

            if (groups.isEmpty()) {
                item(key = "no-fields") {
                    Text(
                        "no fields match this filter",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalSavantColors.current.muted,
                    )
                }
            }

            var rowIndex = 0
            groups.forEach { (category, nodes) ->
                val expanded = state.searchQuery.isNotBlank() || category in state.expandedCategories
                item(key = "group-${category.name}") {
                    FieldGroupHeader(
                        category = category,
                        count = nodes.size,
                        expanded = expanded,
                        onToggle = { onToggleCategory(category) },
                    )
                }
                if (expanded) {
                    items(items = nodes, key = { "field-${it.path}" }) { node ->
                        FieldRow(
                            node = node,
                            rowIndex = rowIndex++,
                            onProposeEdit = onProposeEdit,
                        )
                    }
                }
            }
        }

        items(state.conversation, key = { "line-${it.id}" }) { line ->
            if (line.author == MessageAuthor.USER) UserBubble(line.text)
            else AssistantBlock(line.text)
        }

        state.export?.let { export ->
            item(key = "export-${export.fileName}-${export.size}") {
                AssistantBlock("validated a new save copy.") {
                    ExportCard(export = export, onShare = onShare, onSave = onSave)
                }
            }
        }

        item(key = "bottom-space") { Spacer(Modifier.height(8.dp)) }
    }

    if (state.showDiff) {
        ModalBottomSheet(
            onDismissRequest = onDismissDiff,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
            dragHandle = {
                Box(
                    Modifier.padding(vertical = 10.dp).size(width = 34.dp, height = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    HorizontalDivider(thickness = 3.dp, color = LocalSavantColors.current.border)
                }
            },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("review changes", style = MaterialTheme.typography.titleLarge)
                Text(
                    "nothing is written until you apply this diff.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSavantColors.current.muted,
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 390.dp)) {
                    items(state.pendingChanges, key = { it.path }) { change ->
                        DiffRow(change = change, onRevert = { onRemovePending(change.path) })
                        HorizontalDivider(color = LocalSavantColors.current.border)
                    }
                }
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismissDiff,
                        enabled = !state.isApplying,
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Text("cancel")
                    }
                    ApplyChangesButton(
                        count = state.pendingChanges.size,
                        loading = state.isApplying,
                        onClick = onConfirmDiff,
                        modifier = Modifier.weight(2f),
                    )
                }
            }
        }
    }

    if (state.showSafetyWarning) {
        AlertDialog(
            onDismissRequest = {},
            shape = RoundedCornerShape(6.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("offline saves only", fontWeight = FontWeight.Medium) },
            text = {
                Text(
                    "use Savant only with offline, single-player games. editing online-game saves can corrupt data or trigger account bans. Savant exports a new copy and never overwrites the selected file.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = onAcknowledgeWarning) { Text("i understand") }
            },
        )
    }
}
