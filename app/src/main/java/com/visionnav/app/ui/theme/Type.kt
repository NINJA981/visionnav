package com.visionnav.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Large type scale for low vision. Body text is never below 20 sp; the active-navigation
 * instruction uses displaySmall (36 sp) or larger. All sizes are sp so system font scaling
 * (up to 200 %) applies; layouts are scrollable so nothing is clipped.
 */
private val Sans = FontFamily.SansSerif

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Sans,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
)

val VisionNavTypography = Typography(
    displayLarge = style(52, 60, FontWeight.Black),
    displayMedium = style(44, 52, FontWeight.ExtraBold),
    displaySmall = style(36, 44, FontWeight.Bold),
    headlineLarge = style(34, 42, FontWeight.Bold),
    headlineMedium = style(30, 38, FontWeight.Bold),
    headlineSmall = style(26, 34, FontWeight.Bold),
    titleLarge = style(26, 34, FontWeight.SemiBold),
    titleMedium = style(24, 32, FontWeight.SemiBold),
    titleSmall = style(22, 30, FontWeight.SemiBold),
    bodyLarge = style(22, 32, FontWeight.Normal),
    bodyMedium = style(20, 30, FontWeight.Normal),
    bodySmall = style(20, 28, FontWeight.Normal),
    labelLarge = style(22, 28, FontWeight.Bold, 0.2),
    labelMedium = style(20, 26, FontWeight.SemiBold),
    labelSmall = style(20, 26, FontWeight.Medium),
)
