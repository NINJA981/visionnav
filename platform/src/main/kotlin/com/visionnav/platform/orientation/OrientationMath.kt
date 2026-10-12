package com.visionnav.platform.orientation

import com.visionnav.core.sensing.SensorAccuracy
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure orientation math (no Android dependencies) used by [SensorOrientationSource].
 *
 * ## Frames
 * - **Device frame** (Android sensor convention): +X to the right of the screen, +Y towards the
 *   top of the screen, +Z out of the screen towards the user. The **rear camera looks along −Z**.
 * - **World frame** (as produced by `SensorManager.getRotationMatrixFromVector`): +X East,
 *   +Y North (magnetic), +Z Up.
 * - The rotation matrix `r` is a row-major `FloatArray(9)` mapping device → world:
 *   `world = R · device`. Column *j* is device axis *j* expressed in world coordinates.
 *
 * All angles returned are degrees unless the name says otherwise.
 */
object OrientationMath {

    private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()
    private const val DEG_TO_RAD = (Math.PI / 180.0).toFloat()

    /**
     * Unit vector, in world coordinates (east, north, up), along which the rear camera looks:
     * `v = R · (0, 0, −1)` = minus the third column of R.
     */
    fun cameraForwardWorld(r: FloatArray): FloatArray {
        require(r.size >= 9) { "rotation matrix must have 9 elements" }
        return floatArrayOf(-r[2], -r[5], -r[8])
    }

    /**
     * Compass azimuth of the camera's line of sight, relative to the north reference of the
     * matrix (magnetic north for the rotation-vector sensor), in [0, 360).
     *
     * When the phone is near flat the horizontal component of the camera axis shrinks and the
     * value becomes ill-defined/noisy; it is still returned and [tiltFromVerticalDeg] tells the
     * consumer how much to trust it.
     */
    fun cameraAzimuthDeg(r: FloatArray): Float {
        val v = cameraForwardWorld(r)
        return normalizeDeg(atan2(v[0], v[1]) * RAD_TO_DEG)
    }

    /**
     * Angle between the camera axis (−Z) and the horizontal plane, in [0, 90]:
     * 0 = phone upright with the camera looking at the horizon, 90 = phone flat (face up or down).
     * Equivalently the angle of the screen plane from vertical.
     */
    fun tiltFromVerticalDeg(r: FloatArray): Float {
        val up = cameraForwardWorld(r)[2].coerceIn(-1f, 1f)
        return asin(abs(up)) * RAD_TO_DEG
    }

    /**
     * Rotation of the phone about the camera axis, in (−180, 180]: 0 = portrait upright (top of
     * the phone pointing up), positive = rotated clockwise as seen by the user looking at the
     * screen (top of the phone leaning to the right), ±90 = landscape. Ill-defined (and returned
     * as computed, near 0) when the phone is flat.
     */
    fun rollDeg(r: FloatArray): Float {
        val xUp = r[6] // device +X, up component
        val yUp = r[7] // device +Y, up component
        return atan2(-xUp, yUp) * RAD_TO_DEG
    }

    /**
     * Signed rate at which the camera's facing direction turns about the world vertical (gravity)
     * axis, in rad/s. Positive = turning right (clockwise seen from above, azimuth increasing).
     *
     * @param gx, gy, gz gyroscope angular velocity in the device frame (rad/s, right-handed,
     *   counter-clockwise positive), as reported by `TYPE_GYROSCOPE`.
     */
    fun yawRateRadPerSec(r: FloatArray, gx: Float, gy: Float, gz: Float): Float {
        // World-frame angular velocity = R · ω_device; take the Up component.
        val omegaUp = r[6] * gx + r[7] * gy + r[8] * gz
        // Counter-clockwise about Up decreases azimuth, so negate for "positive = right".
        return -omegaUp
    }

    /** Euclidean norm of a 3-vector (gyro rate, magnetic field magnitude). */
    fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    /** Normalises any angle to [0, 360). */
    fun normalizeDeg(deg: Float): Float {
        if (!deg.isFinite()) return deg
        val n = deg % 360f
        val r = if (n < 0f) n + 360f else n
        return if (r >= 360f) 0f else r
    }

