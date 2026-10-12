package dev.savant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.savant.domain.parser.ParseLogLine
import dev.savant.ui.theme.LocalReducedMotion
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantColors
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantMotion
import kotlinx.coroutines.delay

/**
 * The parse log, revealed line by line.
 *
 * 30ms between lines so the work is visible without becoming a show; with reduced motion the whole
 * block appears at once. Lines are monospace and never wrapped, because a path is a path.
 */
@Composable
fun ParseProgress(
    log: List<ParseLogLine>,
    done: Boolean,
    modifier: Modifier = Modifier,
) {
    if (log.isEmpty()) return
    val colors = Savant.colors
    val reduced = LocalReducedMotion.current
    var shown by remember(log.size) { mutableIntStateOf(if (reduced) log.size else 0) }

    LaunchedEffect(log.size, reduced) {
        if (reduced) {
            shown = log.size
            return@LaunchedEffect
        }
        for (index in 1..log.size) {
            shown = index
            delay(SavantMotion.STAGGER_MS.toLong())
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SavantDimens.gutter, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusSquare(if (done) colors.positive else colors.accent)
            Spacer(Modifier.width(8.dp))
            SectionLabel(text = if (done) "parse" else "parsing")
        }
        log.take(shown).forEach { line ->
            Text(
                text = line.text,
                style = SavantMono.log,
                color = lineColor(line.text, colors),
                maxLines = 2,
            )
        }
    }
}

private fun lineColor(text: String, colors: SavantColors): Color {
    val lower = text.lowercase()
    return when {
        lower.startsWith("warn") || lower.contains("could not") || lower.contains("blocked") -> colors.caution
        lower.startsWith("error") || lower.contains("failed") -> colors.negative
        lower.startsWith("found") || lower.startsWith("ok") -> colors.text
        else -> colors.textMuted
    }
}

/** The 2dp status square in front of the log header. */
@Composable
private fun StatusSquare(color: Color) {
    Box(
        modifier = Modifier
            .width(SavantDimens.accentRule)
            .height(10.dp)
            .background(color),
    )
}
