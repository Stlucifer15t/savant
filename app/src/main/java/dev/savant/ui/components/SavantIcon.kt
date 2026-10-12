package dev.savant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class SavantIconName {
    ATTACH, SEND, CHAT, FILES, FORMATS, SETTINGS, SEARCH, CHEVRON_DOWN,
    EDIT, SHARE, SAVE, REVERT, CLOSE, CHECK, PLUS, COPY,
}

@Composable
fun SavantIcon(
    name: SavantIconName,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val semantics = if (contentDescription == null) modifier else modifier.semantics {
        this.contentDescription = contentDescription
    }
    Canvas(semantics) {
        val color = if (tint == Color.Unspecified) Color.White else tint
        val sx = size.width / 24f
        val sy = size.height / 24f
        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
        val strokeWidth = 1.7.dp.toPx()
        val stroke = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, p(x1, y1), p(x2, y2), strokeWidth, StrokeCap.Round)
        fun path(block: Path.() -> Unit) {
            val value = Path().apply(block)
            drawPath(value, color, style = stroke)
        }

        when (name) {
            SavantIconName.ATTACH -> path {
                moveTo(p(8.4f, 12.8f).x, p(8.4f, 12.8f).y)
                lineTo(p(14.8f, 6.4f).x, p(14.8f, 6.4f).y)
                cubicTo(p(18.8f, 2.4f).x, p(18.8f, 2.4f).y, p(24f, 7.7f).x, p(24f, 7.7f).y, p(20f, 11.7f).x, p(20f, 11.7f).y)
                lineTo(p(11.4f, 20.3f).x, p(11.4f, 20.3f).y)
                cubicTo(p(5.4f, 26.3f).x, p(5.4f, 26.3f).y, p(-2.3f, 18.6f).x, p(-2.3f, 18.6f).y, p(3.7f, 12.6f).x, p(3.7f, 12.6f).y)
                lineTo(p(12.1f, 4.2f).x, p(12.1f, 4.2f).y)
            }
            SavantIconName.SEND -> path {
                moveTo(p(3f, 11.5f).x, p(3f, 11.5f).y)
                lineTo(p(21f, 3.5f).x, p(21f, 3.5f).y)
                lineTo(p(16f, 20.5f).x, p(16f, 20.5f).y)
                lineTo(p(11.5f, 13f).x, p(11.5f, 13f).y)
                close()
                moveTo(p(11.5f, 13f).x, p(11.5f, 13f).y)
                lineTo(p(21f, 3.5f).x, p(21f, 3.5f).y)
            }
            SavantIconName.CHAT -> {
                drawRoundRect(
                    color = color,
                    topLeft = p(3f, 4f),
                    size = Size(18f * sx, 14f * sy),
                    cornerRadius = CornerRadius(3f * sx, 3f * sy),
                    style = stroke,
                )
                line(8f, 18f, 5f, 21f)
                line(5f, 21f, 5.6f, 17.2f)
            }
            SavantIconName.FILES -> {
                path {
                    moveTo(p(3f, 7f).x, p(3f, 7f).y)
                    lineTo(p(10f, 7f).x, p(10f, 7f).y)
                    lineTo(p(12f, 9f).x, p(12f, 9f).y)
                    lineTo(p(21f, 9f).x, p(21f, 9f).y)
                    lineTo(p(21f, 19f).x, p(21f, 19f).y)
                    lineTo(p(3f, 19f).x, p(3f, 19f).y)
                    close()
                }
                line(3f, 7f, 3f, 5f)
                line(3f, 5f, 9f, 5f)
                line(9f, 5f, 11f, 7f)
            }
            SavantIconName.FORMATS -> {
                path {
                    moveTo(p(9f, 4f).x, p(9f, 4f).y)
                    cubicTo(p(6f, 4f).x, p(6f, 4f).y, p(7f, 9f).x, p(7f, 9f).y, p(4f, 9f).x, p(4f, 9f).y)
                    cubicTo(p(7f, 9f).x, p(7f, 9f).y, p(6f, 20f).x, p(6f, 20f).y, p(9f, 20f).x, p(9f, 20f).y)
                }
                path {
                    moveTo(p(15f, 4f).x, p(15f, 4f).y)
                    cubicTo(p(18f, 4f).x, p(18f, 4f).y, p(17f, 9f).x, p(17f, 9f).y, p(20f, 9f).x, p(20f, 9f).y)
                    cubicTo(p(17f, 9f).x, p(17f, 9f).y, p(18f, 20f).x, p(18f, 20f).y, p(15f, 20f).x, p(15f, 20f).y)
                }
            }
            SavantIconName.SETTINGS -> {
                line(4f, 7f, 20f, 7f); line(4f, 17f, 20f, 17f)
                drawCircle(color, 2.2f * sx, p(9f, 7f), style = Stroke(strokeWidth))
                drawCircle(color, 2.2f * sx, p(15f, 17f), style = Stroke(strokeWidth))
            }
            SavantIconName.SEARCH -> {
                drawCircle(color, 6.5f * sx, p(10.5f, 10.5f), style = stroke)
                line(15.2f, 15.2f, 21f, 21f)
            }
            SavantIconName.CHEVRON_DOWN -> {
                line(6f, 9f, 12f, 15f); line(12f, 15f, 18f, 9f)
            }
            SavantIconName.EDIT -> {
                line(5f, 19f, 8.5f, 18.2f); line(5f, 19f, 5.8f, 15.5f)
                path {
                    moveTo(p(5.8f, 15.5f).x, p(5.8f, 15.5f).y)
                    lineTo(p(15.8f, 5.5f).x, p(15.8f, 5.5f).y)
                    lineTo(p(18.5f, 8.2f).x, p(18.5f, 8.2f).y)
                    lineTo(p(8.5f, 18.2f).x, p(8.5f, 18.2f).y)
                }
            }
            SavantIconName.SHARE -> {
                drawCircle(color, 2.4f * sx, p(6f, 12f), style = stroke)
                drawCircle(color, 2.4f * sx, p(18f, 6f), style = stroke)
                drawCircle(color, 2.4f * sx, p(18f, 18f), style = stroke)
                line(8.2f, 10.9f, 15.8f, 7.1f); line(8.2f, 13.1f, 15.8f, 16.9f)
            }
            SavantIconName.SAVE -> {
                line(12f, 3f, 12f, 15f); line(8f, 11f, 12f, 15f); line(16f, 11f, 12f, 15f)
                path {
                    moveTo(p(4f, 16f).x, p(4f, 16f).y)
                    lineTo(p(4f, 20f).x, p(4f, 20f).y)
                    lineTo(p(20f, 20f).x, p(20f, 20f).y)
                    lineTo(p(20f, 16f).x, p(20f, 16f).y)
                }
            }
            SavantIconName.REVERT -> {
                line(4f, 9f, 8f, 5f); line(4f, 9f, 8f, 13f)
                path {
                    moveTo(p(4f, 9f).x, p(4f, 9f).y)
                    lineTo(p(13f, 9f).x, p(13f, 9f).y)
                    cubicTo(p(21f, 9f).x, p(21f, 9f).y, p(21f, 19f).x, p(21f, 19f).y, p(13f, 19f).x, p(13f, 19f).y)
                }
            }
            SavantIconName.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            SavantIconName.CHECK -> { line(5f, 12.5f, 10f, 17f); line(10f, 17f, 19f, 7f) }
            SavantIconName.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            SavantIconName.COPY -> {
                drawRoundRect(
                    color = color,
                    topLeft = p(7f, 7f),
                    size = Size(12f * sx, 13f * sy),
                    cornerRadius = CornerRadius(2f * sx, 2f * sy),
                    style = stroke,
                )
                path {
                    moveTo(p(15f, 7f).x, p(15f, 7f).y)
                    lineTo(p(15f, 4f).x, p(15f, 4f).y)
                    lineTo(p(5f, 4f).x, p(5f, 4f).y)
                    lineTo(p(5f, 16f).x, p(5f, 16f).y)
                    lineTo(p(7f, 16f).x, p(7f, 16f).y)
                }
            }
        }
    }
}
