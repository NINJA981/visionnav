package com.visionnav.platform.haptics

import com.visionnav.core.announce.HapticPattern

/**
 * A vibration waveform.
 * @param timingsMs alternating off/on durations starting with an initial delay (Android
 *   `VibrationEffect.createWaveform` convention: index 0 = off, 1 = on, 2 = off, …).
 * @param amplitudes per-segment amplitude 0–255 (0 for off segments), same length as [timingsMs].
 * @param critical played with alarm-class attributes so it is not suppressed like touch feedback.
 */
class HapticWaveform(
    val timingsMs: LongArray,
    val amplitudes: IntArray,
    val critical: Boolean = false,
) {
    init {
        require(timingsMs.size == amplitudes.size) { "timings and amplitudes must match" }
        require(amplitudes.all { it in 0..255 }) { "amplitudes must be 0..255" }
    }

    /** Number of "on" pulses. */
    val pulseCount: Int get() = timingsMs.indices.count { it % 2 == 1 && timingsMs[it] > 0 }

    /** Total duration in ms. */
    val totalMs: Long get() = timingsMs.sum()

    /** Strongest amplitude used. */
    val peakAmplitude: Int get() = amplitudes.maxOrNull() ?: 0

    /** Stable signature for distinctness checks. */
    fun signature(): String = timingsMs.joinToString(",") + "|" + amplitudes.joinToString(",")
}

/**
 * Haptic designs, distinguishable by rhythm alone (devices without amplitude control play every
 * pulse at full strength, so rhythm — not strength — carries the meaning):
 *
 * | Pattern     | Rhythm                                   | Mnemonic                    |
 * |-------------|------------------------------------------|-----------------------------|
 * | CRITICAL    | 3 long strong pulses (≈1.4 s)            | strongest, longest          |
 * | WARNING     | 2 medium pulses                          |                             |
 * | TURN_LEFT   | 1 long pulse                             | **L**eft = **L**ong         |
 * | TURN_RIGHT  | 2 short quick pulses                     | **R**ight = **R**apid pair  |
 * | ARRIVED     | short-short-long                         | "da-da-daaa"                |
 * | TICK        | one very short light tap                 | confirmation                |
 */
object HapticPatterns {

    private val PATTERNS: Map<HapticPattern, HapticWaveform?> = mapOf(
        HapticPattern.NONE to null,
        HapticPattern.CRITICAL to HapticWaveform(
            timingsMs = longArrayOf(0, 400, 120, 400, 120, 400),
            amplitudes = intArrayOf(0, 255, 0, 255, 0, 255),
            critical = true,
        ),
        HapticPattern.WARNING to HapticWaveform(
            timingsMs = longArrayOf(0, 250, 150, 250),
            amplitudes = intArrayOf(0, 200, 0, 200),
        ),
        HapticPattern.TURN_LEFT to HapticWaveform(
            timingsMs = longArrayOf(0, 500),
            amplitudes = intArrayOf(0, 180),
        ),
        HapticPattern.TURN_RIGHT to HapticWaveform(
            timingsMs = longArrayOf(0, 110, 110, 110),
            amplitudes = intArrayOf(0, 180, 0, 180),
        ),
        HapticPattern.ARRIVED to HapticWaveform(
            timingsMs = longArrayOf(0, 90, 90, 90, 90, 350),
            amplitudes = intArrayOf(0, 150, 0, 150, 0, 170),
        ),
        HapticPattern.TICK to HapticWaveform(
            timingsMs = longArrayOf(0, 30),
            amplitudes = intArrayOf(0, 120),
        ),
    )

    /** Waveform for [pattern]; null for [HapticPattern.NONE]. */
    fun forPattern(pattern: HapticPattern): HapticWaveform? = PATTERNS.getValue(pattern)
}
