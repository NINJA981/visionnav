package com.visionnav.core.crossing

import com.visionnav.core.common.TimeSource
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.crossing.CrossingAssistState.APPROACH_CONTEXT_AVAILABLE
import com.visionnav.core.crossing.CrossingAssistState.EXITED
import com.visionnav.core.crossing.CrossingAssistState.INACTIVE
import com.visionnav.core.crossing.CrossingAssistState.OBSERVATION_COMPLETE
import com.visionnav.core.crossing.CrossingAssistState.OBSERVATION_UNCERTAIN
import com.visionnav.core.crossing.CrossingAssistState.OBSERVING_TRAFFIC
import com.visionnav.core.crossing.CrossingAssistState.SEGMENT_TRANSITION
import com.visionnav.core.crossing.CrossingAssistState.USER_STOPPED_FOR_OBSERVATION
import com.visionnav.core.crossing.CrossingCandidate.MappedCrossing
import com.visionnav.core.crossing.CrossingCandidate.VisualCrosswalk
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.sensing.MotionActivity
import com.visionnav.core.vision.AnalysisDemand
import com.visionnav.core.vision.CameraState
import com.visionnav.core.vision.FrameFeed
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class CrossingAssistConfig(
    /** A mapped/possible crossing this close (GPS) makes context "available". Keep below [exitDistanceM] (hysteresis). */
    val approachRadiusM: Double = 25.0,
    /** Moving farther than this from the crossing point (beyond GPS accuracy) exits crossing assist. */
    val exitDistanceM: Double = 30.0,
    /** STATIONARY must be sustained this long before observation starts automatically. */
    val stationaryConfirmMs: Long = 2_000,
    /** Observation ends after this long; the user can restart it. */
    val observationTimeoutMs: Long = 20_000,
    /** After "stop", crossings within this radius of the dismissed one don't re-trigger until the user moves away. */
    val suppressionRadiusM: Double = 15.0,
    /** The only client id this component ever uses with [FrameFeed.setDemand]. */
    val frameFeedClientId: String = "crossing",
)

/**
 * Observation-mode state machine for assisted road crossing (docs/PLAN.md §4.6):
 *
 * ```
 * INACTIVE ─(crossing ≤ approachRadius / user confirms at crossing)▶ APPROACH_CONTEXT_AVAILABLE
 *   ─(user request, OR STATIONARY sustained ≥ 2 s)▶ USER_STOPPED_FOR_OBSERVATION ▶ OBSERVING_TRAFFIC
 * OBSERVING_TRAFFIC ⇄ OBSERVATION_UNCERTAIN            (camera view problems appear / resolve)
 *   ─(timeout / resumed walking)▶ OBSERVATION_COMPLETE  (walking ▶ back to APPROACH_CONTEXT_AVAILABLE)
 *     "Resumed" walking needs STATIONARY seen during this observation: a WALKING reading that only
 *     reflects the steps taken to reach the kerb (e.g. a step event as the activity source starts
 *     together with the observation) never ends it; the timeout and the user's stop still do.
 *   ─(user "next" / user confirms refuge)▶ SEGMENT_TRANSITION ▶ OBSERVING_TRAFFIC (fresh, history cleared)
 * any ─(user stop / moved > 30 m away / route ended)▶ EXITED ▶ INACTIVE
 * ```
 *
 * Safety rationale:
 * - The camera is reached **only** through [FrameFeed.setDemand] with a single client id; this class
 *   never opens a camera. Demand is OBSERVING while observing and NONE otherwise.
 * - `MotionActivity.UNKNOWN` is never treated as stationary: without step/speed evidence the user
 *   must ask explicitly.
 * - Crossing stages advance only on explicit user action; a refuge stage is entered only if the refuge is
 *   MAPPED or user-confirmed. Time and distance never complete a segment.
 * - GPS never yields [UserPositionAssessment.USER_CONFIRMED]; only [onUserConfirmedAtCrossing] does.
 * - It has no access to, and never writes, navigation/walking heading.
 *
 * Output: every input method returns the [CrossingAssistEvent]s it produced (synchronously, in order)
 * and also emits them on [events] (buffered, drop-oldest, no replay). Not thread-safe: drive it from a
 * single thread/coroutine (e.g. the navigation service's main dispatcher).
 */
