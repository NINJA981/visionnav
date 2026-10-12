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

/**
 * The street tokens every screen draws with. Dark theme is the street at night (paint on asphalt);
 * light theme inverts it (asphalt bars on paint) for users who read better on a light ground.
 */
@Immutable
data class KerbColors(
    /** Ground. */
    val ground: Color,
    /** Text and marks on [ground]. */
    val onGround: Color,
    val onGroundDim: Color,
    /** Zebra bar fill (primary actions). */
    val bar: Color,
    val onBar: Color,
    /** Raised quiet surface (secondary rows, panels). */
    val kerb: Color,
    val onKerb: Color,
    /** Attention: tactile pad, focus, warnings. */
    val amber: Color,
    val onAmber: Color,
    /** Amber used as a stroke or text on [ground]. */
    val amberInk: Color,
    /** Stop: critical alerts only. */
    val red: Color,
    val onRed: Color,
    val isDark: Boolean,
)

private val NightKerb = KerbColors(
    ground = Asphalt, onGround = Paint, onGroundDim = PaintDim,
    bar = Paint, onBar = Asphalt,
    kerb = KerbGrey, onKerb = Paint,
    amber = SignalAmber, onAmber = Asphalt, amberInk = SignalAmber,
    red = SignalRed, onRed = Paint,
    isDark = true,
)

private val DayKerb = KerbColors(
    ground = Paint, onGround = Asphalt, onGroundDim = KerbGrey,
    bar = Asphalt, onBar = Paint,
    kerb = Color(0xFFDEDCD4), onKerb = Asphalt,
    amber = SignalAmber, onAmber = Asphalt, amberInk = SignalAmberDeep,
    red = SignalRed, onRed = Paint,
    isDark = false,
)

/** Status / alert colours kept for existing call sites. Always paired with words. */
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

private fun statusFor(k: KerbColors) = VisionNavStatusColors(
    critical = k.red, onCritical = k.onRed,
    warning = k.amber, onWarning = k.onAmber,
    info = k.kerb, onInfo = k.onKerb,
    good = k.onGround, degraded = k.amberInk, bad = if (k.isDark) Color(0xFFFF8A80) else k.red,
)

private fun schemeFor(k: KerbColors): ColorScheme {
    val base = if (k.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = k.bar, onPrimary = k.onBar,
        primaryContainer = k.amber, onPrimaryContainer = k.onAmber,
        secondary = k.amberInk, onSecondary = k.ground,
        secondaryContainer = k.kerb, onSecondaryContainer = k.onKerb,
        tertiary = k.amber, onTertiary = k.onAmber,
        background = k.ground, onBackground = k.onGround,
        surface = k.ground, onSurface = k.onGround,
        surfaceVariant = k.kerb, onSurfaceVariant = k.onGroundDim,
        surfaceContainerLowest = k.ground, surfaceContainerLow = k.ground,
        surfaceContainer = k.kerb, surfaceContainerHigh = k.kerb, surfaceContainerHighest = k.kerb,
        outline = k.onGroundDim, outlineVariant = k.kerb,
        error = k.red, onError = k.onRed, errorContainer = k.red, onErrorContainer = k.onRed,
        inverseSurface = k.bar, inverseOnSurface = k.onBar, inversePrimary = k.amber,
    )
}

val LocalKerb = staticCompositionLocalOf { NightKerb }
val LocalStatusColors = staticCompositionLocalOf { statusFor(NightKerb) }

/** Paint has square ends: corners are reserved for things you hold (pad, dialogs, fields). */
private val KerbShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
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
    val kerb = if (dark) NightKerb else DayKerb
    CompositionLocalProvider(LocalKerb provides kerb, LocalStatusColors provides statusFor(kerb)) {
        MaterialTheme(
            colorScheme = schemeFor(kerb),
            typography = VisionNavTypography,
            shapes = KerbShapes,
            content = content,
        )
    }
}

object VisionNavTheme {
    val status: VisionNavStatusColors
        @Composable get() = LocalStatusColors.current
    val kerb: KerbColors
        @Composable get() = LocalKerb.current
}
