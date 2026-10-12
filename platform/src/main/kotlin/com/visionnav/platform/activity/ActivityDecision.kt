package com.visionnav.platform.activity

import com.visionnav.core.sensing.MotionActivity

/**
 * Inputs to [ActivityDecision.decide]. All times are on one monotonic clock (ms).
 *
 * @param stepSensorUsable the step detector exists **and** its permission
 *   (`ACTIVITY_RECOGNITION`, API 29+) is granted. A present-but-unpermitted sensor is unusable.
 * @param listeningSinceMs when the step detector started delivering (used as the reference for
 *   "no steps for N s" before the first step); null if not listening.
 * @param lastStepMs time of the most recent detected step; null if none since listening began.
 * @param reliableSpeedMps GPS speed **only** if it comes from a fresh fix with good accuracy;
 *   null when there is no reliable speed. Unreliable speeds must not be passed.
 * @param previous last decided activity, used to hold state inside the hysteresis band.
 */
data class ActivityInputs(
    val nowMs: Long,
    val stepSensorUsable: Boolean,
    val listeningSinceMs: Long?,
    val lastStepMs: Long?,
    val reliableSpeedMps: Float?,
    val previous: MotionActivity,
)

/**
 * Pure walking/stationary decision (JVM-testable).
 *
 * - **WALKING** if a step was detected within [STEP_RECENT_MS], or reliable GPS speed >
 *   [WALKING_SPEED_MPS].
 * - **STATIONARY** only with a usable step detector that has seen no step for at least
 *   [STATIONARY_NO_STEP_MS], **and** reliable speed is < [STATIONARY_SPEED_MPS] or unknown.
 *   GPS speed alone never declares STATIONARY: low GPS speeds are too noisy to trust.
 * - **UNKNOWN** if there is no usable step detector (absent, or permission missing) and no
 *   reliable speed showing walking.
 * - Otherwise (hysteresis band, e.g. last step 2.0–2.5 s ago, or speed 0.3–0.6 m/s with no
 *   steps) the [ActivityInputs.previous] value is kept.
 */
object ActivityDecision {
    const val STEP_RECENT_MS = 2_000L
    const val STATIONARY_NO_STEP_MS = 2_500L
    const val WALKING_SPEED_MPS = 0.6f
    const val STATIONARY_SPEED_MPS = 0.3f

    fun decide(input: ActivityInputs): MotionActivity {
        val speed = input.reliableSpeedMps
        val recentStep = input.stepSensorUsable &&
            input.lastStepMs != null &&
            input.nowMs - input.lastStepMs <= STEP_RECENT_MS
        if (recentStep) return MotionActivity.WALKING
        if (speed != null && speed > WALKING_SPEED_MPS) return MotionActivity.WALKING

        if (!input.stepSensorUsable) return MotionActivity.UNKNOWN
        val reference = input.lastStepMs ?: input.listeningSinceMs ?: return MotionActivity.UNKNOWN
        val quietMs = input.nowMs - reference
        if (quietMs >= STATIONARY_NO_STEP_MS && (speed == null || speed < STATIONARY_SPEED_MPS)) {
            return MotionActivity.STATIONARY
        }
        return input.previous
    }
}
