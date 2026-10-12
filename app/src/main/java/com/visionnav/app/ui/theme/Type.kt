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
 * One family: Atkinson Hyperlegible Next, designed by the Braille Institute for low-vision readers
 * (distinct letterforms for I/l/1, O/0, b/d). Hierarchy comes from weight and size only.
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

val Atkinson = FontFamily(atkinson(400), atkinson(500), atkinson(600), atkinson(700), atkinson(800))

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Atkinson,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
)

val VisionNavTypography = Typography(
    // Spoken-instruction scale: the words you would read off a street sign.
    displayLarge = style(56, 60, FontWeight.ExtraBold, -0.5),
    displayMedium = style(46, 50, FontWeight.ExtraBold, -0.4),
    displaySmall = style(38, 44, FontWeight.ExtraBold, -0.2),
    // Zebra-bar labels and screen titles.
    headlineLarge = style(34, 40, FontWeight.ExtraBold),
    headlineMedium = style(30, 36, FontWeight.Bold),
    headlineSmall = style(26, 32, FontWeight.Bold),
    titleLarge = style(26, 32, FontWeight.Bold),
    titleMedium = style(23, 30, FontWeight.Bold),
    titleSmall = style(21, 28, FontWeight.SemiBold),
    // Reading text. Never below 20 sp.
    bodyLarge = style(22, 32, FontWeight.Normal),
    bodyMedium = style(20, 29, FontWeight.Normal),
    bodySmall = style(20, 28, FontWeight.Normal),
    labelLarge = style(22, 28, FontWeight.Bold),
    labelMedium = style(20, 26, FontWeight.SemiBold),
    labelSmall = style(20, 26, FontWeight.Medium),
)
