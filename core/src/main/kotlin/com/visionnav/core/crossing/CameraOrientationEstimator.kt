package com.visionnav.core.crossing

import com.visionnav.core.common.Reliability
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.sensing.DeviceOrientation
import com.visionnav.core.sensing.OrientationState
import com.visionnav.core.sensing.SensorAccuracy
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Gates for [CameraOrientationEstimator]. Defaults follow docs/PLAN.md §4.4. */
data class CameraOrientationConfig(
    val minAccuracy: SensorAccuracy = SensorAccuracy.MEDIUM,
    val maxTiltFromVerticalDeg: Float = 35f,
    val maxAbsRollDeg: Float = 25f,
    val maxGyroRateRadPerSec: Float = 0.35f,
    /** Earth's field is ~25–65 µT; outside this band the compass is likely disturbed (cars, metal). */
    val minMagneticFieldUt: Float = 20f,
    val maxMagneticFieldUt: Float = 70f,
    /** Orientation must pass every gate continuously for this long before it is RELIABLE. */
    val stabilityWindowMs: Long = 800,
    /** Circular standard deviation of azimuth over the stability window must stay below this. */
    val maxAzimuthStdDevDeg: Double = 8.0,
    /** A gap between samples longer than this restarts the stability window. */
    val maxSampleGapMs: Long = 500,
)

/**
 * Decides whether image-left/right can be mapped to the user's world-left/right.
 *
 * Camera bearing = device azimuth for the rear camera with the phone upright in portrait
 * (see [DeviceOrientation.azimuthDeg]). The estimate is [Reliability.RELIABLE] only when the
 * heading sensor is accurate, the phone is near-upright, the gyro is steady, the magnetic field
 * looks like Earth's, and all of that has held for [CameraOrientationConfig.stabilityWindowMs]
 * with a steady azimuth. Anything less produces LOW/UNKNOWN with reasons, and downstream wording
 * falls back to image-space ("in the camera view").
 *
 * Read-only with respect to sensors: it never writes navigation heading.
 * Stateful (stability window) and not thread-safe; feed it from one thread. Timestamps come from
 * [DeviceOrientation.elapsedMs], so it is deterministic under test.
 */
class CameraOrientationEstimator(private val config: CameraOrientationConfig = CameraOrientationConfig()) {

    private data class Sample(val t: Long, val azimuthDeg: Double)

    private val window = ArrayDeque<Sample>()
    private var runStartMs: Long? = null

    fun estimate(state: OrientationState): CameraOrientationEstimate {
        val o = when (state) {
            is OrientationState.Unavailable -> {
                reset()
                return CameraOrientationEstimate(null, Reliability.UNKNOWN, setOf(UncertaintyReason.HEADING_UNAVAILABLE))
            }
            is OrientationState.Available -> state.orientation
        }
        if (!o.azimuthDeg.isFinite()) {
            reset()
            return CameraOrientationEstimate(null, Reliability.UNKNOWN, setOf(UncertaintyReason.HEADING_UNAVAILABLE))
        }
        val bearing = GeoMath.normalizeDeg(o.azimuthDeg.toDouble())
        val problems = instantaneousProblems(o)
        if (problems.isNotEmpty()) {
            reset()
            return if (o.accuracy == SensorAccuracy.UNRELIABLE) {
                CameraOrientationEstimate(null, Reliability.UNKNOWN, problems)
            } else {
                CameraOrientationEstimate(bearing, Reliability.LOW, problems)
            }
        }

        val t = o.elapsedMs
        val last = window.lastOrNull()
        if (last != null && (t < last.t || t - last.t > config.maxSampleGapMs)) reset()
        val start = runStartMs ?: t.also { runStartMs = it }
        window.addLast(Sample(t, bearing))
        while (window.size > 1 && window.first().t < t - config.stabilityWindowMs) window.removeFirst()

        val std = circularStdDevDeg(window.map { it.azimuthDeg })
        return when {
            std >= config.maxAzimuthStdDevDeg ->
                CameraOrientationEstimate(bearing, Reliability.LOW, setOf(UncertaintyReason.CAMERA_MOVING))
            t - start < config.stabilityWindowMs ->
                CameraOrientationEstimate(bearing, Reliability.LOW, setOf(UncertaintyReason.ORIENTATION_UNRELIABLE))
            else -> CameraOrientationEstimate(bearing, Reliability.RELIABLE, emptySet())
        }
    }

    /** Forget the stability window (e.g. when observation restarts). */
    fun reset() {
        window.clear()
        runStartMs = null
    }

    private fun instantaneousProblems(o: DeviceOrientation): Set<UncertaintyReason> = buildSet {
        if (o.accuracy.ordinal < config.minAccuracy.ordinal) add(UncertaintyReason.HEADING_UNRELIABLE)
        if (!o.tiltFromVerticalDeg.isFinite() || abs(o.tiltFromVerticalDeg) > config.maxTiltFromVerticalDeg) {
            add(UncertaintyReason.ORIENTATION_UNRELIABLE)
        }
        if (!o.rollDeg.isFinite() || abs(o.rollDeg) > config.maxAbsRollDeg) add(UncertaintyReason.ORIENTATION_UNRELIABLE)
        o.gyroRateRadPerSec?.let { if (!it.isFinite() || abs(it) > config.maxGyroRateRadPerSec) add(UncertaintyReason.CAMERA_MOVING) }
        o.magneticFieldUt?.let {
            if (!it.isFinite() || it < config.minMagneticFieldUt || it > config.maxMagneticFieldUt) {
                add(UncertaintyReason.HEADING_UNRELIABLE)
            }
        }
    }

    internal companion object {
        fun circularStdDevDeg(anglesDeg: List<Double>): Double {
            if (anglesDeg.size < 2) return 0.0
            var s = 0.0
            var c = 0.0
            for (a in anglesDeg) {
                s += sin(a * PI / 180.0)
                c += cos(a * PI / 180.0)
            }
            val r = sqrt(s * s + c * c) / anglesDeg.size
            if (r >= 1.0) return 0.0
            if (r <= 1e-12) return 180.0
            return sqrt(-2.0 * ln(r)) * 180.0 / PI
        }
    }
}
