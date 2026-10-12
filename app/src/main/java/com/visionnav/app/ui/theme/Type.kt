package com.visionnav.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.visionnav.app.R

/*
 * One family (AURA §04: "one highly readable sans-serif"). Atkinson Hyperlegible Next, designed by
 * the Braille Institute for low-vision readers (distinct I/l/1, O/0, b/d), fits AURA's audience
 * better than the spec's suggested Inter while keeping the same restraint.
 *
 * Scale is built on a 20 sp body with roughly a 1.25 ratio. Everything is sp so system font
 * scaling up to 200 % applies; every screen scrolls, so nothing clips.
 */
@OptIn(ExperimentalTextApi::class)
private fun atkinson(weight: Int) = Font(
    R.font.atkinson_hyperlegible_next,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Atkinson = FontFamily(atkinson(400), atkinson(500), atkinson(600))

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Atkinson,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
)

val VisionNavTypography = Typography(
    // AURA §04: weights 400 / 500 / 600 only. Size carries hierarchy; bold is not a default.
    displayLarge = style(52, 58, FontWeight.SemiBold, -0.5),
    displayMedium = style(44, 50, FontWeight.SemiBold, -0.4),
    displaySmall = style(36, 42, FontWeight.SemiBold, -0.2),
    headlineLarge = style(32, 38, FontWeight.SemiBold),
    headlineMedium = style(28, 34, FontWeight.SemiBold),
    headlineSmall = style(25, 31, FontWeight.SemiBold),
    titleLarge = style(24, 30, FontWeight.Medium),
    titleMedium = style(22, 28, FontWeight.Medium),
    titleSmall = style(20, 26, FontWeight.Medium),
    // Reading text. Never below 20 sp for this low-vision audience (AURA's 14-16 px is for sighted web use).
    bodyLarge = style(21, 31, FontWeight.Normal),
    bodyMedium = style(20, 29, FontWeight.Normal),
    bodySmall = style(20, 28, FontWeight.Normal),
    labelLarge = style(20, 26, FontWeight.Medium),
    labelMedium = style(20, 26, FontWeight.Medium),
    labelSmall = style(20, 26, FontWeight.Normal),
)
