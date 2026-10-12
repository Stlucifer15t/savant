package dev.savant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.savant.domain.model.Confidence
import dev.savant.domain.model.ImportedFile
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/**
 * The file as it entered savant.
 *
 * Name, size, format badge, detection confidence and a thumbnail of the first bytes in hex — the
 * pattern is what a person actually uses to recognise a save at a glance, more than any icon.
 */
@Composable
fun FileChip(
    file: ImportedFile,
    formatBadge: String,
    confidence: Confidence?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = Savant.colors
    val shape = RoundedCornerShape(SavantDimens.radius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SavantDimens.gutter, vertical = 6.dp)
            .clip(shape)
            .background(colors.surface)
            .border(SavantDimens.hairline, colors.border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HexThumb(file.hexPreview)
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = file.name,
                style = SavantTypography.titleSmall,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = file.sizeLabel, style = SavantMono.path, color = colors.textMuted)
                Text(text = "·", style = SavantMono.path, color = colors.textFaint)
                Text(text = file.origin.label, style = SavantMono.path, color = colors.textMuted)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Tag(text = formatBadge, color = colors.text, emphasis = true)
            if (confidence != null) {
                Text(
                    text = "${confidence.percent}% ${confidence.level.name.lowercase()}",
                    style = SavantMono.tag,
                    color = confidenceColor(confidence.score, colors),
                )
            }
        }
    }
}

/** Two rows of hex pairs in a bordered square. Never a file-type glyph. */
@Composable
private fun HexThumb(hex: String, modifier: Modifier = Modifier) {
    val colors = Savant.colors
    val rows = hex.split("\n").take(2)
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .size(46.dp)
            .clip(shape)
            .background(colors.background)
            .border(SavantDimens.hairline, colors.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            rows.forEach { row ->
                Text(
                    text = row.take(11),
                    style = SavantMono.tag.copy(fontSize = 8.sp, lineHeight = 11.sp),
                    color = colors.textFaint,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun confidenceColor(score: Float, colors: dev.savant.ui.theme.SavantColors): Color = when {
    score >= 0.9f -> colors.positive
    score >= 0.6f -> colors.text
    else -> colors.caution
}
