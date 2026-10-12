package com.visionnav.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.visionnav.app.settings.ThemeMode

/**
 * AURA tokens (AURA_DESIGN.md §42). Every screen draws with these; no arbitrary colours in components.
 *
 * [primary] is the solid neutral used for the one main action on a screen (§28). Status colours
 * ([safe], [attention], [risk], [info]) appear only where the user needs to know a state.
 */
@Immutable
data class AuraColors(
    val background: Color,
    val surface: Color,
    val surfaceMuted: Color,
    /** Dividers only (decorative). */
    val border: Color,
    /** Outlines of interactive controls (≥ 3:1). */
    val borderStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val primary: Color,
    val onPrimary: Color,
    val safe: Color,
    /** Attention as text/icon colour. */
    val attention: Color,
    /** Risk as a fill (with [onRisk] text). */
    val risk: Color,
    val onRisk: Color,
    /** Risk as text on [background]. */
    val riskText: Color,
    val info: Color,
    val isDark: Boolean,
)

private val LightAura = AuraColors(
    background = AuraBackground, surface = AuraSurface, surfaceMuted = AuraSurfaceMuted,
    border = AuraBorder, borderStrong = AuraBorderStrong,
    text = AuraText, textSecondary = AuraTextSecondary,
    primary = AuraText, onPrimary = AuraSurface,
    safe = AuraSafe, attention = AuraAttention,
    risk = AuraRisk, onRisk = AuraSurface, riskText = AuraRisk,
    info = AuraInfo,
    isDark = false,
)

private val DarkAura = AuraColors(
    background = AuraDarkBackground, surface = AuraDarkSurface, surfaceMuted = AuraDarkSurfaceMuted,
    border = AuraDarkBorder, borderStrong = AuraDarkBorderStrong,
    text = AuraDarkText, textSecondary = AuraDarkTextSecondary,
    primary = AuraDarkText, onPrimary = AuraDarkBackground,
    safe = AuraDarkSafe, attention = AuraDarkAttention,
    risk = AuraRisk, onRisk = AuraSurface, riskText = AuraDarkRiskText,
    info = AuraDarkInfo,
    isDark = true,
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

private fun statusFor(a: AuraColors) = VisionNavStatusColors(
    critical = a.risk, onCritical = a.onRisk,
    warning = a.attention, onWarning = a.surface,
    info = a.info, onInfo = a.surface,
    good = a.safe, degraded = a.attention, bad = a.riskText,
)

private fun schemeFor(a: AuraColors): ColorScheme {
    val base = if (a.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = a.primary, onPrimary = a.onPrimary,
        primaryContainer = a.surfaceMuted, onPrimaryContainer = a.text,
        secondary = a.textSecondary, onSecondary = a.surface,
        secondaryContainer = a.surfaceMuted, onSecondaryContainer = a.text,
        tertiary = a.info, onTertiary = a.surface,
        background = a.background, onBackground = a.text,
        surface = a.surface, onSurface = a.text,
        surfaceVariant = a.surfaceMuted, onSurfaceVariant = a.textSecondary,
        surfaceContainerLowest = a.surface, surfaceContainerLow = a.surface,
        surfaceContainer = a.surface, surfaceContainerHigh = a.surfaceMuted, surfaceContainerHighest = a.surfaceMuted,
        outline = a.borderStrong, outlineVariant = a.border,
        error = a.riskText, onError = a.surface, errorContainer = a.risk, onErrorContainer = a.onRisk,
        inverseSurface = a.text, inverseOnSurface = a.background, inversePrimary = a.surface,
    )
}

val LocalAura = staticCompositionLocalOf { LightAura }
val LocalStatusColors = staticCompositionLocalOf { statusFor(LightAura) }

/** §06: restrained corners. Controls 8, cards 12, large surfaces 16. */
private val AuraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun VisionNavTheme(
    themeMode: ThemeMode = ThemeMode.LIGHT,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val aura = if (dark) DarkAura else LightAura
    // Status/navigation bar icons follow the app theme, not the system's (the user may pick light on a dark system).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalAura provides aura, LocalStatusColors provides statusFor(aura)) {
        MaterialTheme(
            colorScheme = schemeFor(aura),
            typography = VisionNavTypography,
            shapes = AuraShapes,
            content = content,
        )
    }
}

object VisionNavTheme {
    val status: VisionNavStatusColors
        @Composable get() = LocalStatusColors.current
    val aura: AuraColors
        @Composable get() = LocalAura.current
}
