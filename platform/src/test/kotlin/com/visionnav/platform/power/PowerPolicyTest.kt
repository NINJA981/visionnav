package com.visionnav.platform.power

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PowerPolicyTest {

    private fun snap(thermal: Int? = null, battery: Int? = 80, charging: Boolean = false, saver: Boolean = false) =
        PowerSnapshot(thermal, battery, charging, saver)

    @Test
    fun `normal conditions`() {
        assertThat(PowerPolicy.decide(snap())).isEqualTo(PerformanceMode.NORMAL)
        assertThat(PowerPolicy.decide(snap(thermal = PowerPolicy.THERMAL_LIGHT))).isEqualTo(PerformanceMode.NORMAL)
        assertThat(PowerPolicy.decide(snap(battery = null))).isEqualTo(PerformanceMode.NORMAL)
    }

    @Test
    fun `thermal status drives the mode`() {
        assertThat(PowerPolicy.decide(snap(thermal = PowerPolicy.THERMAL_MODERATE))).isEqualTo(PerformanceMode.REDUCED)
        assertThat(PowerPolicy.decide(snap(thermal = PowerPolicy.THERMAL_SEVERE))).isEqualTo(PerformanceMode.MINIMAL)
        assertThat(PowerPolicy.decide(snap(thermal = 5, charging = true))).isEqualTo(PerformanceMode.MINIMAL)
    }

    @Test
    fun `battery and saver drive the mode unless charging`() {
        assertThat(PowerPolicy.decide(snap(battery = 18))).isEqualTo(PerformanceMode.REDUCED)
        assertThat(PowerPolicy.decide(snap(battery = 8))).isEqualTo(PerformanceMode.MINIMAL)
        assertThat(PowerPolicy.decide(snap(battery = 8, charging = true))).isEqualTo(PerformanceMode.NORMAL)
        assertThat(PowerPolicy.decide(snap(saver = true))).isEqualTo(PerformanceMode.REDUCED)
    }

    @Test
    fun `most restrictive wins`() {
        assertThat(PowerPolicy.decide(snap(thermal = PowerPolicy.THERMAL_MODERATE, battery = 5)))
            .isEqualTo(PerformanceMode.MINIMAL)
    }

    @Test
    fun `throttle factors`() {
        assertThat(PerformanceMode.NORMAL.inferenceThrottleFactor).isEqualTo(1.0f)
        assertThat(PerformanceMode.REDUCED.inferenceThrottleFactor).isEqualTo(0.6f)
        assertThat(PerformanceMode.MINIMAL.inferenceThrottleFactor).isEqualTo(0.3f)
    }
}
