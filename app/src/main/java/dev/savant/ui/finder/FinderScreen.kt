package dev.savant.ui.finder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.Node
import dev.savant.domain.model.asDouble
import dev.savant.domain.model.displayText
import dev.savant.ui.components.CandidateRow
import dev.savant.ui.components.FlatIconButton
import dev.savant.ui.components.GhostButton
import dev.savant.ui.components.Hairline
import dev.savant.ui.components.KeyValueRow
import dev.savant.ui.components.SavantPanel
import dev.savant.ui.components.ScreenTopBar
import dev.savant.ui.components.SectionLabel
import dev.savant.ui.components.Tag
import dev.savant.ui.icons.SavantIcon
import dev.savant.ui.icons.SavantIcons
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/**
 * The finder screen.
 *
 * Type a name, a value, `category:stats` or `type:f32` and every match in the open save is listed
 * with its path, type and current value. Expanding a row shows the full picture and offers to hand
 * the field to the chat, which is where an edit gets its diff preview.
 */
@Composable
fun FinderScreen(
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FinderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = Savant.colors

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(
            title = "finder",
            subtitle = when {
                !state.hasSession -> "no file open"
                else -> "${state.editableCount} editable of ${state.totalCount} nodes" +
                    if (state.readOnly) " · read only" else ""
            },
            actions = {
                if (state.query.isNotBlank()) {
                    FlatIconButton(
                        icon = SavantIcons.close,
                        contentDescription = "clear search",
                        onClick = viewModel::onClear,
                    )
                }
            },
        )

        SearchField(
            value = state.query,
            onValueChange = viewModel::onQueryChanged,
            searching = state.searching,
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = SavantDimens.gutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(FieldCategory.entries.toList(), key = { it.name }) { category ->
                GhostButton(
                    text = category.label,
                    onClick = { viewModel.onFilter(category) },
                )
            }
        }
        Hairline()

        if (!state.hasSession) {
            EmptyFinder(text = "attach a save file in chat, then search it here")
            return@Column
        }
        if (state.query.isBlank()) {
            EmptyFinder(text = "search by name, path or value — try \"gold\", \"hp\", \"category:flags\"")
            return@Column
        }
        if (state.results.isEmpty() && !state.searching) {
            EmptyFinder(text = "no field matches \"${state.query}\"")
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(state.results, key = { it.path }) { node ->
                ResultRow(
                    node = node,
                    expanded = state.expandedPath == node.path,
                    readOnly = state.readOnly,
                    onToggle = { viewModel.onToggle(node.path) },
                    onSendToChat = {
                        viewModel.onSendToChat(node)
                        onOpenChat()
                    },
                )
                Hairline(color = colors.border.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, searching: Boolean) {
    val colors = Savant.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SavantDimens.gutter, vertical = 10.dp)
            .clip(RoundedCornerShape(SavantDimens.radius))
            .background(colors.surface)
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SavantIcon(
            id = if (searching) SavantIcons.loader else SavantIcons.search,
            size = 15.dp,
            tint = colors.textMuted,
        )
        Spacer(Modifier.width(9.dp))
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = SavantMono.value.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = "name, path or value",
                                style = SavantMono.value,
                                color = colors.textFaint,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        if (value.isNotBlank()) {
            Text(text = "esc", style = SavantMono.tag, color = colors.textFaint)
        }
    }
}

@Composable
private fun ResultRow(
    node: Node,
    expanded: Boolean,
    readOnly: Boolean,
    onToggle: () -> Unit,
    onSendToChat: () -> Unit,
) {
    val colors = Savant.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .clickable(onClick = onToggle),
    ) {
        CandidateRow(
            title = node.label,
            subtitle = node.path,
            typeTag = node.type.tag,
            value = node.value?.displayText() ?: "null",
            confidenceLabel = node.aiGuess?.let { "guess ${it.displayName} · ${(it.confidence * 100).toInt()}%" },
            highlighted = node.aiGuess != null,
        )
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Hairline()
                Spacer(Modifier.height(6.dp))
                KeyValueRow("category", node.category.label)
                KeyValueRow("type", node.type.tag + " · " + (node.type.sizeBytes?.toString() ?: "-") + " byte(s)")
                KeyValueRow("editable", if (node.editable) "yes" else "no")
                node.min?.let { KeyValueRow("min", it.toString()) }
                node.max?.let { KeyValueRow("max", it.toString()) }
                node.byteOffset?.let { KeyValueRow("offset", "0x%08x".format(it)) }
                node.aiGuess?.explanation?.let { KeyValueRow("why", it) }
                if (node.enumOptions.isNotEmpty()) {
                    KeyValueRow("options", node.enumOptions.joinToString(", "))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GhostButton(
                        text = "edit in chat",
                        icon = SavantIcons.send,
                        onClick = onSendToChat,
                        enabled = node.editable && !readOnly,
                    )
                    if (!node.editable) SectionLabel(text = "not editable", color = colors.textFaint)
                }
            }
        }
    }
}

@Composable
private fun EmptyFinder(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(SavantDimens.gutter),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        SavantPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SectionLabel(text = "search")
                Text(
                    text = text,
                    style = SavantTypography.bodySmall,
                    color = Savant.colors.textMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "value search across two saves, and typed scanning of raw binaries, arrive in step 5",
                    style = SavantMono.path,
                    color = Savant.colors.textFaint,
                )
            }
        }
        Tag(text = "one file at a time", color = Savant.colors.textFaint)
    }
}
