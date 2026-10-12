package com.visionnav.app.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * AURA palette (see AURA_DESIGN.md §03): a restrained neutral foundation, with status colours
 * used only where they carry meaning. Colour is never the only signal; every status also has words.
 *
 * Accessibility adjustments to the spec, for a low-vision audience (contrast computed, WCAG 2.x):
 *  - Control outlines use BorderStrong #8F8F89 (3.0:1, meets 1.4.11 non-text contrast). The spec's
 *    #DEDED9 (1.3:1) is kept for dividers only, where it is decorative.
 *  - Attention text uses #8A5A00 (5.5:1). The spec's #A66A00 is 4.2:1, below AA for body text.
 *  - Body text never uses TextMuted (#8A8A84, 3.2:1); it uses TextSecondary (5.4:1) or darker.
 */

// Light (default)
val AuraBackground = Color(0xFFF7F7F5)
val AuraSurface = Color(0xFFFFFFFF)
val AuraSurfaceMuted = Color(0xFFF0F0ED)
val AuraBorder = Color(0xFFDEDED9)
val AuraBorderStrong = Color(0xFF8F8F89)
val AuraText = Color(0xFF171717)
val AuraTextSecondary = Color(0xFF666661)
val AuraTextMuted = Color(0xFF8A8A84)

val AuraSafe = Color(0xFF2E7D5B)
val AuraAttention = Color(0xFF8A5A00)
val AuraRisk = Color(0xFFB42318)
val AuraInfo = Color(0xFF2856A3)

// Dark (optional, §38): same language, no pure black, semantics preserved.
val AuraDarkBackground = Color(0xFF1B1B1A)
val AuraDarkSurface = Color(0xFF242423)
val AuraDarkSurfaceMuted = Color(0xFF2E2E2C)
val AuraDarkBorder = Color(0xFF3A3A37)
val AuraDarkBorderStrong = Color(0xFF6A6A65)
val AuraDarkText = Color(0xFFF2F2EF)
val AuraDarkTextSecondary = Color(0xFFA9A9A3)

val AuraDarkSafe = Color(0xFF5FBF8F)
val AuraDarkAttention = Color(0xFFE0A23A)
val AuraDarkRiskText = Color(0xFFF07A6E)
val AuraDarkInfo = Color(0xFF8FB4F0)
