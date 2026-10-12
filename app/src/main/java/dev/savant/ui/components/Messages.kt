package dev.savant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantTypography

/**
 * How savant speaks in the transcript.
 *
 * Assistant replies are flat with a 2dp accent rule on the left — no bubble, no avatar, no name.
 * The user's own message is the only bubble, compact and right aligned, so scanning down the list
 * shows what changed and what savant said about it.
 */
@Composable
fun AssistantText(
    lines: List<String>,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val colors = Savant.colors
    if (lines.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = SavantDimens.gutter, end = SavantDimens.gutter, top = 8.dp, bottom = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(SavantDimens.accentRule)
                .fillMaxHeight()
                .background(if (isError) colors.negative else colors.accent),
        )
        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            lines.forEachIndexed { index, line ->
                Text(
                    text = line,
                    style = if (index == 0) SavantTypography.bodyMedium else SavantMono.path,
                    color = when {
                        isError && index == 0 -> colors.negative
                        index == 0 -> colors.text
                        else -> colors.textMuted
                    },
                )
            }
        }
    }
}

/** The user's message: compact, right aligned, one border, no tail. */
@Composable
fun UserBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = Savant.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = SavantDimens.gutter, top = 6.dp, bottom = 6.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(SavantDimens.radius))
                .background(colors.surfaceRaised)
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                style = SavantTypography.bodyMedium,
                color = colors.text,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** A read-only or verification notice. Same shape as a panel, with a coloured left edge. */
@Composable
fun NoticeCard(
    title: String,
    lines: List<String>,
    modifier: Modifier = Modifier,
    accent: Color = Savant.colors.caution,
) {
    val colors = Savant.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SavantDimens.gutter, vertical = 6.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(SavantDimens.radius))
            .background(colors.surface),
    ) {
        Box(modifier = Modifier.width(SavantDimens.accentRule).fillMaxHeight().background(accent))
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = SavantTypography.titleSmall, color = colors.text)
            lines.forEach { line ->
                Text(
                    text = line,
                    style = SavantMono.path,
                    color = colors.textMuted,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
