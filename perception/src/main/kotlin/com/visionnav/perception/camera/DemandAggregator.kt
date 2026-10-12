package com.visionnav.perception.camera

import com.visionnav.core.vision.AnalysisDemand

/**
 * Pure bookkeeping of per-client analysis demand for the single camera session.
 *
 * Effective demand is the **maximum** over clients. A client requesting [AnalysisDemand.NONE] is
 * removed. [set] reports what the session must do so that the camera is started exactly once when
 * demand first appears and released as soon as every client is at NONE.
 *
 * Not thread-safe; the owner serialises calls.
 */
class DemandAggregator {

    enum class Action {
        /** Demand went from NONE to > NONE: bind and start the camera (once). */
        START,
        /** Demand dropped to NONE: unbind and release the camera. */
        STOP,
        /** Still running, effective rate changed. */
        UPDATE_RATE,
        /** No effective change. */
        NONE,
    }

    data class Change(val previous: AnalysisDemand, val current: AnalysisDemand, val action: Action)

    private val demands = LinkedHashMap<String, AnalysisDemand>()

    /** Highest demand across clients ([AnalysisDemand.NONE] when there are none). */
    var effective: AnalysisDemand = AnalysisDemand.NONE
        private set

    /** Clients with demand > NONE. */
    val activeClients: Map<String, AnalysisDemand> get() = demands.toMap()

    fun set(clientId: String, demand: AnalysisDemand): Change {
        if (demand == AnalysisDemand.NONE) demands.remove(clientId) else demands[clientId] = demand
        val previous = effective
        effective = demands.values.maxByOrNull { it.targetFps } ?: AnalysisDemand.NONE
        val action = when {
            previous == AnalysisDemand.NONE && effective != AnalysisDemand.NONE -> Action.START
            previous != AnalysisDemand.NONE && effective == AnalysisDemand.NONE -> Action.STOP
            previous != effective -> Action.UPDATE_RATE
            else -> Action.NONE
        }
        return Change(previous, effective, action)
    }

    /** Removes every client (e.g. permission revoked); returns the resulting change. */
    fun clear(): Change {
        val previous = effective
        demands.clear()
        effective = AnalysisDemand.NONE
        return Change(previous, effective, if (previous != AnalysisDemand.NONE) Action.STOP else Action.NONE)
    }
}

/**
 * Timestamp-based frame throttle. Decides **before any per-frame work** whether a camera frame
 * should be analysed, given the effective target fps and a thermal/battery throttle factor.
 *
 * A frame is accepted when at least `interval × (1 − [jitterTolerance])` has elapsed since the
 * last accepted frame, where `interval = 1 / (targetFps × factor)`. The tolerance absorbs camera
 * timestamp jitter so e.g. a 30 fps stream throttled to 5 fps yields ~5 fps rather than ~4.3 fps.
 * The effective rate never drops below [minFps] while demand is active.
 *
 * Not thread-safe; used from the analysis thread only.
 */
class FrameThrottle(
    private val jitterTolerance: Float = 0.1f,
    private val minFps: Float = 0.5f,
) {
    private var lastAcceptedNs: Long = Long.MIN_VALUE

    /** Minimum spacing between accepted frames, or null when [targetFps] ≤ 0 (no analysis). */
    fun intervalNs(targetFps: Int, factor: Float): Long? {
        if (targetFps <= 0) return null
        val fps = maxOf(minFps, targetFps * factor.coerceIn(0f, 1f))
        return (1_000_000_000.0 / fps).toLong()
    }

    /** True (and records the frame) if a frame captured at [timestampNs] should be analysed. */
    fun tryAccept(timestampNs: Long, targetFps: Int, factor: Float): Boolean {
        val interval = intervalNs(targetFps, factor) ?: return false
        if (lastAcceptedNs != Long.MIN_VALUE) {
            val dt = timestampNs - lastAcceptedNs
            // dt < 0: timestamp base changed (camera rebind) — accept and resync.
            if (dt >= 0 && dt < (interval * (1f - jitterTolerance)).toLong()) return false
        }
        lastAcceptedNs = timestampNs
        return true
    }

    fun reset() {
        lastAcceptedNs = Long.MIN_VALUE
    }
}
