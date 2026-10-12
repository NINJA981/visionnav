package com.visionnav.core.crossing

import com.visionnav.core.common.UncertaintyReason

/** Why a [CrossingAssistStateMachine] transition happened. */
enum class TransitionCause {
    CONTEXT_NEAR_CROSSING,
    USER_CONFIRMED_AT_CROSSING,
    USER_REQUEST,
    STATIONARY_DETECTED,
    OBSERVATION_STARTED,
    VIEW_DEGRADED,
    VIEW_RESTORED,
    TIMEOUT,
    RESUMED_WALKING,
    NEXT_SEGMENT,
    REFUGE_CONFIRMED,
    SEGMENT_STARTED,
    USER_STOP,
    MOVED_AWAY,
    ROUTE_ENDED,
    EXIT_COMPLETE,
}

/**
 * Output of [CrossingAssistStateMachine]. Every input method returns the events it caused (in order)
 * and also emits them on [CrossingAssistStateMachine.events]. Feed them to
 * [TrafficObservationReporter.onAssistEvent] to obtain spoken announcements.
 */
sealed interface CrossingAssistEvent {
    val atMs: Long

    data class StateChanged(
        val from: CrossingAssistState,
        val to: CrossingAssistState,
        val cause: TransitionCause,
        override val atMs: Long,
        /** Latest crossing context known to the state machine, if any. */
        val context: RoadCrossingContext?,
        /** Current camera-view problems (non-empty iff [to] is OBSERVATION_UNCERTAIN). */
        val viewIssues: Set<UncertaintyReason>,
    ) : CrossingAssistEvent

    data class StageChanged(
        val from: CrossingStage,
        val to: CrossingStage,
        override val atMs: Long,
    ) : CrossingAssistEvent

    /** The set of camera-view problems changed while already OBSERVATION_UNCERTAIN. */
    data class ViewIssuesChanged(
        val viewIssues: Set<UncertaintyReason>,
        override val atMs: Long,
    ) : CrossingAssistEvent
}
