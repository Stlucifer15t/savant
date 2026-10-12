package dev.savant.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The whole palette, in one place.
 *
 * Dark is the reference design: near-black warm grey, a single amber accent used only for changed
 * values, the send button and the message rule. Light is the same accent on warm off-white.
 * No purple, no blue, no gradients.
 */
@Immutable
data class SavantColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val accentDim: Color,
    val onAccent: Color,
    val positive: Color,
    val negative: Color,
    val caution: Color,
    val isDark: Boolean,
)

val SavantColorsDark = SavantColors(
    background = Color(0xFF0E0F11),
    surface = Color(0xFF16181B),
    surfaceRaised = Color(0xFF1B1E22),
    border = Color(0xFF26292E),
    borderStrong = Color(0xFF34383F),
    text = Color(0xFFE6E4DF),
    textMuted = Color(0xFF8B8F96),
    textFaint = Color(0xFF5C6169),
    accent = Color(0xFFF2A33A),
    accentDim = Color(0x33F2A33A),
    onAccent = Color(0xFF17120A),
    positive = Color(0xFF7FB069),
    negative = Color(0xFFD4695E),
    caution = Color(0xFFD9A441),
    isDark = true,
)

val SavantColorsLight = SavantColors(
    background = Color(0xFFF7F4EE),
    surface = Color(0xFFFFFDF8),
    surfaceRaised = Color(0xFFFDFBF6),
    border = Color(0xFFE1DBD0),
    borderStrong = Color(0xFFCDC5B7),
    text = Color(0xFF1C1B18),
    textMuted = Color(0xFF6E6A62),
    textFaint = Color(0xFF9A958B),
    accent = Color(0xFFB9741A),
    accentDim = Color(0x22B9741A),
    onAccent = Color(0xFFFFF8EC),
    positive = Color(0xFF4F7A3A),
    negative = Color(0xFFA6402F),
    caution = Color(0xFF8A6A16),
    isDark = false,
)

val LocalSavantColors = staticCompositionLocalOf { SavantColorsDark }

/** `Savant.colors.accent` — the short form used all over the UI. */
object Savant {
    val colors: SavantColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSavantColors.current
}
