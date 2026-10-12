package com.visionnav.app.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * "Kerb" palette — taken from the street, not from a UI kit.
 *
 * Asphalt is the ground, thermoplastic road paint is the primary mark (zebra bars, text), kerb
 * concrete is the raised secondary surface, and the two signal colours are used only for what
 * they mean on a real street: amber = attention, red = stop. Colour never carries meaning alone;
 * every signal also has words (and, for alerts, an earcon and vibration).
 *
 * Contrast (WCAG 2.x, computed): Paint/Asphalt 13.1:1, PaintDim/Asphalt 8.3:1, Amber/Asphalt 8.5:1,
 * Paint/KerbGrey 9.6:1, AmberDeep/Paint 5.2:1, Paint/SignalRed 4.95:1 (critical text is large and
 * bold, AAA-large). SignalRed on Asphalt is only 2.6:1, so red is a fill, never text on the ground.
 */

/** Wet-asphalt grey. The ground everything sits on (dark theme). */
val Asphalt = Color(0xFF26282B)

/** Slightly lifted asphalt, for pressed states and the text-field well. */
val AsphaltLift = Color(0xFF2F3236)

/** Kerb concrete: raised secondary surfaces (quiet rows, panels). */
val KerbGrey = Color(0xFF3A3D42)

/** Thermoplastic road-marking white: zebra bars and primary text. */
val Paint = Color(0xFFF2F1EC)

/** Worn paint: secondary text. Still AAA on asphalt. */
val PaintDim = Color(0xFFC3C2BC)

/** Signal amber: attention, focus, warnings, the tactile pad. */
val SignalAmber = Color(0xFFFFB81C)

/** Darker amber for text/strokes on light paint backgrounds (4.9:1 on Paint). */
val SignalAmberDeep = Color(0xFF8A5A00)

/** Signal red: critical stop alerts only. */
val SignalRed = Color(0xFFC62A1F)