    /** Signed smallest difference `to − from` in (−180, 180]. */
    fun signedDiffDeg(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /**
     * Converts a magnetic azimuth to a true azimuth: `true = magnetic + declination`
     * (declination positive east, as returned by `GeomagneticField.getDeclination()`).
     */
    fun applyDeclination(magneticAzimuthDeg: Float, declinationDeg: Float): Float =
        normalizeDeg(magneticAzimuthDeg + declinationDeg)

    /**
     * One step of a circular exponential low-pass filter. Moves [previousDeg] towards [sampleDeg]
     * along the shortest arc, so 359° → 1° moves through 0°, never through 180°.
     *
     * @param alpha weight of the new sample in (0, 1]; 1 = no smoothing.
     * @return the new filtered angle in [0, 360); [sampleDeg] (normalised) if there is no previous.
     */
    fun lowPassAngleDeg(previousDeg: Float?, sampleDeg: Float, alpha: Float): Float {
        require(alpha > 0f && alpha <= 1f) { "alpha must be in (0, 1]" }
        if (previousDeg == null || !previousDeg.isFinite()) return normalizeDeg(sampleDeg)
        return normalizeDeg(previousDeg + alpha * signedDiffDeg(previousDeg, sampleDeg))
    }

    /**
     * Circular mean of [anglesDeg] (unit-vector averaging), in [0, 360).
     * @return null when the list is empty or the angles cancel out (no meaningful mean).
     */
    fun circularMeanDeg(anglesDeg: List<Float>): Float? {
        if (anglesDeg.isEmpty()) return null
        var s = 0.0
        var c = 0.0
        for (a in anglesDeg) {
            val rad = a * DEG_TO_RAD
            s += sin(rad)
            c += cos(rad)
        }
        if (s * s + c * c < 1e-12 * anglesDeg.size * anglesDeg.size) return null
        return normalizeDeg((atan2(s, c) * RAD_TO_DEG).toFloat())
    }

    // ---------------------------------------------------------------------------------------
    // Accuracy
    // ---------------------------------------------------------------------------------------

    /** Android `SENSOR_STATUS_*` values (duplicated so this file stays JVM-pure). */
    const val STATUS_NO_CONTACT = -1
    const val STATUS_UNRELIABLE = 0
    const val STATUS_ACCURACY_LOW = 1
    const val STATUS_ACCURACY_MEDIUM = 2
    const val STATUS_ACCURACY_HIGH = 3

    /** Maps an Android sensor status (`onAccuracyChanged`) to [SensorAccuracy]. */
    fun accuracyFromStatus(status: Int): SensorAccuracy = when (status) {
        STATUS_ACCURACY_HIGH -> SensorAccuracy.HIGH
        STATUS_ACCURACY_MEDIUM -> SensorAccuracy.MEDIUM
        STATUS_ACCURACY_LOW -> SensorAccuracy.LOW
        else -> SensorAccuracy.UNRELIABLE
    }

    /**
     * Maps the rotation-vector sensor's estimated heading accuracy (`values[4]`, radians,
     * one-sigma-ish) to [SensorAccuracy]: ≤ 10° HIGH, ≤ 25° MEDIUM, ≤ 45° LOW, else UNRELIABLE.
     * @return null when the sensor does not report it (negative / non-finite).
     */
    fun accuracyFromHeadingErrorRad(errorRad: Float): SensorAccuracy? {
        if (!errorRad.isFinite() || errorRad < 0f) return null
        val deg = errorRad * RAD_TO_DEG
        return when {
            deg <= 10f -> SensorAccuracy.HIGH
            deg <= 25f -> SensorAccuracy.MEDIUM
            deg <= 45f -> SensorAccuracy.LOW
            else -> SensorAccuracy.UNRELIABLE
        }
    }

    /**
     * Combines all accuracy evidence into one conservative heading accuracy.
     *
     * - Base: the rotation vector's estimated heading accuracy if reported, otherwise its
     *   `onAccuracyChanged` status, otherwise LOW (unknown is never presented as good).
     * - Capped by the magnetometer's calibration status when known (an uncalibrated magnetometer
     *   makes the fused heading suspect regardless of what the fusion estimate claims).
     * - Downgraded one level when the magnetic declination is unknown (azimuth is then relative
     *   to magnetic, not true, north).
     */
    fun combineHeadingAccuracy(
        headingErrorRad: Float?,
        rotationStatus: Int?,
        magnetometerStatus: Int?,
        declinationKnown: Boolean,
    ): SensorAccuracy {
        var acc = headingErrorRad?.let(::accuracyFromHeadingErrorRad)
            ?: rotationStatus?.let(::accuracyFromStatus)
            ?: SensorAccuracy.LOW
        if (magnetometerStatus != null) acc = minOf(acc, accuracyFromStatus(magnetometerStatus))
        if (!declinationKnown) acc = downgrade(acc)
        return acc
    }

    /** One level lower accuracy (UNRELIABLE stays UNRELIABLE). */
    fun downgrade(accuracy: SensorAccuracy): SensorAccuracy =
        SensorAccuracy.entries[(accuracy.ordinal - 1).coerceAtLeast(0)]
}
