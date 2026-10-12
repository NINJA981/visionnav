package com.visionnav.app.engine

import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.vision.CameraState

/**
 * Decides when a user-requested scene description ("What's around") has seen enough analysed
 * frames to be worth speaking. Pure and single-threaded (the engine thread).
 *
 * The camera may be cold (session starting) and the detector may still be loading (GPU init takes
 * seconds on some phones). A fixed wall-clock wait could expire before any frame was analysed and
 * then describe an empty scene as "Nothing recognised", which a blind user would read as absence
 * of obstacles. Instead the gate waits until:
 * - the detector is ready ([CameraState.Running] with a real, non-mock detector), and
 * - at least [minFrames] frames analysed by it have arrived **since the request**, spanning at
 *   least [minSpanMs] (whichever comes later),
 * capped at [capMs] after the request. At the cap, a request with no analysed frame yields
 * [Verdict.NotReady] (say so honestly), never an empty description.
 */
class SceneWarmupGate(
    private val requestMs: Long,
    private val minFrames: Int = MIN_FRAMES,
    private val minSpanMs: Long = MIN_SPAN_MS,
    private val capMs: Long = CAP_MS,
) {
    enum class Verdict {
        /** Keep waiting for frames. */
        WAIT,

        /** Enough analysed frames: describe the scene. */
        READY,

        /** Cap reached with some (but fewer than wanted) analysed frames: describe what was seen. */
        PARTIAL,

        /** Cap reached and no frame was analysed by a ready detector: do not describe. */
        NOT_READY,

        /** The camera cannot deliver frames. */
        CAMERA_UNAVAILABLE,

        /** Frames may flow but nothing can be recognised in them (detector failed or mock). */
        DETECTOR_UNAVAILABLE,
    }

    private var frames = 0
    private var firstFrameMs: Long? = null
    private var lastFrameMs: Long? = null

    /** Frames analysed by a ready detector since the request. */
    val analysedFrames: Int get() = frames

    /**
     * Called for every analysed frame. [detectorReady] is whether the camera state at that moment
     * says a real detector produced the detections (see [isDetectorReady]).
     */
    fun onFrame(frameElapsedMs: Long, detectorReady: Boolean) {
        if (!detectorReady || frameElapsedMs < requestMs) return
        if (firstFrameMs == null) firstFrameMs = frameElapsedMs
        lastFrameMs = frameElapsedMs
        frames++
    }

    fun verdict(nowMs: Long, camera: CameraState): Verdict {
        when {
            // A failure left over from an earlier session is reported until the new start resets
            // it, so "unavailable" only counts once the session has had a moment to (re)start.
            camera is CameraState.Unavailable && nowMs - requestMs < UNAVAILABLE_GRACE_MS -> Unit
            camera is CameraState.Unavailable ->
                return if (camera.reason == UncertaintyReason.DETECTOR_UNAVAILABLE) Verdict.DETECTOR_UNAVAILABLE
                else Verdict.CAMERA_UNAVAILABLE
            camera is CameraState.Running && camera.capabilities?.isMock == true -> return Verdict.DETECTOR_UNAVAILABLE
        }
        if (enough()) return Verdict.READY
        if (nowMs - requestMs < capMs) return Verdict.WAIT
        return if (frames > 0) Verdict.PARTIAL else Verdict.NOT_READY
    }

    private fun enough(): Boolean {
        val first = firstFrameMs ?: return false
        val last = lastFrameMs ?: return false
        return frames >= minFrames && last - first >= minSpanMs
    }

    companion object {
        const val MIN_FRAMES = 4
        const val MIN_SPAN_MS = 1_200L
        const val CAP_MS = 6_000L
        const val UNAVAILABLE_GRACE_MS = 1_000L

        /** True when frames are being analysed by a real (non-mock) detector. */
        fun isDetectorReady(camera: CameraState): Boolean =
            camera is CameraState.Running && camera.capabilities?.isMock != true
    }
}
