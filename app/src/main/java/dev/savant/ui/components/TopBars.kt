package dev.savant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.savant.ui.icons.SavantIcon
import dev.savant.ui.icons.SavantIcons
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/**
 * A screen header.
 *
 * Flat, left aligned, 1px rule underneath. No centred title, no logo, no scrolling collapse — the
 * header is a label plus whatever actions belong to this screen.
 */
@Composable
fun ScreenTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = Savant.colors
    Column(modifier = modifier.fillMaxWidth().background(colors.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(start = SavantDimens.gutter, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = SavantTypography.titleMedium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = SavantMono.path,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
        Hairline()
    }
}

/**
 * The chat header: what is open, how sure savant is about it, and whether a backup exists.
 *
 * The dot is the backup status — green when the original is safely copied, amber when it is not.
 */
@Composable
fun SessionTopBar(
    fileName: String?,
    formatBadge: String?,
    backedUp: Boolean,
    readOnly: Boolean,
    pendingEdits: Int,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onAttach: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Savant.colors
    Column(modifier = modifier.fillMaxWidth().background(colors.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = SavantDimens.gutter, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (fileName == null) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "savant", style = SavantTypography.titleMedium, color = colors.text)
                    Text(text = "no file open", style = SavantMono.path, color = colors.textFaint)
                }
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = fileName,
                            style = SavantTypography.titleSmall,
                            color = colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (readOnly) Tag(text = "read only", color = colors.caution)
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (formatBadge != null) Tag(text = formatBadge, color = colors.text, emphasis = true)
                        StatusDot(
                            color = if (backedUp) colors.positive else colors.caution,
                            label = if (backedUp) "backed up" else "no backup",
                        )
                        if (pendingEdits > 0) {
                            Text(
                                text = "$pendingEdits changed",
                                style = SavantMono.tag,
                                color = colors.accent,
                            )
                        }
                    }
                }
            }
            FlatIconButton(
                icon = SavantIcons.undo,
                contentDescription = "undo last batch",
                onClick = onUndo,
                enabled = canUndo,
            )
            FlatIconButton(
                icon = SavantIcons.attach,
                contentDescription = "attach a save file",
                onClick = onAttach,
            )
            if (fileName != null) {
                FlatIconButton(
                    icon = SavantIcons.close,
                    contentDescription = "close this file",
                    onClick = onClose,
                )
            }
        }
        Hairline()
    }
}

/** The 6dp status dot with its label. */
@Composable
fun StatusDot(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(text = label, style = SavantMono.tag, color = Savant.colors.textMuted)
    }
}

/** Thin spacer used between stacked sections on the non-chat screens. */
@Composable
fun SectionGap(height: androidx.compose.ui.unit.Dp = 14.dp) {
    Box(modifier = Modifier.height(height).width(1.dp))
}
