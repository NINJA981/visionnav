package com.visionnav.core.crossing

import com.visionnav.core.announce.Announcement
import com.visionnav.core.announce.AnnouncementPriority
import com.visionnav.core.announce.Earcon
import com.visionnav.core.announce.SafetyPhraseGuard
import com.visionnav.core.common.Reliability
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.crossing.CrossingCandidate.MappedCrossing
import com.visionnav.core.crossing.CrossingCandidate.PossibleCrossing
import com.visionnav.core.crossing.CrossingCandidate.VisualCrosswalk
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.tracking.ApproachState
import com.visionnav.core.tracking.ImageMotion
import com.visionnav.core.tracking.MotionEstimate
import kotlin.math.abs

data class TrafficReporterConfig(
    /** Per-key cooldown for traffic descriptions (OBSERVATION priority). */
    val observationCooldownMs: Long = 4_000,
    /** Per-reason cooldown for "traffic view unclear …" phrases. */
    val viewIssueCooldownMs: Long = 6_000,
    /** Cooldown for road/crossing context phrases. */
    val contextCooldownMs: Long = 60_000,
    /** Cooldown for "against the expected direction". */
    val mismatchCooldownMs: Long = 8_000,
    /** Interval of the INFO "Camera sees ahead only." reminder while observing. */
    val coverageReminderIntervalMs: Long = 15_000,
    /** More than this many moving vehicles switches to the summary phrase. */
    val summaryThreshold: Int = 3,
    /** A track that is UNKNOWN only because it is young stays silent until it has been seen this long. */
    val unclearMinSpanMs: Long = 1_000,
    /** Per-track dedupe memory is dropped after the track has been absent this long. */
    val trackForgetMs: Long = 5_000,
    val observationTtlMs: Long = 3_000,
    val navigationTtlMs: Long = 8_000,
    val infoTtlMs: Long = 5_000,
    /** For "traffic expected from your left/right": road flow within this of camera bearing ± 90°. */
    val sideToleranceDeg: Double = 40.0,
)

/**
 * Turns crossing-assist state, road context, per-track [MotionEstimate]s, map/camera comparison and
 * camera-view problems into [Announcement]s. All methods are synchronous and return the announcements
 * to enqueue (in order); nothing is spoken directly.
 *
 * Integration: `stateMachine.onX(...).forEach { reporter.onAssistEvent(it, orientation) }`, then while
 * observing feed every motion update to [onMotion] and call [tick] periodically.
 *
 * Safety rationale:
 * - Only observations are described. No vehicles → **no output** (silence is not reassurance; the only
 *   no-vehicle phrase is spoken on explicit request and restates the coverage limit).
 * - World wording ("from your left") only when orientation is RELIABLE; otherwise image wording.
 * - A map/camera mismatch never suppresses the track's own description; the extra "against the
 *   expected direction" phrase requires [MismatchPersistence] confirmation.
 * - Every text passes [SafetyPhraseGuard]; a block is a defect reported via [onDefect] and dropped.
 *
 * Dedupe: one utterance per (track, description) change; per-key cooldowns; summary mode above
 * [TrafficReporterConfig.summaryThreshold] moving vehicles; an escalation to APPROACHING bypasses both
 * cooldown and summary mode and is flagged `escalation = true`. Not thread-safe.
 */
