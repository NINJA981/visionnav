package com.visionnav.core.motion

import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.tracking.ApproachState
import com.visionnav.core.tracking.ImageMotion
import com.visionnav.core.tracking.MotionEstimate
import com.visionnav.core.tracking.Track
import com.visionnav.core.tracking.TrackSample
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Thresholds for [MotionEstimator] (PLAN §4.2–4.3). Lateral quantities are in image widths,
 * vertical ones in image heights, rates per second.
 *
 * @param minObservations / [minDurationMs] below either, the track is too short to classify.
 * @param maxWindowMs only the most recent part of the valid history is used.
 * @param minLateralSpeed |Theil–Sen slope of centre-x| needed to call lateral motion.
 * @param minNetDisplacement |last − first| ego-compensated centre-x needed to call lateral motion.
 * @param minSignAgreement fraction of moving steps that must agree with the slope direction.
 * @param lateralStepDeadband steps smaller than this are ignored when counting signs.
 * @param maxStationaryResidual robust residual spread (widths) above which a low-slope track is
 *   too noisy to be called stationary.
 * @param maxYawRateRadPerSec any |yaw| above this in the window ⇒ UNKNOWN(CAMERA_MOVING): the
 *   compensation is a small-angle approximation and gyro/frame timing error grows with rate.
 * @param horizontalFovDeg default HFOV of the analysed image; can be overridden per call.
 * @param noGyroConfidenceFactor confidence multiplier when no yaw data is available at all (camera
 *   motion could not be ruled out).
 * @param minApproachSignAgreement sign agreement required for both growth and bottom-edge descent.
 * @param minScaleGrowthRate minimum log-height growth per second for APPROACHING / MOVING_AWAY.
 * @param minBottomDescentRate minimum bottom-edge descent (image heights/s) for APPROACHING.
 * @param lateralDominanceFactor lateral motion (object widths/s) is dominant when it exceeds this
 *   multiple of |log-height rate|.
 * @param crossingMaxScaleRate |log-height rate| at or below which scale counts as stable for CROSSING.
 * @param stationaryMaxScaleRate / [stationaryMaxBottomRate] ceilings for STATIONARY.
 */
data class MotionConfig(
    val minObservations: Int = 6,
    val minDurationMs: Long = 600,
    val maxWindowMs: Long = 3_000,
    val minLateralSpeed: Float = 0.08f,
    val minNetDisplacement: Float = 0.06f,
    val minSignAgreement: Float = 0.75f,
    val lateralStepDeadband: Float = 0.002f,
    val maxStationaryResidual: Float = 0.03f,
    val maxYawRateRadPerSec: Float = 0.35f,
    val horizontalFovDeg: Float = 66f,
    val noGyroConfidenceFactor: Float = 0.6f,
    val minApproachSignAgreement: Float = 0.7f,
    val minScaleGrowthRate: Float = 0.1f,
    val minBottomDescentRate: Float = 0.02f,
    val scaleStepDeadband: Float = 0.005f,
    val bottomStepDeadband: Float = 0.001f,
    val lateralDominanceFactor: Float = 2.0f,
    val crossingMaxScaleRate: Float = 0.15f,
    val stationaryMaxScaleRate: Float = 0.05f,
    val stationaryMaxBottomRate: Float = 0.015f,
) {
    init {
        require(minObservations >= 2) { "minObservations must be >= 2" }
        require(horizontalFovDeg > 0f) { "horizontalFovDeg must be > 0" }
    }
}

/**
 * Classifies image-space lateral motion and radial (approach) motion for one track, from its
 * **valid** history only (samples before [Track.historyValidFromMs] belong to a possibly different
 * object and are ignored).
 *
 * Safety-relevant choices:
 * - Every doubtful case returns an explicit UNKNOWN with [UncertaintyReason]s; nothing is guessed.
 * - **Ego-motion:** a camera yawing right by ω makes a static object drift left by ≈ ω·Δt/HFOV
 *   (small-angle, pinhole centre approximation). That shift is added back per step before fitting.
 *   Above [MotionConfig.maxYawRateRadPerSec] the approximation is not trusted ⇒ CAMERA_MOVING.
 *   Pitch is not compensated (no pitch rate in the contract); the bottom-edge/height cues therefore
 *   also require the yaw gate and consistent signs.
 * - **Approach** requires BOTH log-height growth and bottom-edge descent, consistently. Box growth
 *   alone (e.g. detector box jitter, a person standing up, a partially occluded object emerging)
 *   stays UNKNOWN. No metres or time-to-collision are ever computed.
 * - Output describes the **camera view** only; mapping to the user's world left/right is a
 *   separate, reliability-gated step elsewhere.
 *
 * Stateless and thread-safe.
 */
