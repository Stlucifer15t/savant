package dev.savant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * One radius, no elevation.
 *
 * 6dp everywhere: chips, cards, sheets and fields. Anything rounder reads as a consumer chat app,
 * and savant is a tool. Depth comes from 1px borders and a slightly lighter raised surface.
 */
val SavantShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

object SavantDimens {
    val radius = 6.dp
    val hairline = 1.dp
    val accentRule = 2.dp
    val gutter = 16.dp
    val rowHeight = 44.dp
    val inputBarHeight = 60.dp
    val chipHeight = 26.dp
}
