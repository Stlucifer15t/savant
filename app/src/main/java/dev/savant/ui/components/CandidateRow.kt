package dev.savant.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/**
 * One search hit.
 *
 * Used by the finder now and by the binary candidate list in step 5: the shape is the same —
 * where it is, what type it is, what it holds, how sure savant is, and one action.
 */
@Composable
fun CandidateRow(
    title: String,
    subtitle: String,
    typeTag: String,
    value: String,
    modifier: Modifier = Modifier,
    confidenceLabel: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    highlighted: Boolean = false,
) {
    val colors = Savant.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = SavantTypography.bodySmall,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Tag(text = typeTag, color = if (highlighted) colors.accent else colors.textMuted)
            }
            Text(
                text = subtitle,
                style = SavantMono.path,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (confidenceLabel != null) {
                Text(
                    text = confidenceLabel,
                    style = SavantMono.tag,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        ValueTicker(
            text = value,
            baseColor = if (highlighted) colors.accent else colors.text,
            style = SavantMono.valueLarge,
            flash = false,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.width(9.dp))
            GhostButton(text = actionLabel, onClick = onAction)
        }
    }
}
