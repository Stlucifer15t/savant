package dev.savant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.savant.data.settings.ThemeMode

/**
 * Motion budget.
 *
 * Value changes get a number ticker plus one 400ms accent flash, parse rows stagger in 30ms apart,
 * everything else is under 300ms. With reduced motion on, [motionMs] collapses to zero so the same
 * code paths render final states without animating.
 */
object SavantMotion {
    const val FLASH_MS = 400
    const val STAGGER_MS = 30
    const val STANDARD_MS = 220
    const val SHEET_MS = 260
}

val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
@ReadOnlyComposable
fun motionMs(base: Int): Int = if (LocalReducedMotion.current) 0 else base

private fun darkScheme(colors: SavantColors) = darkColorScheme(
    primary = colors.accent,
    onPrimary = colors.onAccent,
    primaryContainer = colors.accentDim,
    onPrimaryContainer = colors.accent,
    inversePrimary = colors.accent,
    secondary = colors.textMuted,
    onSecondary = colors.background,
    secondaryContainer = colors.surfaceRaised,
    onSecondaryContainer = colors.text,
    tertiary = colors.textMuted,
    onTertiary = colors.background,
    background = colors.background,
    onBackground = colors.text,
    surface = colors.surface,
    onSurface = colors.text,
    surfaceVariant = colors.surfaceRaised,
    onSurfaceVariant = colors.textMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = colors.text,
    inverseOnSurface = colors.background,
    error = colors.negative,
    onError = colors.background,
    errorContainer = colors.surfaceRaised,
    onErrorContainer = colors.negative,
    outline = colors.borderStrong,
    outlineVariant = colors.border,
    scrim = Color.Black,
    surfaceBright = colors.surfaceRaised,
    surfaceDim = colors.background,
    surfaceContainer = colors.surface,
    surfaceContainerHigh = colors.surfaceRaised,
    surfaceContainerHighest = colors.surfaceRaised,
    surfaceContainerLow = colors.surface,
    surfaceContainerLowest = colors.background,
)

private fun lightScheme(colors: SavantColors) = lightColorScheme(
    primary = colors.accent,
    onPrimary = colors.onAccent,
    primaryContainer = colors.accentDim,
    onPrimaryContainer = colors.accent,
    inversePrimary = colors.accent,
    secondary = colors.textMuted,
    onSecondary = colors.background,
    secondaryContainer = colors.surfaceRaised,
    onSecondaryContainer = colors.text,
    tertiary = colors.textMuted,
    onTertiary = colors.background,
    background = colors.background,
    onBackground = colors.text,
    surface = colors.surface,
    onSurface = colors.text,
    surfaceVariant = colors.surfaceRaised,
    onSurfaceVariant = colors.textMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = colors.text,
    inverseOnSurface = colors.background,
    error = colors.negative,
    onError = colors.background,
    errorContainer = colors.surfaceRaised,
    onErrorContainer = colors.negative,
    outline = colors.borderStrong,
    outlineVariant = colors.border,
    scrim = Color.Black,
    surfaceBright = colors.surface,
    surfaceDim = colors.background,
    surfaceContainer = colors.surface,
    surfaceContainerHigh = colors.surfaceRaised,
    surfaceContainerHighest = colors.surfaceRaised,
    surfaceContainerLow = colors.surface,
    surfaceContainerLowest = colors.surface,
)

/**
 * Savant's theme.
 *
 * Material 3 is used for behaviour (ripples, text selection, sheets, dialogs) with a fully custom
 * scheme — no default purple anywhere. Every custom component reads [Savant.colors] rather than the
 * Material roles, so the two can never drift apart.
 */
@Composable
fun SavantTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (dark) SavantColorsDark else SavantColorsLight
    val scheme = if (dark) darkScheme(colors) else lightScheme(colors)

    CompositionLocalProvider(
        LocalSavantColors provides colors,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SavantTypography,
            shapes = SavantShapes,
            content = content,
        )
    }
}
