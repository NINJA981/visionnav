package com.visionnav.platform.audio

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.announce.Earcon
import org.junit.Test

class EarconPatternsTest {

    private val audible = Earcon.entries.filter { it != Earcon.NONE }

    @Test
    fun `none is silent and every other earcon has a pattern`() {
        assertThat(EarconPatterns.forEarcon(Earcon.NONE)).isEmpty()
        audible.forEach { assertThat(EarconPatterns.forEarcon(it)).isNotEmpty() }
    }

    @Test
    fun `all earcon patterns are distinct`() {
        val signatures = audible.map { EarconPatterns.forEarcon(it) }
        assertThat(signatures.toSet()).hasSize(audible.size)
    }

    @Test
    fun `earcons are short so they never mask traffic sound for long`() {
        audible.forEach { assertThat(EarconPatterns.totalDurationMs(it)).isAtMost(800) }
    }

    @Test
    fun `critical alert is the loudest and has the most pulses`() {
        val critical = EarconPatterns.forEarcon(Earcon.CRITICAL_ALERT)
        audible.filter { it != Earcon.CRITICAL_ALERT }.forEach {
            assertThat(EarconPatterns.volumeFor(Earcon.CRITICAL_ALERT)).isGreaterThan(EarconPatterns.volumeFor(it))
            assertThat(critical.size).isGreaterThan(EarconPatterns.forEarcon(it).size)
        }
    }

    @Test
    fun `observation start and end mirror each other`() {
        assertThat(EarconPatterns.forEarcon(Earcon.OBSERVATION_END).map { it.toneType })
            .isEqualTo(EarconPatterns.forEarcon(Earcon.OBSERVATION_START).map { it.toneType }.reversed())
    }
}
