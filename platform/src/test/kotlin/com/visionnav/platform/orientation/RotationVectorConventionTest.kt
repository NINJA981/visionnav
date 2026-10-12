package com.visionnav.platform.orientation

import android.hardware.SensorManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/**
 * End-to-end convention check: a rotation-vector quaternion → the real
 * `SensorManager.getRotationMatrixFromVector` → [OrientationMath]. Guards against a transposed
 * matrix or wrong camera axis, which pure hand-built matrices cannot catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RotationVectorConventionTest {

    /** Quaternion as (x, y, z, w). */
    private data class Q(val x: Double, val y: Double, val z: Double, val w: Double) {
        operator fun times(o: Q) = Q(
            x = w * o.x + x * o.w + y * o.z - z * o.y,
            y = w * o.y - x * o.z + y * o.w + z * o.x,
            z = w * o.z + x * o.y - y * o.x + z * o.w,
            w = w * o.w - x * o.x - y * o.y - z * o.z,
        )
    }

    private fun axisAngle(ax: Double, ay: Double, az: Double, deg: Double): Q {
        val h = Math.toRadians(deg) / 2
        return Q(ax * sin(h), ay * sin(h), az * sin(h), cos(h))
    }

    /** Portrait upright, camera facing [azimuthDeg], pitched down by [pitchDownDeg]. */
    private fun matrixFor(azimuthDeg: Double, pitchDownDeg: Double = 0.0): FloatArray {
        // Start flat face-up (identity), stand the phone up (+90° about world East), optionally
        // pitch it back down, then turn it about world Up (clockwise = negative angle).
        val standUp = axisAngle(1.0, 0.0, 0.0, 90.0 - pitchDownDeg)
        val turn = axisAngle(0.0, 0.0, 1.0, -azimuthDeg)
        val q = turn * standUp
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(
            r,
            floatArrayOf(q.x.toFloat(), q.y.toFloat(), q.z.toFloat(), q.w.toFloat()),
        )
        return r
    }

    @Test
    fun `camera azimuth follows the facing direction`() {
        for (az in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 315.0)) {
            val got = OrientationMath.cameraAzimuthDeg(matrixFor(az))
            assertThat(kotlin.math.abs(OrientationMath.signedDiffDeg(az.toFloat(), got))).isLessThan(0.05f)
        }
    }

    @Test
    fun `tilt and roll from a real rotation vector`() {
        val upright = matrixFor(90.0)
        assertThat(OrientationMath.tiltFromVerticalDeg(upright)).isWithin(0.05f).of(0f)
        assertThat(OrientationMath.rollDeg(upright)).isWithin(0.05f).of(0f)
        val pitched = matrixFor(90.0, pitchDownDeg = 50.0)
        assertThat(OrientationMath.tiltFromVerticalDeg(pitched)).isWithin(0.05f).of(50f)
        assertThat(OrientationMath.cameraAzimuthDeg(pitched)).isWithin(0.05f).of(90f)
        val flat = matrixFor(0.0, pitchDownDeg = 90.0)
        assertThat(OrientationMath.tiltFromVerticalDeg(flat)).isWithin(0.05f).of(90f)
    }
}