class MotionEstimator(val config: MotionConfig = MotionConfig()) {

    /**
     * @param nowMs current monotonic time; samples after it are ignored and the window ends here.
     * @param horizontalFovDeg HFOV reported by the camera for this session, if known.
     */
    fun estimate(track: Track, nowMs: Long, horizontalFovDeg: Float? = null): MotionEstimate {
        val windowStart = nowMs - config.maxWindowMs
        val samples = track.validHistory.filter { it.elapsedMs in windowStart..nowMs }
        val n = samples.size
        val span = if (n >= 2) samples.last().elapsedMs - samples.first().elapsedMs else 0L

        fun unknown(vararg reasons: UncertaintyReason, lateral: Float? = null, scale: Float? = null) = MotionEstimate(
            trackId = track.id,
            objectClass = track.objectClass,
            imageMotion = ImageMotion.UNKNOWN,
            approach = ApproachState.UNKNOWN,
            confidence = 0f,
            lateralSpeed = lateral,
            scaleGrowthRate = scale,
            observationCount = n,
            observationSpanMs = span,
            reasons = reasons.toSet(),
        )

        val tooShort = n < config.minObservations || span < config.minDurationMs
        if (tooShort) {
            return if (track.identitySuspect) unknown(UncertaintyReason.IDENTITY_SWITCH)
            else unknown(UncertaintyReason.TRACK_TOO_SHORT)
        }

        val maxAbsYaw = samples.mapNotNull { it.cameraYawRateRadPerSec?.takeIf(Float::isFinite) }.maxOfOrNull { abs(it) }
        if (maxAbsYaw != null && maxAbsYaw > config.maxYawRateRadPerSec) return unknown(UncertaintyReason.CAMERA_MOVING)

        val hfovRad = Math.toRadians((horizontalFovDeg?.takeIf { it > 0f } ?: config.horizontalFovDeg).toDouble())
        val t0 = samples.first().elapsedMs
        val ts = DoubleArray(n) { (samples[it].elapsedMs - t0) / 1000.0 }

        // Ego-motion compensated centre-x: cumulative sum of compensated steps.
        val xs = DoubleArray(n)
        xs[0] = samples[0].box.centerX.toDouble()
        var compensatedSteps = 0
        for (i in 1 until n) {
            val dt = ts[i] - ts[i - 1]
            val raw = (samples[i].box.centerX - samples[i - 1].box.centerX).toDouble()
            val yaw = stepYaw(samples[i - 1], samples[i])
            val shift = if (yaw != null) {
                compensatedSteps++
                yaw * dt / hfovRad // add back the leftward drift caused by turning right
            } else {
                0.0
            }
            xs[i] = xs[i - 1] + raw + shift
        }
        val gyroCoverage = compensatedSteps.toDouble() / (n - 1)
        val gyroFactor = config.noGyroConfidenceFactor + (1 - config.noGyroConfidenceFactor) * gyroCoverage

        val logH = DoubleArray(n) { ln(max(samples[it].box.height.toDouble(), 1e-4)) }
        val bottoms = DoubleArray(n) { samples[it].box.bottom.toDouble() }
        val meanWidth = samples.map { it.box.width.toDouble() }.average().coerceAtLeast(0.01)

        val xFit = RobustStats.theilSen(ts, xs) ?: return unknown(UncertaintyReason.TRACK_TOO_SHORT)
        val hFit = RobustStats.theilSen(ts, logH) ?: return unknown(UncertaintyReason.TRACK_TOO_SHORT)
        val bFit = RobustStats.theilSen(ts, bottoms) ?: return unknown(UncertaintyReason.TRACK_TOO_SHORT)

        val reasons = LinkedHashSet<UncertaintyReason>()

        // ---- lateral -------------------------------------------------------------------------
        val slope = xFit.slope
        val net = xs.last() - xs.first()
        val xSigns = RobustStats.stepSigns(xs, config.lateralStepDeadband.toDouble())
        val agreement = xSigns.agreementWith(slope)
        val significant = abs(slope) >= config.minLateralSpeed || abs(net) >= config.minNetDisplacement
        val consistent = abs(slope) >= config.minLateralSpeed &&
            abs(net) >= config.minNetDisplacement &&
            net * slope > 0 &&
            agreement >= config.minSignAgreement

        val imageMotion = when {
            consistent -> if (slope > 0) ImageMotion.MOVING_RIGHT_IN_IMAGE else ImageMotion.MOVING_LEFT_IN_IMAGE
            significant -> ImageMotion.UNKNOWN.also { reasons += UncertaintyReason.MOTION_INCONSISTENT }
            xFit.residualMad > config.maxStationaryResidual -> ImageMotion.UNKNOWN.also { reasons += UncertaintyReason.MOTION_INCONSISTENT }
            else -> ImageMotion.STATIONARY_OR_LOW_MOTION
        }

        // ---- radial --------------------------------------------------------------------------
        val scaleRate = hFit.slope
        val bottomRate = bFit.slope
        val hSigns = RobustStats.stepSigns(logH, config.scaleStepDeadband.toDouble())
        val bSigns = RobustStats.stepSigns(bottoms, config.bottomStepDeadband.toDouble())
        val minAgree = config.minApproachSignAgreement.toDouble()
        val lateralMoving = imageMotion == ImageMotion.MOVING_LEFT_IN_IMAGE || imageMotion == ImageMotion.MOVING_RIGHT_IN_IMAGE
        val lateralObjectRate = abs(slope) / meanWidth
        val lateralDominant = lateralMoving && lateralObjectRate > config.lateralDominanceFactor * abs(scaleRate)

        val growing = scaleRate >= config.minScaleGrowthRate && hSigns.positiveFraction >= minAgree
        val descending = bottomRate >= config.minBottomDescentRate && bSigns.positiveFraction >= minAgree
        val shrinking = scaleRate <= -config.minScaleGrowthRate && hSigns.negativeFraction >= minAgree
        val ascending = bottomRate <= -config.minBottomDescentRate && bSigns.negativeFraction >= minAgree

        val approach = when {
            growing && descending && !lateralDominant -> ApproachState.APPROACHING
            shrinking && ascending && !lateralDominant -> ApproachState.MOVING_AWAY
            lateralDominant && abs(scaleRate) <= config.crossingMaxScaleRate -> ApproachState.CROSSING
            imageMotion == ImageMotion.STATIONARY_OR_LOW_MOTION &&
                abs(scaleRate) <= config.stationaryMaxScaleRate &&
                abs(bottomRate) <= config.stationaryMaxBottomRate -> ApproachState.STATIONARY
            else -> ApproachState.UNKNOWN.also { reasons += UncertaintyReason.MOTION_INCONSISTENT }
        }

        // ---- confidence ----------------------------------------------------------------------
        val countScore = 0.5 + 0.5 * ((n - config.minObservations).toDouble() / config.minObservations).coerceIn(0.0, 1.0)
        val residualScore = 1.0 - (xFit.residualMad / config.maxStationaryResidual).coerceIn(0.0, 1.0)
        val evidence = when {
            lateralMoving -> agreement * (0.5 + 0.5 * residualScore)
            imageMotion == ImageMotion.STATIONARY_OR_LOW_MOTION -> 0.5 + 0.5 * residualScore
            approach == ApproachState.APPROACHING -> minOf(hSigns.positiveFraction, bSigns.positiveFraction)
            approach == ApproachState.MOVING_AWAY -> minOf(hSigns.negativeFraction, bSigns.negativeFraction)
            else -> 0.0
        }
        val confidence = (evidence * countScore * gyroFactor).coerceIn(0.0, 1.0)

        return MotionEstimate(
            trackId = track.id,
            objectClass = track.objectClass,
            imageMotion = imageMotion,
            approach = approach,
            confidence = confidence.toFloat(),
            lateralSpeed = slope.toFloat(),
            scaleGrowthRate = scaleRate.toFloat(),
            observationCount = n,
            observationSpanMs = span,
            reasons = reasons,
        )
    }

    /** Estimates for every track, keyed by track id. */
    fun estimateAll(tracks: List<Track>, nowMs: Long, horizontalFovDeg: Float? = null): Map<Long, MotionEstimate> =
        tracks.associate { it.id to estimate(it, nowMs, horizontalFovDeg) }

    /** Mean yaw over one step using whichever endpoint readings exist; null if neither does. */
    private fun stepYaw(a: TrackSample, b: TrackSample): Double? {
        val ya = a.cameraYawRateRadPerSec?.takeIf(Float::isFinite)
        val yb = b.cameraYawRateRadPerSec?.takeIf(Float::isFinite)
        return when {
            ya != null && yb != null -> (ya + yb) / 2.0
            ya != null -> ya.toDouble()
            yb != null -> yb.toDouble()
            else -> null
        }
    }
}
