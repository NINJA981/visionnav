package com.visionnav.core.safety

import com.visionnav.core.safety.SafetyGeometry.Companion.rank
import com.visionnav.core.tracking.ApproachState
import com.visionnav.core.tracking.MotionEstimate
import com.visionnav.core.tracking.Track
import com.visionnav.core.vision.ObjectCategory
import com.visionnav.core.vision.ObjectClass

/**
 * @param geometry corridor and proximity-band configuration.
 * @param deEscalateAfterFrames / [deEscalateAfterMs] a lower severity is only adopted after it has
 *   been observed for at least this many consecutive frames AND this long. Escalation is immediate.
 * @param maxMissedFrames tracks coasting for more frames than this produce no event.
 * @param minEmittedSeverity events below this severity are not returned.
 */
data class SafetyRiskConfig(
    val geometry: SafetyGeometryConfig = SafetyGeometryConfig(),
    val deEscalateAfterFrames: Int = 3,
    val deEscalateAfterMs: Long = 1_000,
    val maxMissedFrames: Int = 2,
    val minEmittedSeverity: SafetySeverity = SafetySeverity.INFO,
)

/**
 * Turns confirmed tracks + motion estimates into [SafetyEvent]s (Phase 5).
 *
 * Rules (raw severity, before hysteresis):
 * - **CRITICAL**: any object VERY_NEAR in the walking path; or a vehicle APPROACHING at NEAR or nearer.
 * - **WARNING**: NEAR in path; vehicle APPROACHING at MEDIUM; vehicle VERY_NEAR anywhere in view;
 *   anything APPROACHING in path at MEDIUM or nearer.
 * - **CAUTION**: MEDIUM in path; any APPROACHING vehicle; anything NEAR or nearer outside the path.
 * - **INFO**: everything else. Traffic-control objects (lights, signs) are always INFO.
 *
 * Proximity is a relative image band ([SafetyGeometry.proximity]), never a distance.
 *
 * **Hysteresis:** per track, severity escalates immediately but only de-escalates after the lower
 * level persisted for [SafetyRiskConfig.deEscalateAfterFrames] frames and
 * [SafetyRiskConfig.deEscalateAfterMs]; it then drops to the highest raw level seen during that
 * window. This stops warnings flickering on detector noise without ever delaying a warning.
 *
 * This engine is **independent of crossing/observation mode** and must run on every frame in
 * every mode; it holds per-track state only and never needs to be reset for mode changes. State for
 * tracks that disappear from the input is dropped automatically.
 *
 * Not thread-safe: call from the single analysis pipeline.
 */
class SafetyRiskEngine(val config: SafetyRiskConfig = SafetyRiskConfig()) {

    val geometry = SafetyGeometry(config.geometry)

    private class TrackSeverityState(
        var current: SafetySeverity,
        var lowerSinceMs: Long? = null,
        var lowerFrames: Int = 0,
        var maxLowerSeen: SafetySeverity = SafetySeverity.INFO,
    )

    private val states = HashMap<Long, TrackSeverityState>()

    /**
     * @param estimates motion estimates keyed by track id; a missing estimate means approach UNKNOWN.
     * @return one event per eligible track at or above [SafetyRiskConfig.minEmittedSeverity], sorted
     *   by descending severity then track id.
     */
    fun evaluate(tracks: List<Track>, estimates: Map<Long, MotionEstimate>, nowMs: Long): List<SafetyEvent> {
        val eligible = tracks.filter { it.missedFrames <= config.maxMissedFrames && it.history.isNotEmpty() }
        val eligibleIds = eligible.mapTo(HashSet()) { it.id }
        states.keys.retainAll(eligibleIds)

        val events = ArrayList<SafetyEvent>(eligible.size)
        for (track in eligible) {
            val box = track.latest.box
            val proximity = geometry.proximity(track.objectClass, box)
            val inPath = geometry.inWalkingPath(box)
            val approach = estimates[track.id]?.approach ?: ApproachState.UNKNOWN
            val raw = rawSeverity(track.objectClass, proximity, approach, inPath)
            val severity = applyHysteresis(track.id, raw, nowMs)
            if (severity >= config.minEmittedSeverity) {
                events += SafetyEvent(
                    trackId = track.id,
                    objectClass = track.objectClass,
                    severity = severity,
                    sector = geometry.sector(box),
                    proximity = proximity,
                    approach = approach,
                    inWalkingPath = inPath,
                    elapsedMs = nowMs,
                )
            }
        }
        return events.sortedWith(compareByDescending<SafetyEvent> { it.severity }.thenBy { it.trackId })
    }

    /** Clears hysteresis state (e.g. after a camera restart). Not needed for mode changes. */
    fun reset() = states.clear()

    /** Severity from the current frame alone, without hysteresis. Pure. */
    fun rawSeverity(objectClass: ObjectClass, proximity: ProximityBand, approach: ApproachState, inPath: Boolean): SafetySeverity {
        if (objectClass.category == ObjectCategory.TRAFFIC_CONTROL) return SafetySeverity.INFO
        val vehicle = objectClass.isVehicle
        val approaching = approach == ApproachState.APPROACHING
        val p = proximity.rank
        val veryNear = ProximityBand.VERY_NEAR.rank
        val near = ProximityBand.NEAR.rank
        val medium = ProximityBand.MEDIUM.rank
        return when {
            (p == veryNear && inPath) || (vehicle && approaching && p >= near) -> SafetySeverity.CRITICAL
            (p == near && inPath) ||
                (vehicle && approaching && p == medium) ||
                (vehicle && p == veryNear) ||
                (approaching && inPath && p >= medium) -> SafetySeverity.WARNING
            (p == medium && inPath) || (vehicle && approaching) || p >= near -> SafetySeverity.CAUTION
            else -> SafetySeverity.INFO
        }
    }

    private fun applyHysteresis(trackId: Long, raw: SafetySeverity, nowMs: Long): SafetySeverity {
        val s = states[trackId] ?: return raw.also { states[trackId] = TrackSeverityState(raw) }
        when {
            raw >= s.current -> {
                s.current = raw
                s.lowerSinceMs = null
                s.lowerFrames = 0
                s.maxLowerSeen = SafetySeverity.INFO
            }
            else -> {
                if (s.lowerSinceMs == null) {
                    s.lowerSinceMs = nowMs
                    s.maxLowerSeen = raw
                }
                s.lowerFrames++
                if (raw > s.maxLowerSeen) s.maxLowerSeen = raw
                val since = s.lowerSinceMs!!
                if (s.lowerFrames >= config.deEscalateAfterFrames && nowMs - since >= config.deEscalateAfterMs) {
                    s.current = s.maxLowerSeen
                    s.lowerSinceMs = null
                    s.lowerFrames = 0
                    s.maxLowerSeen = SafetySeverity.INFO
                }
            }
        }
        return s.current
    }
}
