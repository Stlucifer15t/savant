package dev.savant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Immutable
data class SavantColors(
    val border: Color,
    val muted: Color,
    val raisedSurface: Color,
    val accentText: Color,
    val userBubble: Color,
)

val LocalSavantColors = staticCompositionLocalOf {
    SavantColors(
        border = Ink700,
        muted = Steel400,
        raisedSurface = Ink800,
        accentText = Amber500,
        userBubble = Ink800,
    )
}

val LocalReducedMotion = staticCompositionLocalOf { false }

private val DarkScheme = darkColorScheme(
    primary = Amber500,
    onPrimary = Ink950,
    primaryContainer = AmberPressed,
    onPrimaryContainer = Ink950,
    secondary = Chalk100,
    onSecondary = Ink950,
    secondaryContainer = Ink800,
    onSecondaryContainer = Chalk100,
    tertiary = Steel400,
    background = Ink950,
    onBackground = Chalk100,
    surface = Ink900,
    onSurface = Chalk100,
    surfaceVariant = Ink800,
    onSurfaceVariant = Steel400,
    outline = Ink700,
    outlineVariant = Ink700,
    error = ErrorDark,
    onError = Ink950,
    scrim = Color.Black,
)

private val LightScheme = lightColorScheme(
    primary = Amber500,
    onPrimary = Graphite900,
    primaryContainer = Color(0xFFFFDDB8),
    onPrimaryContainer = Graphite900,
    secondary = Graphite900,
    onSecondary = Paper100,
    secondaryContainer = Paper200,
    onSecondaryContainer = Graphite900,
    tertiary = Graphite500,
    background = Paper50,
    onBackground = Graphite900,
    surface = Paper100,
    onSurface = Graphite900,
    surfaceVariant = Paper200,
    onSurfaceVariant = Graphite500,
    outline = PaperBorder,
    outlineVariant = PaperBorder,
    error = ErrorLight,
    onError = Color.White,
    scrim = Color.Black,
)

private val SavantShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(6.dp),
)

@Composable
fun SavantTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val extras = if (dark) {
        SavantColors(
            border = Ink700,
            muted = Steel400,
            raisedSurface = Ink800,
            accentText = Amber500,
            userBubble = Ink800,
        )
    } else {
        SavantColors(
            border = PaperBorder,
            muted = Graphite500,
            raisedSurface = Paper200,
            accentText = AmberAccessible,
            userBubble = Paper200,
        )
    }
    CompositionLocalProvider(
        LocalSavantColors provides extras,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = SavantTypography,
            shapes = SavantShapes,
            content = content,
        )
    }
}