class CrossingAssistStateMachine(
    private val frameFeed: FrameFeed,
    /** Clears tracker / motion histories for the observation. Wired by the integrator to the tracker reset. */
    private val resetObservationHistory: () -> Unit,
    private val timeSource: TimeSource,
    private val config: CrossingAssistConfig = CrossingAssistConfig(),
) {
    private val _state = MutableStateFlow(INACTIVE)
    val state: StateFlow<CrossingAssistState> = _state.asStateFlow()

    private val _stage = MutableStateFlow(CrossingStage.SIDEWALK)
    val stage: StateFlow<CrossingStage> = _stage.asStateFlow()

    private val _userPosition = MutableStateFlow(UserPositionAssessment.UNKNOWN)
    val userPosition: StateFlow<UserPositionAssessment> = _userPosition.asStateFlow()

    private val _viewIssues = MutableStateFlow<Set<UncertaintyReason>>(emptySet())
    /** Current camera-view problems (quality issues ∪ camera availability). */
    val viewIssues: StateFlow<Set<UncertaintyReason>> = _viewIssues.asStateFlow()

    private val _events = MutableSharedFlow<CrossingAssistEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<CrossingAssistEvent> = _events.asSharedFlow()

    /** Refuge status of the current crossing: MAPPED in map data, or user-confirmed (reported as MAPPED-equivalent). */
    val refugeStatus: RefugeStatus get() = if (refugeUserConfirmed) RefugeStatus.MAPPED else mappedRefuge
    val isRefugeUserConfirmed: Boolean get() = refugeUserConfirmed

    private var latestContext: RoadCrossingContext? = null
    private var anchor: GeoPoint? = null
    private var anchorNodeId: Long? = null
    private var mappedRefuge = RefugeStatus.UNKNOWN
    private var refugeUserConfirmed = false
    private var userConfirmedAtCrossing = false
    private var suppressedAnchor: GeoPoint? = null
    private var activity = MotionActivity.UNKNOWN
    private var stationarySinceMs: Long? = null
    private var observationStartMs: Long? = null
    /** STATIONARY was reported during the current observation, so a later WALKING means the user resumed walking. */
    private var stoppedDuringObservation = false
    private var demandActive = false
    private var qualityIssues: Set<UncertaintyReason> = emptySet()
    private var cameraIssue: UncertaintyReason? = null
    private val pending = mutableListOf<CrossingAssistEvent>()

    // ---------------------------------------------------------------- inputs

    fun onContext(ctx: RoadCrossingContext): List<CrossingAssistEvent> = process { now ->
        latestContext = ctx
        refreshAnchorRefuge(ctx)
        _userPosition.value = if (userConfirmedAtCrossing) UserPositionAssessment.USER_CONFIRMED else ctx.userPosition
        clearSuppressionIfFar(ctx)
        if (_state.value == INACTIVE) {
            val candidate = qualifyingCandidate(ctx)
            if (candidate != null && !isSuppressed(candidate)) {
                setAnchor(candidate, ctx)
                transition(APPROACH_CONTEXT_AVAILABLE, TransitionCause.CONTEXT_NEAR_CROSSING, now)
                checkStationary(now)
            }
        } else {
            // Observation started without a fix: anchor at the first fix we get (never invented).
            if (anchor == null) anchor = ctx.location
            if (movedAway(ctx)) exit(TransitionCause.MOVED_AWAY, now)
        }
    }

    fun onActivity(newActivity: MotionActivity): List<CrossingAssistEvent> = process { now ->
        if (newActivity == MotionActivity.STATIONARY) {
            if (activity != MotionActivity.STATIONARY) stationarySinceMs = now
            if (isObserving()) stoppedDuringObservation = true
        } else {
            stationarySinceMs = null
        }
        activity = newActivity
        if (newActivity == MotionActivity.WALKING) {
            when (_state.value) {
                // WALKING without a stop during this observation is the approach to the kerb (or a single
                // step event as the activity source starts), not "resumed walking": keep observing.
                USER_STOPPED_FOR_OBSERVATION, OBSERVING_TRAFFIC, OBSERVATION_UNCERTAIN -> if (stoppedDuringObservation) {
                    completeObservation(TransitionCause.RESUMED_WALKING, now)
                    transition(APPROACH_CONTEXT_AVAILABLE, TransitionCause.RESUMED_WALKING, now)
                }
                OBSERVATION_COMPLETE -> transition(APPROACH_CONTEXT_AVAILABLE, TransitionCause.RESUMED_WALKING, now)
                else -> Unit
            }
        }
        checkStationary(now)
    }

    /** User said "traffic" / tapped observe. Works without map data or GPS. */
    fun onUserRequestObservation(): List<CrossingAssistEvent> = process { now ->
        when (_state.value) {
            INACTIVE -> {
                latestContext?.let { ctx -> qualifyingCandidate(ctx)?.let { setAnchor(it, ctx) } }
                if (anchor == null) anchor = latestContext?.location
                startObservation(TransitionCause.USER_REQUEST, now)
            }
            APPROACH_CONTEXT_AVAILABLE, OBSERVATION_COMPLETE -> startObservation(TransitionCause.USER_REQUEST, now)
            // Already observing: the request extends the observation window; history is kept.
            OBSERVING_TRAFFIC, OBSERVATION_UNCERTAIN -> observationStartMs = now
            else -> Unit
        }
    }

    /** The user explicitly confirms they are at the crossing. The only source of USER_CONFIRMED. */
    fun onUserConfirmedAtCrossing(): List<CrossingAssistEvent> = process { now ->
        userConfirmedAtCrossing = true
        _userPosition.value = UserPositionAssessment.USER_CONFIRMED
        if (_state.value == INACTIVE) {
            latestContext?.let { ctx -> qualifyingCandidate(ctx)?.let { setAnchor(it, ctx) } }
            if (anchor == null) anchor = latestContext?.location
            transition(APPROACH_CONTEXT_AVAILABLE, TransitionCause.USER_CONFIRMED_AT_CROSSING, now)
            checkStationary(now)
        }
    }

    /** The user explicitly confirms they have reached a refuge island. */
    fun onUserConfirmedRefuge(): List<CrossingAssistEvent> = process { now ->
        refugeUserConfirmed = true
        if (canTransitionSegment()) segmentTransition(TransitionCause.REFUGE_CONFIRMED, now)
    }

    /** User says "next": observe the next road segment from scratch. */
    fun onUserNextSegment(): List<CrossingAssistEvent> = process { now ->
        when {
            canTransitionSegment() -> segmentTransition(TransitionCause.NEXT_SEGMENT, now)
            _state.value == APPROACH_CONTEXT_AVAILABLE || _state.value == INACTIVE -> {
                if (_state.value == INACTIVE && anchor == null) anchor = latestContext?.location
                startObservation(TransitionCause.USER_REQUEST, now)
            }
        }
    }

    fun onUserStop(): List<CrossingAssistEvent> = process { now ->
        if (_state.value != INACTIVE) exit(TransitionCause.USER_STOP, now)
    }

    fun onRouteEnded(): List<CrossingAssistEvent> = process { now ->
        if (_state.value != INACTIVE) exit(TransitionCause.ROUTE_ENDED, now)
    }

    /** Latest frame-quality problems (empty = usable). Only view-related reasons are considered. */
    fun onFrameQualityIssues(issues: Set<UncertaintyReason>): List<CrossingAssistEvent> = process { now ->
        qualityIssues = issues.filterTo(mutableSetOf()) { it in VIEW_REASONS }
        reevaluateView(now)
    }

    fun onCameraState(cameraState: CameraState): List<CrossingAssistEvent> = process { now ->
        cameraIssue = when (cameraState) {
            is CameraState.Unavailable ->
                if (cameraState.reason == UncertaintyReason.DETECTOR_UNAVAILABLE) UncertaintyReason.DETECTOR_UNAVAILABLE
                else UncertaintyReason.CAMERA_UNAVAILABLE
            is CameraState.Running -> if (cameraState.capabilities?.supportsVehicles() == false) UncertaintyReason.DETECTOR_UNAVAILABLE else null
            CameraState.Inactive, CameraState.Starting -> null
        }
        reevaluateView(now)
    }

    /** Drives time-based transitions (stationary confirmation, observation timeout). */
    fun tick(nowMs: Long = timeSource.elapsedMs()): List<CrossingAssistEvent> = processAt(nowMs) { now ->
        checkStationary(now)
        val start = observationStartMs
        if ((_state.value == OBSERVING_TRAFFIC || _state.value == OBSERVATION_UNCERTAIN) &&
            start != null && now - start >= config.observationTimeoutMs
        ) {
            completeObservation(TransitionCause.TIMEOUT, now)
        }
    }

    // ---------------------------------------------------------------- internals

    private inline fun process(block: (Long) -> Unit): List<CrossingAssistEvent> = processAt(timeSource.elapsedMs(), block)

    private inline fun processAt(now: Long, block: (Long) -> Unit): List<CrossingAssistEvent> {
        pending.clear()
        block(now)
        val out = pending.toList()
        pending.clear()
        out.forEach { _events.tryEmit(it) }
        return out
    }

    private fun transition(to: CrossingAssistState, cause: TransitionCause, now: Long) {
        val from = _state.value
        if (from == to) return
        _state.value = to
        pending += CrossingAssistEvent.StateChanged(
            from = from,
            to = to,
            cause = cause,
            atMs = now,
            context = latestContext,
            viewIssues = if (to == OBSERVATION_UNCERTAIN) currentViewIssues() else emptySet(),
        )
    }

    private fun setStage(to: CrossingStage, now: Long) {
        val from = _stage.value
        if (from == to) return
        _stage.value = to
        pending += CrossingAssistEvent.StageChanged(from, to, now)
    }

    private fun setDemand(observing: Boolean) {
        if (observing == demandActive) return
        demandActive = observing
        frameFeed.setDemand(config.frameFeedClientId, if (observing) AnalysisDemand.OBSERVING else AnalysisDemand.NONE)
    }

    private fun checkStationary(now: Long) {
        val since = stationarySinceMs ?: return
        if (_state.value == APPROACH_CONTEXT_AVAILABLE &&
            activity == MotionActivity.STATIONARY &&
            now - since >= config.stationaryConfirmMs
        ) {
            startObservation(TransitionCause.STATIONARY_DETECTED, now)
        }
    }

    private fun startObservation(cause: TransitionCause, now: Long) {
        transition(USER_STOPPED_FOR_OBSERVATION, cause, now)
        // Quality issues gathered while the camera was idle are stale; wait for fresh frames.
        if (!demandActive) qualityIssues = emptySet()
        beginFreshObservation(TransitionCause.OBSERVATION_STARTED, now)
    }

    private fun beginFreshObservation(cause: TransitionCause, now: Long) {
        resetObservationHistory()
        setDemand(true)
        observationStartMs = now
        stoppedDuringObservation = activity == MotionActivity.STATIONARY
        if (_stage.value == CrossingStage.SIDEWALK) setStage(CrossingStage.ROAD_SEGMENT_1, now)
        val issues = currentViewIssues()
        _viewIssues.value = issues
        transition(if (issues.isEmpty()) OBSERVING_TRAFFIC else OBSERVATION_UNCERTAIN, cause, now)
    }

    private fun completeObservation(cause: TransitionCause, now: Long) {
        transition(OBSERVATION_COMPLETE, cause, now)
        observationStartMs = null
        setDemand(false)
    }

    private fun canTransitionSegment(): Boolean = when (_state.value) {
        USER_STOPPED_FOR_OBSERVATION, OBSERVING_TRAFFIC, OBSERVATION_UNCERTAIN, OBSERVATION_COMPLETE -> true
        // After walking on from a completed observation (e.g. now at the refuge).
        APPROACH_CONTEXT_AVAILABLE -> _stage.value != CrossingStage.SIDEWALK
        else -> false
    }

    private fun segmentTransition(cause: TransitionCause, now: Long) {
        transition(SEGMENT_TRANSITION, cause, now)
        // End the previous observation; its tracks/motion must not leak into the next segment.
        observationStartMs = null
        if (!demandActive) qualityIssues = emptySet()
        setStage(nextStage(cause), now)
        beginFreshObservation(TransitionCause.SEGMENT_STARTED, now)
    }

    /**
     * Stage advance on explicit user action only. Entering REFUGE requires a MAPPED or user-confirmed
     * refuge; otherwise the layout is UNKNOWN. Nothing here depends on time or distance.
     */
    private fun nextStage(cause: TransitionCause): CrossingStage {
        if (cause == TransitionCause.REFUGE_CONFIRMED) return CrossingStage.REFUGE
        val refugeKnown = mappedRefuge == RefugeStatus.MAPPED || refugeUserConfirmed
        return when (_stage.value) {
            CrossingStage.SIDEWALK, CrossingStage.ROAD_SEGMENT_1 -> if (refugeKnown) CrossingStage.REFUGE else CrossingStage.UNKNOWN
            CrossingStage.REFUGE -> CrossingStage.ROAD_SEGMENT_2
            CrossingStage.ROAD_SEGMENT_2, CrossingStage.OPPOSITE_SIDEWALK, CrossingStage.UNKNOWN -> CrossingStage.UNKNOWN
        }
    }

    private fun exit(cause: TransitionCause, now: Long) {
        if (cause != TransitionCause.MOVED_AWAY) suppressedAnchor = anchor ?: latestContext?.location
        transition(EXITED, cause, now)
        setDemand(false)
        observationStartMs = null
        anchor = null
        anchorNodeId = null
        mappedRefuge = RefugeStatus.UNKNOWN
        refugeUserConfirmed = false
        userConfirmedAtCrossing = false
        _userPosition.value = latestContext?.userPosition ?: UserPositionAssessment.UNKNOWN
        setStage(CrossingStage.SIDEWALK, now)
        transition(INACTIVE, TransitionCause.EXIT_COMPLETE, now)
        // A later observation (possibly elsewhere, after the location source stopped) must not anchor on
        // this context: a stale anchor makes the first fresh fix look like "moved away" and exits at once.
        latestContext = null
    }

    private fun reevaluateView(now: Long) {
        val issues = currentViewIssues()
        val previous = _viewIssues.value
        _viewIssues.value = issues
        when (_state.value) {
            OBSERVING_TRAFFIC -> if (issues.isNotEmpty()) transition(OBSERVATION_UNCERTAIN, TransitionCause.VIEW_DEGRADED, now)
            OBSERVATION_UNCERTAIN -> when {
                issues.isEmpty() -> transition(OBSERVING_TRAFFIC, TransitionCause.VIEW_RESTORED, now)
                issues != previous -> pending += CrossingAssistEvent.ViewIssuesChanged(issues, now)
            }
            else -> Unit
        }
    }

    private fun isObserving(): Boolean = _state.value == USER_STOPPED_FOR_OBSERVATION ||
        _state.value == OBSERVING_TRAFFIC || _state.value == OBSERVATION_UNCERTAIN

    private fun currentViewIssues(): Set<UncertaintyReason> = buildSet {
        cameraIssue?.let { add(it) }
        addAll(qualityIssues)
    }

    private fun qualifyingCandidate(ctx: RoadCrossingContext): CrossingCandidate? {
        val near = ctx.crossings
            .filter { it !is VisualCrosswalk }
            .mapNotNull { c -> c.distanceOrNull()?.takeIf { it <= config.approachRadiusM }?.let { c to it } }
            .minByOrNull { it.second }?.first
        return near ?: ctx.crossings.firstOrNull { it is VisualCrosswalk }
    }

    private fun setAnchor(candidate: CrossingCandidate, ctx: RoadCrossingContext) {
        anchor = candidate.pointOrNull() ?: ctx.location
        anchorNodeId = (candidate as? MappedCrossing)?.osmNodeId
        mappedRefuge = (candidate as? MappedCrossing)?.refuge ?: RefugeStatus.UNKNOWN
    }

    private fun refreshAnchorRefuge(ctx: RoadCrossingContext) {
        val id = anchorNodeId ?: return
        ctx.crossings.firstOrNull { it is MappedCrossing && it.osmNodeId == id }?.let { mappedRefuge = (it as MappedCrossing).refuge }
    }

    /** Moved away only if the distance exceeds the threshold even after allowing for GPS error. */
    private fun movedAway(ctx: RoadCrossingContext): Boolean {
        val a = anchor ?: return false
        val here = ctx.location ?: return false
        val accuracy = (ctx.locationAccuracyM ?: return false).toDouble()
        return GeoMath.distanceM(a, here) - accuracy > config.exitDistanceM
    }

    private fun isSuppressed(candidate: CrossingCandidate): Boolean {
        val s = suppressedAnchor ?: return false
        val p = candidate.pointOrNull() ?: return true
        return GeoMath.distanceM(s, p) <= config.suppressionRadiusM
    }

    private fun clearSuppressionIfFar(ctx: RoadCrossingContext) {
        val s = suppressedAnchor ?: return
        val here = ctx.location ?: return
        if (GeoMath.distanceM(s, here) > config.exitDistanceM) suppressedAnchor = null
    }

    private companion object {
        val VIEW_REASONS = setOf(
            UncertaintyReason.CAMERA_UNAVAILABLE,
            UncertaintyReason.CAMERA_OBSTRUCTED,
            UncertaintyReason.LOW_LIGHT,
            UncertaintyReason.GLARE,
            UncertaintyReason.BLUR,
            UncertaintyReason.CAMERA_MOVING,
            UncertaintyReason.DETECTOR_UNAVAILABLE,
        )
    }
}
