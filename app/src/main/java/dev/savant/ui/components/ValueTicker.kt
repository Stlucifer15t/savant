package dev.savant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.savant.domain.model.formatReal
import dev.savant.ui.theme.LocalReducedMotion
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantMono
import dev.savant.ui.theme.SavantMotion
import kotlin.math.roundToLong
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch

/**
 * A value that ticks to its new number and flashes the accent once.
 *
 * The ticker is the only place savant animates a value: 220ms of counting plus a single 400ms
 * accent flash, then the exact text. With reduced motion on, the final value is shown immediately
 * and nothing moves.
 */
@Composable
fun ValueTicker(
    text: String,
    modifier: Modifier = Modifier,
    numeric: Double? = null,
    integral: Boolean = false,
    style: TextStyle = SavantMono.value,
    baseColor: Color = Savant.colors.text,
    flash: Boolean = true,
) {
    val colors = Savant.colors
    val reduced = LocalReducedMotion.current
    val last = remember { mutableStateOf(numeric) }
    val glow = remember { Animatable(0f) }
    val ticker = remember { Animatable((numeric ?: 0.0).toFloat()) }

    LaunchedEffect(text, numeric) {
        val previous = last.value
        last.value = numeric
        if (previous == numeric) return@LaunchedEffect
        if (reduced || !flash || numeric == null || previous == null) return@LaunchedEffect

        launch {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(if (reduced) 0 else SavantMotion.FLASH_MS))
        }
        ticker.snapTo(previous.toFloat())
        ticker.animateTo(numeric.toFloat(), tween(if (reduced) 0 else SavantMotion.STANDARD_MS))
    }

    // Only tick while the animation is actually running: at rest the exact text wins, so a value
    // that does not fit in a Float is never displayed as a rounded approximation.
    val shown = if (!reduced && numeric != null && ticker.isRunning) formatTick(ticker.value, integral) else text
    val mix = if (reduced) 0f else glow.value

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(colors.accent.copy(alpha = 0.16f * mix))
            .padding(horizontal = 3.dp, vertical = 1.dp),
    ) {
        Text(
            text = shown,
            style = style,
            color = lerp(baseColor, colors.accent, mix),
            maxLines = 1,
        )
    }
}

private fun formatTick(value: Float, integral: Boolean): String =
    if (integral) value.toDouble().roundToLong().toString() else formatReal(value.toDouble())
