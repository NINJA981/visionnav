package com.visionnav.core.tracking

import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.vision.NormBox
import com.visionnav.core.vision.ObjectClass

/** One observation of a track in one frame. */
data class TrackSample(
    val elapsedMs: Long,
    val box: NormBox,
    val confidence: Float,
    /** Camera yaw rate at this frame (rad/s, positive = turning right), if a gyroscope is available. */
    val cameraYawRateRadPerSec: Float? = null,
)

/** A tracked object with a stable ID across frames. */
data class Track(
    val id: Long,
    val objectClass: ObjectClass,
    /** Chronological, bounded history. Last element is the most recent observation. */
    val history: List<TrackSample>,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** Consecutive frames without a matched detection. */
    val missedFrames: Int,
    /**
     * Set when association was ambiguous (e.g., two candidates overlapped, or the box jumped
     * implausibly). Motion classification must treat history before [historyValidFromMs] as invalid.
     */
    val identitySuspect: Boolean,
    val historyValidFromMs: Long,
) {
    val latest: TrackSample get() = history.last()
    val ageMs: Long get() = lastSeenMs - firstSeenMs
    val validHistory: List<TrackSample> get() = history.filter { it.elapsedMs >= historyValidFromMs }
}

/** Image-space lateral motion. Describes the camera view only, never the user's world frame. */
enum class ImageMotion { MOVING_LEFT_IN_IMAGE, MOVING_RIGHT_IN_IMAGE, STATIONARY_OR_LOW_MOTION, UNKNOWN }

/** Radial motion relative to the camera. */
enum class ApproachState { APPROACHING, MOVING_AWAY, CROSSING, STATIONARY, UNKNOWN }

/** Per-track motion estimate. [reasons] explains any UNKNOWN. */
data class MotionEstimate(
    val trackId: Long,
    val objectClass: ObjectClass,
    val imageMotion: ImageMotion,
    val approach: ApproachState,
    /** 0..1 — consistency of the evidence, not a probability of safety. */
    val confidence: Float,
    /** Robust lateral speed, image widths per second (signed, + = rightward). Null if not computed. */
    val lateralSpeed: Float?,
    /** Robust log-height growth rate per second (+ = getting bigger). Null if not computed. */
    val scaleGrowthRate: Float?,
    val observationCount: Int,
    val observationSpanMs: Long,
    val reasons: Set<UncertaintyReason>,
)
