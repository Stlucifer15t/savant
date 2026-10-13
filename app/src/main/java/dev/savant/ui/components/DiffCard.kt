package dev.savant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.savant.domain.model.FieldEdit
import dev.savant.domain.chat.DiffEntry
import dev.savant.domain.chat.DiffStatus
import dev.savant.ui.icons.SavantIcon
import dev.savant.ui.icons.SavantIcons
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/**
 * One proposed change: old struck through and muted, new in the accent, with a per-row revert.
 *
 * This row is the whole point of the app — a person should be able to see exactly what changes
 * before a byte is written, and be able to drop one row from a batch without losing the rest.
 */
@Composable
fun DiffRow(
    edit: FieldEdit,
    reverted: Boolean,
    onToggleRevert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Savant.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = edit.displayName,
                style = SavantTypography.bodySmall,
                color = if (reverted) colors.textFaint else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = edit.path,
                style = SavantMono.path,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (reverted) edit.newText else edit.oldText,
                    style = SavantMono.value.copy(
                        textDecoration = if (reverted) null else TextDecoration.LineThrough,
                    ),
                    color = if (reverted) colors.textFaint else colors.textMuted,
                    maxLines = 1,
                )
                SavantIcon(SavantIcons.chevronRight, size = 12.dp, tint = colors.textFaint)
                Text(
                    text = if (reverted) edit.oldText else edit.newText,
                    style = SavantMono.valueLarge,
                    color = if (reverted) colors.textFaint else colors.accent,
                    maxLines = 1,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = edit.type.tag, style = SavantMono.tag, color = colors.textFaint)
                Text(text = "·", style = SavantMono.tag, color = colors.textFaint)
                Text(text = edit.source.label, style = SavantMono.tag, color = colors.textFaint)
                if (reverted) {
                    Text(text = "· reverted", style = SavantMono.tag, color = colors.positive)
                }
            }
        }
        FlatIconButton(
            icon = if (reverted) SavantIcons.refresh else SavantIcons.undo,
            contentDescription = if (reverted) "put this row back" else "revert this row",
            onClick = onToggleRevert,
            tint = if (reverted) colors.positive else colors.textMuted,
        )
    }
}

/**
 * The confirmation card.
 *
 * Nothing is applied until `apply` is pressed, and pressing it rebuilds and re-verifies the file
 * before an export is offered. Once resolved the card stays in the transcript with its outcome, so
 * scrolling back shows what was changed and by whom.
 */
@Composable
fun DiffCard(
    entry: DiffEntry,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onToggleRevert: (String) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val colors = Savant.colors
    val live = entry.liveEdits
    SavantPanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SavantDimens.gutter, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SavantIcon(SavantIcons.edit, size = 14.dp, tint = colors.accent)
            Spacer(Modifier.width(8.dp))
            SectionLabel(text = "diff preview", color = colors.text)
            Spacer(Modifier.width(8.dp))
            Tag(text = "${live.size}", color = colors.textMuted)
            Spacer(Modifier.weight(1f))
            when (entry.status) {
                DiffStatus.PENDING -> Unit
                DiffStatus.APPLIED -> Text("applied", style = SavantMono.tag, color = colors.positive)
                DiffStatus.PARTIAL -> Text("partly applied", style = SavantMono.tag, color = colors.caution)
                DiffStatus.CANCELLED -> Text("cancelled", style = SavantMono.tag, color = colors.textFaint)
            }
        }
        entry.note?.let { note ->
            Text(
                text = note,
                style = SavantMono.path,
                color = colors.textFaint,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Hairline()
        entry.edits.forEachIndexed { index, edit ->
            if (index > 0) Hairline(color = colors.border.copy(alpha = 0.55f))
            DiffRow(
                edit = edit,
                reverted = edit.path in entry.dropped,
                onToggleRevert = { onToggleRevert(edit.path) },
            )
        }

        if (entry.pending) {
            Hairline()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                GhostButton(
                    text = "cancel",
                    onClick = onCancel,
                    enabled = !busy,
                    contentColor = colors.textMuted,
                )
                AccentButton(
                    text = if (live.size == 1) "apply" else "apply ${live.size}",
                    onClick = onConfirm,
                    icon = SavantIcons.check,
                    enabled = !busy && live.isNotEmpty(),
                )
            }
        } else {
            Spacer(Modifier.height(2.dp))
        }
    }
}
