package com.visionnav.core.scene

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.FakeTimeSource
import com.visionnav.core.common.Reliability
import com.visionnav.core.vision.CrosswalkObservation
import org.junit.Test

class CrosswalkPersistenceGateTest {

    private val strong = CrosswalkObservation(score = 0.8f, stripeCount = 5)
    private val weak = CrosswalkObservation(score = 0.4f, stripeCount = 2)

    @Test
    fun `persistent evidence is emitted only after 1200 ms`() {
        val clock = FakeTimeSource()
        val gate = CrosswalkPersistenceGate()
        var result = gate.update(strong, clock.elapsedMs())
        while (clock.elapsedMs() < 1_100) {
            assertThat(result).isNull()
            clock.advance(100)
            result = gate.update(strong, clock.elapsedMs())
        }
        assertThat(result).isNull() // t = 1100
        clock.advance(100)
        val c = gate.update(strong, clock.elapsedMs())!!
        assertThat(c.persistedMs).isEqualTo(1_200L)
        assertThat(c.score).isWithin(1e-6f).of(0.8f)
        assertThat(c.reliability).isEqualTo(Reliability.LOW)
        assertThat(gate.isActive).isTrue()
    }

    @Test
    fun `single spikes never emit`() {
        val gate = CrosswalkPersistenceGate()
        for (i in 0 until 50) {
            val obs = if (i % 10 == 0) strong else null
            assertThat(gate.update(obs, i * 100L)).isNull()
        }
    }

    @Test
    fun `below 70 percent positive frames does not emit, above does`() {
        val twoOfThree = CrosswalkPersistenceGate()
        for (i in 0 until 60) {
            val obs = if (i % 3 == 2) weak else strong // 66 % positive
            assertThat(twoOfThree.update(obs, i * 100L)).isNull()
        }

        val threeOfFour = CrosswalkPersistenceGate()
        var emitted = false
        for (i in 0 until 30) {
            val obs = if (i % 4 == 3) null else strong // 75 % positive
            if (threeOfFour.update(obs, i * 100L) != null) emitted = true
        }
        assertThat(emitted).isTrue()
    }

    @Test
    fun `drops after 2 s without evidence`() {
        val gate = CrosswalkPersistenceGate()
        for (i in 0..15) gate.update(strong, i * 100L) // active from 1200 ms, last positive at 1500
        assertThat(gate.isActive).isTrue()
        var t = 1_600L
        while (t <= 3_500L) {
            assertThat(gate.update(null, t)).isNotNull()
            t += 100
        }
        assertThat(gate.update(null, 3_600)).isNull()
        assertThat(gate.isActive).isFalse()
        // Needs to persist again from scratch.
        assertThat(gate.update(strong, 3_700)).isNull()
    }

    @Test
    fun `a gap before emission restarts persistence`() {
        val gate = CrosswalkPersistenceGate()
        for (i in 0..8) gate.update(strong, i * 100L) // 0..800
        for (i in 9..14) gate.update(null, i * 100L) // 900..1400: 600 ms gap
        var firstEmitAt: Long? = null
        for (i in 15..40) {
            if (gate.update(strong, i * 100L) != null && firstEmitAt == null) firstEmitAt = i * 100L
        }
        assertThat(firstEmitAt).isEqualTo(1_500L + 1_200L)
    }

    @Test
    fun `time going backwards resets the gate`() {
        val gate = CrosswalkPersistenceGate()
        for (i in 0..13) gate.update(strong, 10_000 + i * 100L)
        assertThat(gate.isActive).isTrue()
        assertThat(gate.update(strong, 500)).isNull()
        assertThat(gate.isActive).isFalse()
    }
}