class TrafficObservationReporter(
    private val config: TrafficReporterConfig = TrafficReporterConfig(),
    private val fusion: FlowFusion = FlowFusion(),
    private val mismatchPersistence: MismatchPersistence = MismatchPersistence(),
    private val guard: SafetyPhraseGuard = SafetyPhraseGuard(),
    private val onDefect: (String) -> Unit = {},
) {
    private class TrackMemory(var lastSpoken: TrackDescription?, var lastSeenMs: Long)

    private val tracks = HashMap<Long, TrackMemory>()
    private val lastSpokenAt = HashMap<String, Long>()
    private var summaryActive = false
    private var lastCoverageMs = 0L

    /** True between observation start and end events. Traffic descriptions are only produced while true. */
    var isObserving: Boolean = false
        private set

    // ------------------------------------------------------------ state machine events

    fun onAssistEvent(event: CrossingAssistEvent, orientation: CameraOrientationEstimate? = null): List<Announcement> {
        val out = mutableListOf<Announcement>()
        val now = event.atMs
        when (event) {
            is CrossingAssistEvent.StateChanged -> onStateChanged(event, orientation, out)
            is CrossingAssistEvent.StageChanged -> if (event.to == CrossingStage.UNKNOWN) {
                emit(out, CrossingPhrases.REFUGE_UNCONFIRMED, AnnouncementPriority.NAVIGATION, KEY_STAGE, now, config.observationCooldownMs)
            }
            is CrossingAssistEvent.ViewIssuesChanged -> emitViewIssue(out, event.viewIssues, now)
        }
        return out
    }

    private fun onStateChanged(e: CrossingAssistEvent.StateChanged, orientation: CameraOrientationEstimate?, out: MutableList<Announcement>) {
        val now = e.atMs
        when (e.to) {
            CrossingAssistState.APPROACH_CONTEXT_AVAILABLE ->
                if (e.from == CrossingAssistState.INACTIVE && e.context != null) out += describeContext(e.context, orientation, now)
            CrossingAssistState.OBSERVING_TRAFFIC, CrossingAssistState.OBSERVATION_UNCERTAIN -> when (e.cause) {
                TransitionCause.OBSERVATION_STARTED, TransitionCause.SEGMENT_STARTED -> {
                    beginObservation(now)
                    // Confirms a user request (like "where am I"), so it is NAVIGATION priority: it must not
                    // consume the ambient rate-limit budget and delay the first real traffic observation.
                    emit(out, CrossingPhrases.OBSERVING, AnnouncementPriority.NAVIGATION, KEY_OBSERVING, now, config.observationCooldownMs, earcon = Earcon.OBSERVATION_START)
                    if (e.context != null) {
                        emit(out, roadPhrase(e.context, orientation), AnnouncementPriority.NAVIGATION, KEY_CONTEXT_ROAD, now, config.contextCooldownMs)
                    }
                    if (e.to == CrossingAssistState.OBSERVATION_UNCERTAIN) emitViewIssue(out, e.viewIssues, now)
                }
                TransitionCause.VIEW_DEGRADED -> emitViewIssue(out, e.viewIssues, now)
                TransitionCause.VIEW_RESTORED ->
                    emit(out, CrossingPhrases.OBSERVING, AnnouncementPriority.OBSERVATION, KEY_OBSERVING, now, config.observationCooldownMs)
                else -> Unit
            }
            CrossingAssistState.OBSERVATION_COMPLETE, CrossingAssistState.EXITED -> if (isObserving) {
                emit(out, CrossingPhrases.OBSERVATION_ENDED, AnnouncementPriority.OBSERVATION, KEY_ENDED, now, 0, earcon = Earcon.OBSERVATION_END)
                endObservation()
            }
            CrossingAssistState.SEGMENT_TRANSITION -> clearObservationMemory()
            CrossingAssistState.INACTIVE, CrossingAssistState.USER_STOPPED_FOR_OBSERVATION -> Unit
        }
    }

    // ------------------------------------------------------------ context

    /** Crossing / road / map-uncertainty phrases for a newly available crossing context. */
    fun describeContext(ctx: RoadCrossingContext, orientation: CameraOrientationEstimate?, nowMs: Long): List<Announcement> {
        val out = mutableListOf<Announcement>()
        primaryCandidate(ctx)?.let { c ->
            val text = when (c) {
                is MappedCrossing -> CrossingPhrases.mappedCrossing(c.control, c.refuge, c.hasAudibleSignal)
                is PossibleCrossing -> CrossingPhrases.POSSIBLE_CROSSING
                is VisualCrosswalk -> CrossingPhrases.VISUAL_CROSSWALK
            }
            emit(out, text, AnnouncementPriority.NAVIGATION, KEY_CONTEXT_CROSSING, nowMs, config.contextCooldownMs)
        }
        emit(out, roadPhrase(ctx, orientation), AnnouncementPriority.NAVIGATION, KEY_CONTEXT_ROAD, nowMs, config.contextCooldownMs)
        for (r in CONTEXT_REASONS) if (r in ctx.uncertainties) {
            emit(out, CrossingPhrases.uncertainty(r), AnnouncementPriority.INFO, "$KEY_CONTEXT_PREFIX.uncertainty.$r", nowMs, config.contextCooldownMs)
        }
        return out
    }

    /**
     * Road-direction wording. Side ("from your left") only with RELIABLE orientation and a mapped
     * (not inferred) one-way road running across the camera's view.
     */
    fun roadPhrase(ctx: RoadCrossingContext, orientation: CameraOrientationEstimate?): String {
        val road = relevantRoad(ctx) ?: return CrossingPhrases.ROAD_DIRECTION_UNAVAILABLE
        return when (road.directionality) {
            RoadDirectionality.UNKNOWN -> CrossingPhrases.ROAD_DIRECTION_UNAVAILABLE
            RoadDirectionality.TWO_WAY -> CrossingPhrases.TWO_WAY
            RoadDirectionality.ONE_WAY -> when {
                road.reliability == Reliability.INFERRED -> CrossingPhrases.ROUNDABOUT
                else -> trafficSide(road, orientation)?.let(CrossingPhrases::oneWayFrom) ?: CrossingPhrases.ONE_WAY
            }
        }
    }

    private fun trafficSide(road: RoadSegmentContext, orientation: CameraOrientationEstimate?): TrafficSide? {
        if (orientation == null || !orientation.isReliable) return null
        val cam = orientation.cameraBearingDeg ?: return null
        val flow = road.expectedFlows.singleOrNull()?.bearingDeg ?: return null
        val rel = GeoMath.signedAngleDiff(cam, flow)
        return when {
            abs(rel - 90.0) <= config.sideToleranceDeg -> TrafficSide.LEFT // flowing to the user's right ⇒ comes from the left
            abs(rel + 90.0) <= config.sideToleranceDeg -> TrafficSide.RIGHT
            else -> null
        }
    }

    // ------------------------------------------------------------ traffic

    /**
     * Describes vehicle tracks while observing. Non-vehicles are ignored. An empty or vehicle-free
     * update produces nothing. [context] (may be null, e.g. no map data) is used only for the
     * optional map/camera comparison — reporting never depends on it.
     */
    fun onMotion(
        estimates: List<MotionEstimate>,
        orientation: CameraOrientationEstimate,
        context: RoadCrossingContext?,
        cameraHfovDeg: Float?,
        nowMs: Long,
    ): List<Announcement> {
        if (!isObserving) return emptyList()
        val out = mutableListOf<Announcement>()
        val vehicles = estimates.filter { it.objectClass.isVehicle }
        for (v in vehicles) tracks.getOrPut(v.trackId) { TrackMemory(null, nowMs) }.lastSeenMs = nowMs
        tracks.entries.removeAll { nowMs - it.value.lastSeenMs > config.trackForgetMs }

        val road = context?.let(::relevantRoad)
        var anyMismatch = false
        for (v in vehicles) {
            val comparison = fusion.compare(v, orientation, road, cameraHfovDeg)
            if (comparison == FlowComparison.EXPECTED_FLOW_MISMATCH) anyMismatch = true
            mismatchPersistence.update(v.trackId, comparison, nowMs)
        }

        val moving = vehicles.count(::isMoving)
        val summary = moving > config.summaryThreshold
        if (summary && !summaryActive) {
            emit(out, CrossingPhrases.SEVERAL_VEHICLES, AnnouncementPriority.OBSERVATION, KEY_SUMMARY, nowMs, config.observationCooldownMs)
        }
        summaryActive = summary

        for (v in vehicles) {
            val d = describe(v, orientation, onRequest = false) ?: continue
            val mem = tracks.getValue(v.trackId)
            if (mem.lastSpoken == d) continue
            val escalation = d == TrackDescription.APPROACHING
            if (summary && !escalation) {
                mem.lastSpoken = d // covered by the summary phrase
                continue
            }
            val spoken = emit(
                out, CrossingPhrases.track(v.objectClass, d), AnnouncementPriority.OBSERVATION, "$KEY_TRACK.${v.trackId}",
                nowMs, config.observationCooldownMs, escalation = escalation && mem.lastSpoken != null, bypassCooldown = escalation,
            )
            if (spoken) mem.lastSpoken = d
        }

        if (anyMismatch && mismatchPersistence.isConfirmed(nowMs)) {
            emit(out, CrossingPhrases.AGAINST_EXPECTED, AnnouncementPriority.OBSERVATION, KEY_MISMATCH, nowMs, config.mismatchCooldownMs)
        }
        return out
    }

    /**
     * Explicit user request ("traffic?"): describes what is currently seen, bypassing dedupe. With no
     * vehicles, states that none are *recognised* and repeats the coverage limit — never "clear".
     */
    fun describeOnRequest(estimates: List<MotionEstimate>, orientation: CameraOrientationEstimate, nowMs: Long): List<Announcement> {
        val out = mutableListOf<Announcement>()
        val vehicles = estimates.filter { it.objectClass.isVehicle }
        when {
            vehicles.isEmpty() ->
                emit(out, CrossingPhrases.NO_VEHICLES_ON_REQUEST, AnnouncementPriority.OBSERVATION, KEY_REQUEST, nowMs, 0, bypassCooldown = true)
            vehicles.count(::isMoving) > config.summaryThreshold ->
                emit(out, CrossingPhrases.SEVERAL_VEHICLES, AnnouncementPriority.OBSERVATION, KEY_REQUEST, nowMs, 0, bypassCooldown = true)
            else -> vehicles
                .sortedByDescending { if (it.approach == ApproachState.APPROACHING) 2 else if (isMoving(it)) 1 else 0 }
                .take(config.summaryThreshold)
                .forEach { v ->
                    val d = describe(v, orientation, onRequest = true) ?: return@forEach
                    emit(out, CrossingPhrases.track(v.objectClass, d), AnnouncementPriority.OBSERVATION, "$KEY_REQUEST.${v.trackId}", nowMs, 0, bypassCooldown = true)
                }
        }
        return out
    }

    /** Periodic INFO coverage reminder while observing. */
    fun tick(nowMs: Long): List<Announcement> {
        if (!isObserving || nowMs - lastCoverageMs < config.coverageReminderIntervalMs) return emptyList()
        lastCoverageMs = nowMs
        val out = mutableListOf<Announcement>()
        emit(out, CrossingPhrases.COVERAGE_REMINDER, AnnouncementPriority.INFO, KEY_COVERAGE, nowMs, 0, bypassCooldown = true)
        return out
    }

    /** Forget everything, including context cooldowns. */
    fun reset() {
        clearObservationMemory()
        lastSpokenAt.clear()
        isObserving = false
    }

    // ------------------------------------------------------------ internals

    private fun describe(v: MotionEstimate, orientation: CameraOrientationEstimate, onRequest: Boolean): TrackDescription? {
        if (v.approach == ApproachState.APPROACHING) return TrackDescription.APPROACHING
        if (v.imageMotion == ImageMotion.MOVING_RIGHT_IN_IMAGE || v.imageMotion == ImageMotion.MOVING_LEFT_IN_IMAGE) {
            return when (fusion.worldLateralDirection(v, orientation)) {
                WorldLateral.LEFT_TO_RIGHT -> TrackDescription.WORLD_FROM_LEFT
                WorldLateral.RIGHT_TO_LEFT -> TrackDescription.WORLD_FROM_RIGHT
                WorldLateral.UNKNOWN ->
                    if (v.imageMotion == ImageMotion.MOVING_RIGHT_IN_IMAGE) TrackDescription.IMAGE_LEFT_TO_RIGHT
                    else TrackDescription.IMAGE_RIGHT_TO_LEFT
            }
        }
        if (v.approach == ApproachState.MOVING_AWAY) return TrackDescription.MOVING_AWAY
        if (v.approach == ApproachState.STATIONARY) return if (onRequest) TrackDescription.NO_CLEAR_MOTION else null
        val tooEarly = UncertaintyReason.TRACK_TOO_SHORT in v.reasons && v.observationSpanMs < config.unclearMinSpanMs
        return if (tooEarly && !onRequest) null else TrackDescription.DIRECTION_UNCLEAR
    }

    private fun isMoving(v: MotionEstimate): Boolean =
        v.imageMotion == ImageMotion.MOVING_LEFT_IN_IMAGE || v.imageMotion == ImageMotion.MOVING_RIGHT_IN_IMAGE ||
            v.approach == ApproachState.APPROACHING || v.approach == ApproachState.MOVING_AWAY || v.approach == ApproachState.CROSSING

    private fun emitViewIssue(out: MutableList<Announcement>, issues: Set<UncertaintyReason>, now: Long) {
        val top = VIEW_PRIORITY.firstOrNull { it in issues } ?: issues.firstOrNull() ?: return
        emit(out, CrossingPhrases.uncertainty(top), AnnouncementPriority.OBSERVATION, "$KEY_VIEW.$top", now, config.viewIssueCooldownMs)
    }

    private fun beginObservation(now: Long) {
        clearObservationMemory()
        isObserving = true
        lastCoverageMs = now
    }

    private fun endObservation() {
        clearObservationMemory()
        isObserving = false
    }

    /** Clears per-observation dedupe state so a new segment starts from zero (context cooldowns kept). */
    private fun clearObservationMemory() {
        tracks.clear()
        summaryActive = false
        mismatchPersistence.reset()
        lastSpokenAt.keys.removeAll { !it.startsWith(KEY_CONTEXT_PREFIX) }
    }

    private fun emit(
        out: MutableList<Announcement>,
        text: String,
        priority: AnnouncementPriority,
        key: String,
        now: Long,
        cooldownMs: Long,
        earcon: Earcon = Earcon.NONE,
        escalation: Boolean = false,
        bypassCooldown: Boolean = false,
    ): Boolean {
        if (!bypassCooldown) {
            val last = lastSpokenAt[key]
            if (last != null && now - last < cooldownMs) return false
        }
        if (guard.check(text) is SafetyPhraseGuard.Verdict.Blocked) {
            onDefect(text)
            return false
        }
        lastSpokenAt[key] = now
        val ttl = when (priority) {
            AnnouncementPriority.INFO -> config.infoTtlMs
            AnnouncementPriority.OBSERVATION -> config.observationTtlMs
            else -> config.navigationTtlMs
        }
        out += Announcement(text = text, priority = priority, dedupeKey = key, ttlMs = ttl, earcon = earcon, escalation = escalation, createdAtMs = now)
        return true
    }

    companion object {
        private const val KEY_CONTEXT_PREFIX = "crossing.context"
        private const val KEY_CONTEXT_CROSSING = "$KEY_CONTEXT_PREFIX.crossing"
        private const val KEY_CONTEXT_ROAD = "$KEY_CONTEXT_PREFIX.road"
        private const val KEY_OBSERVING = "crossing.observing"
        private const val KEY_ENDED = "crossing.ended"
        private const val KEY_STAGE = "crossing.stage"
        private const val KEY_VIEW = "crossing.view"
        private const val KEY_TRACK = "crossing.track"
        private const val KEY_SUMMARY = "crossing.summary"
        private const val KEY_MISMATCH = "crossing.mismatch"
        private const val KEY_COVERAGE = "crossing.coverage"
        private const val KEY_REQUEST = "crossing.request"

        private val CONTEXT_REASONS = listOf(
            UncertaintyReason.GPS_UNAVAILABLE,
            UncertaintyReason.GPS_LOW_ACCURACY,
            UncertaintyReason.MAP_DATA_UNAVAILABLE,
            UncertaintyReason.CROSSING_AMBIGUOUS,
        )

        private val VIEW_PRIORITY = listOf(
            UncertaintyReason.CAMERA_UNAVAILABLE,
            UncertaintyReason.DETECTOR_UNAVAILABLE,
            UncertaintyReason.CAMERA_OBSTRUCTED,
            UncertaintyReason.LOW_LIGHT,
            UncertaintyReason.GLARE,
            UncertaintyReason.CAMERA_MOVING,
            UncertaintyReason.BLUR,
        )

        /**
         * The crossing being described: the nearest mapped/possible crossing with a known distance,
         * else a visual crosswalk. Crossings with unknown distance (no GPS) are not called "ahead".
         */
        fun primaryCandidate(ctx: RoadCrossingContext): CrossingCandidate? =
            ctx.crossings.filter { it.distanceOrNull() != null }.minByOrNull { it.distanceOrNull()!! }
                ?: ctx.crossings.firstOrNull { it is VisualCrosswalk }

        /**
         * The road the user is most likely facing: the road nearest the primary crossing point (within
         * 15 m) if there is one, else the road nearest the user. Null without GPS — we don't guess.
         */
        fun relevantRoad(ctx: RoadCrossingContext): RoadSegmentContext? {
            if (ctx.location == null) return null
            val located = ctx.roads.filter { it.distanceM != null }
            val crossingPoint = primaryCandidate(ctx)?.pointOrNull()
            if (crossingPoint != null) {
                val byCrossing = located
                    .mapNotNull { r -> GeoMath.projectOntoPolyline(crossingPoint, r.geometry)?.distanceM?.let { r to it } }
                    .filter { it.second <= 15.0 }
                    .minByOrNull { it.second }?.first
                if (byCrossing != null) return byCrossing
            }
            return located.minByOrNull { it.distanceM!! }
        }
    }
}
