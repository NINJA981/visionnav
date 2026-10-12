package com.visionnav.platform.orientation

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.sensing.SensorAccuracy
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hand-built device→world rotation matrices (row-major, world = East/North/Up, columns =
 * device X/Y/Z expressed in world coordinates).
 */
class OrientationMathTest {

    private fun rad(deg: Double) = Math.toRadians(deg)

    /** Matrix from the device axes expressed in world coordinates. */
    private fun fromAxes(x: DoubleArray, y: DoubleArray, z: DoubleArray): FloatArray = floatArrayOf(
        x[0].toFloat(), y[0].toFloat(), z[0].toFloat(),
        x[1].toFloat(), y[1].toFloat(), z[1].toFloat(),
        x[2].toFloat(), y[2].toFloat(), z[2].toFloat(),
    )

    /**
     * Portrait phone, camera looking at compass bearing [azimuthDeg], camera pitched down by
     * [pitchDownDeg] (0 = upright, 90 = flat face-up), no roll.
     */
    private fun pose(azimuthDeg: Double, pitchDownDeg: Double = 0.0): FloatArray {
        val a = rad(azimuthDeg)
        val p = rad(pitchDownDeg)
        // Camera forward (−Z).
        val f = doubleArrayOf(sin(a) * cos(p), cos(a) * cos(p), -sin(p))
        val z = doubleArrayOf(-f[0], -f[1], -f[2])
        // Device X: to the right of the camera's facing, horizontal.
        val x = doubleArrayOf(cos(a), -sin(a), 0.0)
        // Y = Z × X (right-handed: X × Y = Z).
        val y = doubleArrayOf(
            z[1] * x[2] - z[2] * x[1],
            z[2] * x[0] - z[0] * x[2],
            z[0] * x[1] - z[1] * x[0],
        )
        return fromAxes(x, y, z)
    }

    @Test
    fun `upright facing north gives azimuth 0, tilt 0, roll 0`() {
        val r = pose(0.0)
        // Sanity: matches the hand-written matrix for this pose.
        val expected = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        for (i in 0 until 9) assertThat(r[i]).isWithin(1e-6f).of(expected[i])
        assertThat(angleNear(OrientationMath.cameraAzimuthDeg(r), 0f)).isTrue()
        assertThat(OrientationMath.tiltFromVerticalDeg(r)).isWithin(0.01f).of(0f)
        assertThat(OrientationMath.rollDeg(r)).isWithin(0.01f).of(0f)
    }

    @Test
    fun `upright facing east gives azimuth 90`() {
        // Hand-written: X = south, Y = up, Z = west.
        val r = floatArrayOf(
            0f, 0f, -1f,
            -1f, 0f, 0f,
            0f, 1f, 0f,
        )
        assertThat(OrientationMath.cameraAzimuthDeg(r)).isWithin(0.01f).of(90f)
        assertThat(OrientationMath.cameraAzimuthDeg(pose(90.0))).isWithin(0.01f).of(90f)
    }

    @Test
    fun `south and west`() {
        assertThat(OrientationMath.cameraAzimuthDeg(pose(180.0))).isWithin(0.01f).of(180f)
        assertThat(OrientationMath.cameraAzimuthDeg(pose(270.0))).isWithin(0.01f).of(270f)
        assertThat(OrientationMath.cameraAzimuthDeg(pose(33.0, pitchDownDeg = 40.0))).isWithin(0.01f).of(33f)
    }

