package com.visionnav.platform.haptics

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.announce.HapticPattern
import org.junit.Test

class HapticPatternsTest {

    private val felt = HapticPattern.entries.filter { it != HapticPattern.NONE }
    private fun wf(p: HapticPattern) = HapticPatterns.forPattern(p)!!

    @Test
    fun `none has no waveform and every other pattern has one`() {
        assertThat(HapticPatterns.forPattern(HapticPattern.NONE)).isNull()
        felt.forEach { assertThat(HapticPatterns.forPattern(it)).isNotNull() }
    }

    @Test
    fun `all patterns are distinct`() {
        assertThat(felt.map { wf(it).signature() }.toSet()).hasSize(felt.size)
    }

    @Test
    fun `patterns are distinct by rhythm alone (no amplitude control)`() {
        assertThat(felt.map { wf(it).timingsMs.toList() }.toSet()).hasSize(felt.size)
    }

    @Test
    fun `left and right turns differ in pulse count`() {
        assertThat(wf(HapticPattern.TURN_LEFT).pulseCount).isEqualTo(1)
        assertThat(wf(HapticPattern.TURN_RIGHT).pulseCount).isEqualTo(2)
    }

    @Test
    fun `critical is the strongest and longest and uses alarm attributes`() {
        val critical = wf(HapticPattern.CRITICAL)
        assertThat(critical.critical).isTrue()
        felt.filter { it != HapticPattern.CRITICAL }.forEach {
            assertThat(critical.peakAmplitude).isGreaterThan(wf(it).peakAmplitude)
            assertThat(critical.totalMs).isGreaterThan(wf(it).totalMs)
            assertThat(wf(it).critical).isFalse()
        }
    }

    @Test
    fun `waveforms follow the createWaveform off-on convention`() {
        felt.forEach { p ->
            val w = wf(p)
            assertThat(w.timingsMs.size).isEqualTo(w.amplitudes.size)
            w.amplitudes.forEachIndexed { i, a -> if (i % 2 == 0) assertThat(a).isEqualTo(0) else assertThat(a).isGreaterThan(0) }
        }
    }
}
