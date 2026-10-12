package com.visionnav.platform.audio

import android.media.ToneGenerator
import com.visionnav.core.announce.Earcon

/**
 * One tone in an earcon sequence.
 * @param toneType a `ToneGenerator.TONE_*` constant.
 * @param durationMs how long the tone plays.
 * @param gapAfterMs silence after the tone before the next step.
 */
data class ToneStep(val toneType: Int, val durationMs: Int, val gapAfterMs: Int = 0)

/**
 * Earcon designs: short (≤ ~1 s), distinct, recognisable without speech.
 *
 * DTMF tones are used for most steps because they are steady dual-frequency tones with exact,
 * caller-controlled durations; the column frequency (1209 → 1336 → 1477 → 1633 Hz) gives a
 * clear perceived pitch order (1/4 < 2/5 < 3/6 < A/D).
 *
 * | Earcon            | Shape                                              |
 * |-------------------|----------------------------------------------------|
 * | CRITICAL_ALERT    | urgent double-double high (D D · D D)              |
 * | WARNING           | two slower medium-high tones (A · A)               |
 * | TURN              | single medium tone, then a short higher one (5 → 9)|
 * | ARRIVED           | rising three-note chime (1 → 5 → 9, last long)     |
 * | OBSERVATION_START | quick rising pair (4 → 6)                          |
 * | OBSERVATION_END   | quick falling pair (6 → 4)                         |
 * | CONFIRM           | one very short beep                                |
 * | ERROR             | one low "nack" buzz                                |
 */
object EarconPatterns {

    private val PATTERNS: Map<Earcon, List<ToneStep>> = mapOf(
        Earcon.NONE to emptyList(),
        Earcon.CRITICAL_ALERT to listOf(
            ToneStep(ToneGenerator.TONE_DTMF_D, 140, 50),
            ToneStep(ToneGenerator.TONE_DTMF_D, 140, 120),
            ToneStep(ToneGenerator.TONE_DTMF_D, 140, 50),
            ToneStep(ToneGenerator.TONE_DTMF_D, 140, 0),
        ),
        Earcon.WARNING to listOf(
            ToneStep(ToneGenerator.TONE_DTMF_A, 220, 120),
            ToneStep(ToneGenerator.TONE_DTMF_A, 220, 0),
        ),
        Earcon.TURN to listOf(
            ToneStep(ToneGenerator.TONE_DTMF_5, 200, 40),
            ToneStep(ToneGenerator.TONE_DTMF_9, 90, 0),
        ),
        Earcon.ARRIVED to listOf(
            ToneStep(ToneGenerator.TONE_DTMF_1, 120, 40),
            ToneStep(ToneGenerator.TONE_DTMF_5, 120, 40),
            ToneStep(ToneGenerator.TONE_DTMF_9, 260, 0),
        ),
        Earcon.OBSERVATION_START to listOf(
            ToneStep(ToneGenerator.TONE_DTMF_4, 80, 30),
            ToneStep(ToneGenerator.TONE_DTMF_6, 80, 0),
        ),
        Earcon.OBSERVATION_END to listOf(
            ToneStep(ToneGenerator.TONE_DTMF_6, 80, 30),
            ToneStep(ToneGenerator.TONE_DTMF_4, 80, 0),
        ),
        Earcon.CONFIRM to listOf(
            ToneStep(ToneGenerator.TONE_PROP_BEEP, 60, 0),
        ),
        Earcon.ERROR to listOf(
            ToneStep(ToneGenerator.TONE_PROP_NACK, 300, 0),
        ),
    )

    /** Tone sequence for [earcon]; empty for [Earcon.NONE]. */
    fun forEarcon(earcon: Earcon): List<ToneStep> = PATTERNS.getValue(earcon)

    /** Total duration of the sequence in ms (tones + gaps). */
    fun totalDurationMs(earcon: Earcon): Int =
        forEarcon(earcon).sumOf { it.durationMs + it.gapAfterMs }

    /** Relative volume (0–100, `ToneGenerator` units) per earcon; critical is loudest. */
    fun volumeFor(earcon: Earcon): Int = when (earcon) {
        Earcon.CRITICAL_ALERT -> 100
        Earcon.WARNING -> 90
        else -> 75
    }
}
