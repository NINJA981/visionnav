package com.visionnav.core.motion

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RobustStatsTest {

    @Test
    fun `median of odd and even lists`() {
        assertThat(RobustStats.median(listOf(3.0, 1.0, 2.0))).isEqualTo(2.0)
        assertThat(RobustStats.median(listOf(4.0, 1.0, 2.0, 3.0))).isEqualTo(2.5)
        assertThat(RobustStats.median(emptyList())).isNull()
    }

    @Test
    fun `theil-sen recovers an exact line`() {
        val xs = DoubleArray(10) { it.toDouble() }
        val ys = DoubleArray(10) { 2.0 * it + 1.0 }
        val fit = RobustStats.theilSen(xs, ys)!!
        assertThat(fit.slope).isWithin(1e-9).of(2.0)
        assertThat(fit.intercept).isWithin(1e-9).of(1.0)
        assertThat(fit.residualMad).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `theil-sen ignores a gross outlier`() {
        val xs = DoubleArray(11) { it.toDouble() }
        val ys = DoubleArray(11) { 0.5 * it }
        ys[5] = 100.0
        assertThat(RobustStats.theilSen(xs, ys)!!.slope).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `theil-sen needs two distinct x values`() {
        assertThat(RobustStats.theilSen(doubleArrayOf(1.0, 1.0), doubleArrayOf(0.0, 1.0))).isNull()
    }

    @Test
    fun `step signs honour the deadband`() {
        val s = RobustStats.stepSigns(doubleArrayOf(0.0, 0.1, 0.1005, 0.0, 0.2), deadband = 0.01)
        assertThat(s.positive).isEqualTo(2)
        assertThat(s.negative).isEqualTo(1)
        assertThat(s.agreementWith(1.0)).isWithin(1e-9).of(2.0 / 3)
        assertThat(RobustStats.StepSigns(0, 0).positiveFraction).isEqualTo(0.0)
    }
}
