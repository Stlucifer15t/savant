package dev.savant.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.savant.ui.icons.SavantIcon
import dev.savant.ui.icons.SavantIcons
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/**
 * The settings vocabulary: a section label, a switch row, a value row and a choice row.
 *
 * They live here rather than in one screen because step 6 added a second settings surface — ai
 * providers — and a row that looks different on the two screens would read as a bug.
 */


@Composable
fun Section(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SavantDimens.gutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(text = title)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Savant.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = { onChange(!checked) })
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = SavantTypography.bodyMedium,
                color = if (enabled) colors.text else colors.textFaint,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SavantMono.path,
                    color = colors.textMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                checkedBorderColor = colors.accent,
                uncheckedThumbColor = colors.textMuted,
                uncheckedTrackColor = colors.surfaceRaised,
                uncheckedBorderColor = colors.border,
                disabledCheckedThumbColor = colors.onAccent.copy(alpha = 0.4f),
                disabledCheckedTrackColor = colors.accent.copy(alpha = 0.3f),
                disabledUncheckedThumbColor = colors.textFaint,
                disabledUncheckedTrackColor = colors.surfaceRaised,
            ),
        )
    }
}

@Composable
fun ValueRow(
    title: String,
    value: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    val colors = Savant.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = SavantTypography.bodyMedium, color = colors.text)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SavantMono.path,
                    color = colors.textMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Tag(text = value, color = if (muted) colors.textFaint else colors.text, emphasis = !muted)
    }
}

/**
 * One choice, laid out as a grid.
 *
 * A row of chips is what a long label breaks: five provider kinds in one `Row` squeeze
 * `custom (openai compatible)` into a column of single letters. Two columns with equal weight and a
 * two line label means a chip can never be narrower than its text — the tallest chip sets the row.
 */
@Composable
fun ChoiceGrid(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    columns: Int = 2,
    modifier: Modifier = Modifier,
) {
    val colors = Savant.colors
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp)) {
        Text(text = title, style = SavantTypography.bodyMedium, color = colors.text)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { option ->
                        ChoiceChip(
                            option = option,
                            isSelected = option == selected,
                            onClick = { onSelect(option) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keep the last row aligned with the ones above it.
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceChip(
    option: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Savant.colors
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (isSelected) colors.accentDim else colors.surfaceRaised)
            .border(SavantDimens.hairline, if (isSelected) colors.accent.copy(alpha = 0.45f) else colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = option,
            style = SavantMono.path,
            color = if (isSelected) colors.accent else colors.textMuted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A row that opens another screen: what the page is, what it currently holds, and a chevron.
 *
 * The value tag is the point — a hub should answer "what is set?" without being opened, so the
 * settings root is a table of contents rather than a form.
 */
@Composable
fun LinkRow(
    title: String,
    subtitle: String?,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    valueColor: androidx.compose.ui.graphics.Color = Savant.colors.textMuted,
) {
    val colors = Savant.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) SavantIcon(icon, tint = colors.textMuted, size = 16.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = SavantTypography.bodyMedium, color = colors.text)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = SavantMono.path,
                    color = colors.textMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) Tag(text = value, color = valueColor)
        SavantIcon(SavantIcons.chevronRight, tint = colors.textFaint, size = 15.dp)
    }
}

/** A section head with an explanation under it. Used by the pages the hub pushes. */
@Composable
fun SectionNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SavantMono.tag,
        color = Savant.colors.textFaint,
        modifier = modifier.padding(horizontal = SavantDimens.gutter, vertical = 6.dp),
    )
}

/** The old name, kept so a two option choice reads as a row rather than a grid. */
@Composable
fun ChoiceRow(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) = ChoiceGrid(title = title, options = options, selected = selected, onSelect = onSelect, columns = options.size.coerceAtLeast(1))

/** The horizontal gutter every settings panel sits in. */
fun Modifier.gutterPadding(): Modifier = this
    .fillMaxWidth()
    .padding(horizontal = SavantDimens.gutter)
