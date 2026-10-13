package dev.savant.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

@Composable
fun ChoiceRow(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val colors = Savant.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp)) {
        Text(text = title, style = SavantTypography.bodyMedium, color = colors.text)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) colors.accentDim else colors.surfaceRaised)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = option,
                        style = SavantMono.value,
                        color = if (isSelected) colors.accent else colors.textMuted,
                    )
                }
            }
        }
    }
}

/** The horizontal gutter every settings panel sits in. */
fun Modifier.gutterPadding(): Modifier = this
    .fillMaxWidth()
    .padding(horizontal = SavantDimens.gutter)