    @Test
    fun `flat face up has tilt 90`() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertThat(OrientationMath.tiltFromVerticalDeg(identity)).isWithin(0.01f).of(90f)
        // Face down (rotated 180° about Y): camera looks up; still "flat".
        val faceDown = floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f)
        assertThat(OrientationMath.tiltFromVerticalDeg(faceDown)).isWithin(0.01f).of(90f)
    }

    @Test
    fun `pitched down 30 degrees has tilt 30`() {
        assertThat(OrientationMath.tiltFromVerticalDeg(pose(120.0, 30.0))).isWithin(0.01f).of(30f)
    }

    @Test
    fun `roll about the camera axis`() {
        // Upright facing north, rotated 90° clockwise as seen by the user: top of the phone
        // points east, device X points down.
        val r = fromAxes(
            x = doubleArrayOf(0.0, 0.0, -1.0),
            y = doubleArrayOf(1.0, 0.0, 0.0),
            z = doubleArrayOf(0.0, -1.0, 0.0),
        )
        assertThat(OrientationMath.rollDeg(r)).isWithin(0.01f).of(90f)
        // Camera still faces north.
        assertThat(angleNear(OrientationMath.cameraAzimuthDeg(r), 0f)).isTrue()
        // Counter-clockwise: top points west, X points up.
        val ccw = fromAxes(
            x = doubleArrayOf(0.0, 0.0, 1.0),
            y = doubleArrayOf(-1.0, 0.0, 0.0),
            z = doubleArrayOf(0.0, -1.0, 0.0),
        )
        assertThat(OrientationMath.rollDeg(ccw)).isWithin(0.01f).of(-90f)
    }

    @Test
    fun `yaw rate is positive when turning right`() {
        val upright = pose(0.0)
        // Upright, rotating about device +Y (world up) counter-clockwise = turning left.
        assertThat(OrientationMath.yawRateRadPerSec(upright, 0f, 1f, 0f)).isWithin(1e-6f).of(-1f)
        assertThat(OrientationMath.yawRateRadPerSec(upright, 0f, -0.5f, 0f)).isWithin(1e-6f).of(0.5f)
        // Rotation about the camera axis (roll) is not yaw.
        assertThat(OrientationMath.yawRateRadPerSec(upright, 0f, 0f, 2f)).isWithin(1e-6f).of(0f)
        // Flat: device Z is up, so spinning about Z clockwise (negative) turns right.
        val flat = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertThat(OrientationMath.yawRateRadPerSec(flat, 0f, 0f, -0.3f)).isWithin(1e-6f).of(0.3f)
    }

    @Test
    fun `declination is added and wrapped`() {
        assertThat(OrientationMath.applyDeclination(10f, -1.5f)).isWithin(1e-4f).of(8.5f)
        assertThat(OrientationMath.applyDeclination(359f, 2f)).isWithin(1e-4f).of(1f)
        assertThat(OrientationMath.applyDeclination(1f, -3f)).isWithin(1e-4f).of(358f)
    }

    @Test
    fun `low pass crosses the 359 to 0 wrap along the short arc`() {
        val half = OrientationMath.lowPassAngleDeg(359f, 1f, 0.5f)
        assertThat(angleNear(half, 0f)).isTrue()
        val quarter = OrientationMath.lowPassAngleDeg(350f, 10f, 0.25f)
        assertThat(quarter).isWithin(1e-3f).of(355f)
        val other = OrientationMath.lowPassAngleDeg(10f, 350f, 0.25f)
        assertThat(other).isWithin(1e-3f).of(5f)
        assertThat(OrientationMath.lowPassAngleDeg(null, 370f, 0.1f)).isWithin(1e-3f).of(10f)
    }

    @Test
    fun `low pass converges to the sample`() {
        var a: Float? = null
        for (i in 0 until 200) a = OrientationMath.lowPassAngleDeg(a ?: 300f, 20f, 0.25f)
        assertThat(angleNear(a!!, 20f, tol = 0.01f)).isTrue()
    }

    @Test
    fun `circular mean handles wrap-around`() {
        val mean = OrientationMath.circularMeanDeg(listOf(350f, 10f, 355f, 5f))!!
        assertThat(angleNear(mean, 0f, tol = 1e-3f)).isTrue()
        assertThat(OrientationMath.circularMeanDeg(listOf(80f, 100f))!!).isWithin(1e-3f).of(90f)
        assertThat(OrientationMath.circularMeanDeg(emptyList())).isNull()
        assertThat(OrientationMath.circularMeanDeg(listOf(0f, 180f))).isNull()
    }

    @Test
    fun `normalize and signed diff`() {
        assertThat(OrientationMath.normalizeDeg(-90f)).isEqualTo(270f)
        assertThat(OrientationMath.normalizeDeg(720f)).isEqualTo(0f)
        assertThat(OrientationMath.signedDiffDeg(350f, 10f)).isWithin(1e-4f).of(20f)
        assertThat(OrientationMath.signedDiffDeg(10f, 350f)).isWithin(1e-4f).of(-20f)
    }

    @Test
    fun `accuracy mapping and combination`() {
        assertThat(OrientationMath.accuracyFromHeadingErrorRad(Math.toRadians(5.0).toFloat()))
            .isEqualTo(SensorAccuracy.HIGH)
        assertThat(OrientationMath.accuracyFromHeadingErrorRad(Math.toRadians(20.0).toFloat()))
            .isEqualTo(SensorAccuracy.MEDIUM)
        assertThat(OrientationMath.accuracyFromHeadingErrorRad(Math.toRadians(40.0).toFloat()))
            .isEqualTo(SensorAccuracy.LOW)
        assertThat(OrientationMath.accuracyFromHeadingErrorRad(Math.toRadians(90.0).toFloat()))
            .isEqualTo(SensorAccuracy.UNRELIABLE)
        assertThat(OrientationMath.accuracyFromHeadingErrorRad(-1f)).isNull()

        val good = Math.toRadians(5.0).toFloat()
        assertThat(OrientationMath.combineHeadingAccuracy(good, null, null, declinationKnown = true))
            .isEqualTo(SensorAccuracy.HIGH)
        // Unknown declination downgrades one level.
        assertThat(OrientationMath.combineHeadingAccuracy(good, null, null, declinationKnown = false))
            .isEqualTo(SensorAccuracy.MEDIUM)
        // Uncalibrated magnetometer caps the result.
        assertThat(
            OrientationMath.combineHeadingAccuracy(good, null, OrientationMath.STATUS_ACCURACY_LOW, true),
        ).isEqualTo(SensorAccuracy.LOW)
        // No heading estimate: fall back to the sensor status.
        assertThat(
            OrientationMath.combineHeadingAccuracy(-1f, OrientationMath.STATUS_ACCURACY_MEDIUM, null, true),
        ).isEqualTo(SensorAccuracy.MEDIUM)
        // No evidence at all is never presented as good.
        assertThat(OrientationMath.combineHeadingAccuracy(null, null, null, true))
            .isEqualTo(SensorAccuracy.LOW)
        assertThat(OrientationMath.combineHeadingAccuracy(null, OrientationMath.STATUS_UNRELIABLE, null, false))
            .isEqualTo(SensorAccuracy.UNRELIABLE)
    }

    private fun angleNear(actual: Float, expected: Float, tol: Float = 0.01f): Boolean =
        kotlin.math.abs(OrientationMath.signedDiffDeg(expected, actual)) <= tol
}
