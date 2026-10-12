package com.visionnav.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
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
import com.visionnav.app.settings.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Amber400,
    onPrimary = Ink,
    primaryContainer = Amber300,
    onPrimaryContainer = Ink,
    secondary = Sky300,
    onSecondary = Sky900,
    secondaryContainer = Navy700,
    onSecondaryContainer = White,
    tertiary = Amber400,
    onTertiary = Ink,
    background = Navy900,
    onBackground = White,
    surface = Navy900,
    onSurface = White,
    surfaceVariant = Navy700,
    onSurfaceVariant = Mist100,
    surfaceContainerLowest = Navy950,
    surfaceContainerLow = Navy850,
    surfaceContainer = Navy800,
    surfaceContainerHigh = Navy700,
    surfaceContainerHighest = Navy600,
    outline = Amber400,
    outlineVariant = Mist200,
    error = Red400,
    onError = Ink,
    errorContainer = Red400,
    onErrorContainer = Ink,
    inverseSurface = White,
    inverseOnSurface = Ink,
    inversePrimary = Navy500,
)

private val LightColors = lightColorScheme(
    primary = Navy500,
    onPrimary = White,
    primaryContainer = Amber400,
    onPrimaryContainer = Ink,
    secondary = Amber800,
    onSecondary = White,
    secondaryContainer = Mist100,
    onSecondaryContainer = Ink,
    tertiary = Amber400,
    onTertiary = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Mist100,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = White,
    surfaceContainerLow = White,
    surfaceContainer = Mist100,
    surfaceContainerHigh = Mist200,
    surfaceContainerHighest = Mist200,
    outline = Navy500,
    outlineVariant = InkSoft,
    error = Red700,
    onError = White,
    errorContainer = Red700,
    onErrorContainer = White,
    inverseSurface = Navy900,
    inverseOnSurface = White,
    inversePrimary = Amber400,
)

/** Status / alert colours outside the Material scheme. Always paired with icon + text. */
@Immutable
data class VisionNavStatusColors(
    val critical: Color,
    val onCritical: Color,
    val warning: Color,
    val onWarning: Color,
    val info: Color,
    val onInfo: Color,
    val good: Color,
    val degraded: Color,
    val bad: Color,
)

private val DarkStatus = VisionNavStatusColors(
    critical = Red400, onCritical = Ink,
    warning = Amber400, onWarning = Ink,
    info = Sky300, onInfo = Sky900,
    good = Green300, degraded = Amber400, bad = Red400,
)

private val LightStatus = VisionNavStatusColors(
    critical = Red700, onCritical = White,
    warning = Amber400, onWarning = Ink,
    info = Navy500, onInfo = White,
    good = Green800, degraded = Amber800, bad = Red700,
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

private val VisionNavShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun VisionNavTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = colors,
            typography = VisionNavTypography,
            shapes = VisionNavShapes,
            content = content,
        )
    }
}

object VisionNavTheme {
    val status: VisionNavStatusColors
        @Composable get() = LocalStatusColors.current
}
