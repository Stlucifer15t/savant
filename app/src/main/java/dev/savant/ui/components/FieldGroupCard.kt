package dev.savant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.savant.domain.model.EditValidator
import dev.savant.domain.model.FieldGroup
import dev.savant.domain.model.Node
import dev.savant.domain.model.asDouble
import dev.savant.domain.model.displayText
import dev.savant.ui.icons.SavantIcon
import dev.savant.ui.icons.SavantIcons
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/** Rows shown before a group offers to open the finder instead. */
private const val MAX_ROWS = 25

/**
 * One category of the found values: currency, stats, inventory, flags, other.
 *
 * Collapsible, with a count badge, and each row is `name | value | type`. Tapping a value edits it
 * in place; the MAX / 0 / x2 chips cover the three things people actually do to a number. Nothing
 * here writes anything — a tap produces a proposal that lands in the diff preview.
 */
@Composable
fun FieldGroupCard(
    group: FieldGroup,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onEdit: (Node, String) -> Unit,
    onQuick: (Node, EditValidator.QuickValue) -> Unit,
    modifier: Modifier = Modifier,
    changedPaths: Set<String> = emptySet(),
    readOnly: Boolean = false,
    /** `path → name` from the on device database, applied over whatever the parser called it. */
    savedNames: Map<String, String> = emptyMap(),
    onRename: (Node) -> Unit = {},
) {
    val colors = Savant.colors
    val editable = group.nodes.filter { it.editable }
    SavantPanel(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SavantDimens.radius))
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SavantIcon(
                id = if (collapsed) SavantIcons.chevronRight else SavantIcons.chevronDown,
                size = 14.dp,
                tint = colors.textMuted,
            )
            Spacer(Modifier.width(9.dp))
            SectionLabel(text = group.category.label, color = colors.text)
            Spacer(Modifier.width(9.dp))
            Tag(
                text = "${editable.size}/${group.count}",
                color = if (editable.isEmpty()) colors.textFaint else colors.textMuted,
            )
            Spacer(Modifier.weight(1f))
            if (readOnly) SavantIcon(SavantIcons.lock, size = 13.dp, tint = colors.caution)
        }

        if (!collapsed) {
            Hairline()
            if (editable.isEmpty()) {
                Text(
                    text = "no editable values in this group",
                    style = SavantMono.path,
                    color = colors.textFaint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                editable.take(MAX_ROWS).forEachIndexed { index, node ->
                    if (index > 0) Hairline(color = colors.border.copy(alpha = 0.55f))
                    FieldRow(
                        node = node,
                        changed = node.path in changedPaths,
                        readOnly = readOnly,
                        onEdit = onEdit,
                        onQuick = onQuick,
                        savedName = savedNames[node.path],
                        onRename = onRename,
                    )
                }
                if (editable.size > MAX_ROWS) {
                    Hairline()
                    Text(
                        text = "+${editable.size - MAX_ROWS} more — search them in finder",
                        style = SavantMono.path,
                        color = colors.textFaint,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FieldRow(
    node: Node,
    changed: Boolean,
    readOnly: Boolean,
    onEdit: (Node, String) -> Unit,
    onQuick: (Node, EditValidator.QuickValue) -> Unit,
    savedName: String? = null,
    onRename: (Node) -> Unit = {},
) {
    val colors = Savant.colors
    var editing by remember(node.path) { mutableStateOf(false) }
    var draft by remember(node.path, node.value) { mutableStateOf(node.value?.displayText() ?: "") }
    val numeric = node.value?.asDouble() != null

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { onRename(node) },
                        ),
                ) {
                    Text(
                        text = savedName ?: node.label,
                        style = SavantTypography.bodySmall,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (savedName != null) {
                        Tag(text = "saved", color = colors.textMuted)
                    } else if (node.aiGuess != null) {
                        Tag(text = "guess", color = colors.accent, emphasis = true)
                    }
                }
                Text(
                    text = if (savedName != null && node.sourceKey != savedName) {
                        "${node.path} · ${node.sourceKey}"
                    } else {
                        node.path
                    },
                    style = SavantMono.path,
                    color = colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (editing) {
                InlineValueField(
                    value = draft,
                    numeric = numeric,
                    onValueChange = { draft = it },
                    onCommit = {
                        editing = false
                        onEdit(node, draft)
                    },
                    onCancel = {
                        editing = false
                        draft = node.value?.displayText() ?: ""
                    },
                )
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = !readOnly, onClick = { editing = true })
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                ) {
                    ValueTicker(
                        text = node.value?.displayText() ?: "null",
                        numeric = node.value?.asDouble(),
                        integral = node.type.isInteger,
                        baseColor = if (changed) colors.accent else colors.text,
                        style = SavantMono.valueLarge,
                    )
                }
            }
            Spacer(Modifier.width(9.dp))
            Tag(text = node.type.tag, color = if (changed) colors.accent else colors.textMuted)
        }

        if (editing && numeric) {
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(EditValidator.QuickValue.MAX, EditValidator.QuickValue.ZERO, EditValidator.QuickValue.DOUBLE)
                    .forEach { quick ->
                        QuickChip(
                            label = quick.label,
                            onClick = {
                                editing = false
                                onQuick(node, quick)
                            },
                        )
                    }
            }
        }
    }
}

@Composable
private fun InlineValueField(
    value: String,
    numeric: Boolean,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = Savant.colors
    val shape = RoundedCornerShape(4.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(130.dp)
                .clip(shape)
                .background(colors.background)
                .padding(horizontal = 7.dp, vertical = 5.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = SavantMono.valueLarge.copy(color = colors.accent),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onCommit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FlatIconButton(
            icon = SavantIcons.check,
            contentDescription = "confirm value",
            onClick = onCommit,
            tint = colors.accent,
        )
        FlatIconButton(
            icon = SavantIcons.close,
            contentDescription = "cancel edit",
            onClick = onCancel,
        )
    }
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    val colors = Savant.colors
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(colors.surfaceRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = label, style = SavantMono.tag, color = colors.textMuted)
    }
}
