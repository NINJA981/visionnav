package com.visionnav.core.crossing

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.Reliability
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.sensing.DeviceOrientation
import com.visionnav.core.sensing.OrientationState
import com.visionnav.core.sensing.SensorAccuracy
import org.junit.Test

class CameraOrientationEstimatorTest {

    private fun state(
        t: Long,
        azimuth: Float = 90f,
        tilt: Float = 10f,
        roll: Float = 3f,
        accuracy: SensorAccuracy = SensorAccuracy.HIGH,
        gyro: Float? = 0.05f,
        mag: Float? = 45f,
    ) = OrientationState.Available(DeviceOrientation(azimuth, tilt, roll, accuracy, gyro, 0f, mag, t))

    /** Feeds steady samples every 100 ms from [from] to [to] inclusive and returns the last estimate. */
    private fun CameraOrientationEstimator.feed(from: Long, to: Long, azimuth: (Long) -> Float = { 90f }): CameraOrientationEstimate {
        var last: CameraOrientationEstimate? = null
        var t = from
        while (t <= to) {
            last = estimate(state(t, azimuth(t)))
            t += 100
        }
        return last!!
    }

    @Test
    fun becomesReliableOnlyAfterStabilityWindow() {
        val est = CameraOrientationEstimator()
        val early = est.feed(0, 700)
        assertThat(early.isReliable).isFalse()
        assertThat(early.reasons).contains(UncertaintyReason.ORIENTATION_UNRELIABLE)

        val stable = est.estimate(state(800))
        assertThat(stable.reliability).isEqualTo(Reliability.RELIABLE)
        assertThat(stable.cameraBearingDeg).isWithin(1e-6).of(90.0)
        assertThat(stable.reasons).isEmpty()
    }

    @Test
    fun eachGateBlocksReliability() {
        val all = listOf<Pair<UncertaintyReason, (Long) -> OrientationState>>(
            UncertaintyReason.ORIENTATION_UNRELIABLE to { t -> state(t, tilt = 50f) },
            UncertaintyReason.ORIENTATION_UNRELIABLE to { t -> state(t, roll = -30f) },
            UncertaintyReason.HEADING_UNRELIABLE to { t -> state(t, accuracy = SensorAccuracy.LOW) },
            UncertaintyReason.CAMERA_MOVING to { t -> state(t, gyro = 0.5f) },
            UncertaintyReason.HEADING_UNRELIABLE to { t -> state(t, mag = 90f) },
            UncertaintyReason.HEADING_UNRELIABLE to { t -> state(t, mag = 10f) },
        )
        for ((reason, make) in all) {
            val est = CameraOrientationEstimator()
            var last: CameraOrientationEstimate? = null
            for (t in 0L..2_000L step 100) last = est.estimate(make(t))
            assertThat(last!!.isReliable).isFalse()
            assertThat(last.reliability).isEqualTo(Reliability.LOW)
            assertThat(last.reasons).contains(reason)
        }
    }

    @Test
    fun unreliableAccuracyOrUnavailable_hasNoBearing() {
        val est = CameraOrientationEstimator()
        val unreliable = est.estimate(state(0, accuracy = SensorAccuracy.UNRELIABLE))
        assertThat(unreliable.cameraBearingDeg).isNull()
        assertThat(unreliable.reliability).isEqualTo(Reliability.UNKNOWN)

        val unavailable = est.estimate(OrientationState.Unavailable(UncertaintyReason.HEADING_UNAVAILABLE))
        assertThat(unavailable.cameraBearingDeg).isNull()
        assertThat(unavailable.reasons).containsExactly(UncertaintyReason.HEADING_UNAVAILABLE)
    }

    @Test
    fun swingingAzimuth_isCameraMoving() {
        val est = CameraOrientationEstimator()
        val last = est.feed(0, 3_000) { t -> if ((t / 100) % 2 == 0L) 75f else 105f }
        assertThat(last.isReliable).isFalse()
        assertThat(last.reasons).contains(UncertaintyReason.CAMERA_MOVING)
    }

    @Test
    fun azimuthWrapAroundNorth_isStillStable() {
        val est = CameraOrientationEstimator()
        val last = est.feed(0, 1_000) { t -> if ((t / 100) % 2 == 0L) 359f else 1f }
        assertThat(last.isReliable).isTrue()
    }

    @Test
    fun badSampleOrGap_restartsStabilityWindow() {
        val est = CameraOrientationEstimator()
        est.feed(0, 700)
        est.estimate(state(800, tilt = 60f))
        assertThat(est.feed(900, 1_600).isReliable).isFalse()
        assertThat(est.estimate(state(1_700)).isReliable).isTrue()

        // A long sensor gap also restarts the window.
        assertThat(est.estimate(state(5_000)).isReliable).isFalse()
    }
}
