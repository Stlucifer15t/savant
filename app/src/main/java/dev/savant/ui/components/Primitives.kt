package dev.savant.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.savant.ui.icons.SavantIcon
import dev.savant.ui.theme.Savant
import dev.savant.ui.theme.SavantDimens
import dev.savant.ui.theme.SavantMono

/**
 * The small set of surfaces savant is built from.
 *
 * Everything is flat: a 1px border, a 6dp radius, no elevation, no gradient. Depth comes from
 * [SavantColors.surfaceRaised] being one step lighter than the background.
 */
@Composable
fun SavantPanel(
    modifier: Modifier = Modifier,
    background: Color = Savant.colors.surface,
    borderColor: Color = Savant.colors.border,
    radius: Dp = SavantDimens.radius,
    contentPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(SavantDimens.hairline, borderColor, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** A 1px rule. Used instead of Material dividers so the weight never changes with theme. */
@Composable
fun Hairline(
    modifier: Modifier = Modifier,
    color: Color = Savant.colors.border,
    vertical: Boolean = false,
) {
    Box(
        modifier = if (vertical) {
            modifier.width(SavantDimens.hairline).background(color)
        } else {
            modifier.fillMaxWidth().height(SavantDimens.hairline).background(color)
        },
    )
}

/** Small uppercase mono label: section heads, counts, meta. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Savant.colors.textMuted,
) {
    Text(
        text = text.uppercase(),
        style = SavantMono.tag,
        color = color,
        modifier = modifier,
    )
}

/** Type tags, format badges, confidence pills. Outline only — never filled blocks of colour. */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Savant.colors.textMuted,
    emphasis: Boolean = false,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (emphasis) color.copy(alpha = 0.14f) else Color.Transparent)
            .border(
                SavantDimens.hairline,
                color.copy(alpha = if (emphasis) 0.55f else 0.32f),
                shape,
            )
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(text = text.uppercase(), style = SavantMono.tag, color = color)
    }
}

/** Flat bordered button. The default for everything that is not the primary action. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    borderColor: Color = Savant.colors.border,
    contentColor: Color = Savant.colors.text,
) {
    val shape = RoundedCornerShape(SavantDimens.radius)
    val content = contentColor.copy(alpha = if (enabled) 1f else 0.4f)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Savant.colors.surface)
            .border(SavantDimens.hairline, borderColor, shape)
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) SavantIcon(icon, tint = content, size = 15.dp)
        Text(text = text, style = SavantMono.value, color = content)
    }
}

/**
 * The only filled button in the app.
 *
 * Send and apply use it; nothing else does. Accent colour is a promise that something happens.
 */
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    val colors = Savant.colors
    val shape = RoundedCornerShape(SavantDimens.radius)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) colors.accent else colors.accent.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) SavantIcon(icon, tint = colors.onAccent, size = 15.dp)
        Text(
            text = text,
            style = SavantMono.value.copy(color = colors.onAccent),
            color = colors.onAccent,
        )
    }
}

/** Icon-only tap target, 36dp, no background until pressed. */
@Composable
fun FlatIconButton(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Savant.colors.textMuted,
    size: Dp = 18.dp,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(SavantDimens.radius))
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        SavantIcon(
            icon,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            size = size,
            contentDescription = contentDescription,
        )
    }
}

/**
 * The dashed drop zone.
 *
 * Drawn with a Canvas stroke instead of an image so the dash length stays crisp at any size and
 * follows the theme border colour.
 */
@Composable
fun DashedPanel(
    modifier: Modifier = Modifier,
    color: Color = Savant.colors.borderStrong,
    radius: Dp = SavantDimens.radius,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .matchParentSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = Stroke(
                width = SavantDimens.hairline.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
            )
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
                style = stroke,
            )
        }
    }
}

/** One row of `label  value` in mono, used by the about and format detail lists. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = SavantMono.value,
    valueColor: Color = Savant.colors.text,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = SavantMono.path,
            color = Savant.colors.textMuted,
            modifier = Modifier.width(120.dp),
        )
        Text(text = value, style = valueStyle, color = valueColor)
    }
}
