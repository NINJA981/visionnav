package com.visionnav.core.crossing

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.FakeTimeSource
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.crossing.CrossingAssistState.APPROACH_CONTEXT_AVAILABLE
import com.visionnav.core.crossing.CrossingAssistState.EXITED
import com.visionnav.core.crossing.CrossingAssistState.INACTIVE
import com.visionnav.core.crossing.CrossingAssistState.OBSERVATION_COMPLETE
import com.visionnav.core.crossing.CrossingAssistState.OBSERVATION_UNCERTAIN
import com.visionnav.core.crossing.CrossingAssistState.OBSERVING_TRAFFIC
import com.visionnav.core.crossing.CrossingAssistState.SEGMENT_TRANSITION
import com.visionnav.core.crossing.CrossingAssistState.USER_STOPPED_FOR_OBSERVATION
import com.visionnav.core.sensing.MotionActivity
import com.visionnav.core.vision.AnalysisDemand
import com.visionnav.core.vision.CameraState
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CrossingAssistStateMachineTest {

    private val clock = FakeTimeSource(1_000)
    private val feed = FakeFrameFeed()
    private var resets = 0
    private val sm = CrossingAssistStateMachine(feed, { resets++ }, clock)

    private fun nearContext(refuge: RefugeStatus = RefugeStatus.UNKNOWN) =
        Fx.context(crossings = listOf(Fx.mappedCrossing(distanceM = 8.0, refuge = refuge)))

    private fun List<CrossingAssistEvent>.states() =
        filterIsInstance<CrossingAssistEvent.StateChanged>().map { it.to }

    private fun startObservingViaStationary(ctx: RoadCrossingContext = nearContext()) {
        sm.onContext(ctx)
        sm.onActivity(MotionActivity.STATIONARY)
        clock.advance(2_000)
        sm.tick()
        assertThat(sm.state.value).isEqualTo(OBSERVING_TRAFFIC)
    }

    @Test
    fun contextNearCrossing_entersApproach() {
        val events = sm.onContext(nearContext())
        assertThat(events.states()).containsExactly(APPROACH_CONTEXT_AVAILABLE)
        assertThat(sm.stage.value).isEqualTo(CrossingStage.SIDEWALK)
        assertThat(feed.demandCalls).isEmpty()
    }

    @Test
    fun farCrossing_staysInactive() {
        sm.onContext(Fx.context(crossings = listOf(Fx.mappedCrossing(distanceM = 60.0))))
        assertThat(sm.state.value).isEqualTo(INACTIVE)
    }

    @Test
    fun stationaryMustBeSustainedTwoSeconds() {
        sm.onContext(nearContext())
        sm.onActivity(MotionActivity.STATIONARY)
        clock.advance(1_999)
        sm.tick()
        assertThat(sm.state.value).isEqualTo(APPROACH_CONTEXT_AVAILABLE)
        clock.advance(1)
        val events = sm.tick()
        assertThat(events.states()).containsExactly(USER_STOPPED_FOR_OBSERVATION, OBSERVING_TRAFFIC).inOrder()
        assertThat(sm.stage.value).isEqualTo(CrossingStage.ROAD_SEGMENT_1)
    }

    @Test
    fun walkingInterruptsStationaryTimer() {
        sm.onContext(nearContext())
        sm.onActivity(MotionActivity.STATIONARY)
        clock.advance(1_500)
        sm.onActivity(MotionActivity.WALKING)
        sm.onActivity(MotionActivity.STATIONARY)
        clock.advance(1_500)
        sm.tick()
        assertThat(sm.state.value).isEqualTo(APPROACH_CONTEXT_AVAILABLE)
    }

    @Test
    fun unknownActivity_isNeverTreatedAsStationary_userMustAsk() {
        sm.onContext(nearContext())
        sm.onActivity(MotionActivity.UNKNOWN)
        clock.advance(60_000)
        sm.tick()
        assertThat(sm.state.value).isEqualTo(APPROACH_CONTEXT_AVAILABLE)
        sm.onUserRequestObservation()
        assertThat(sm.state.value).isEqualTo(OBSERVING_TRAFFIC)
    }

    // ---- Brief 17: observation start/end uses FrameFeed demand only

    @Test
    fun brief17_observationUsesFrameFeedDemandOnly_singleClient_noneOnExit() {
        startObservingViaStationary()
        assertThat(feed.demandCalls).containsExactly("crossing" to AnalysisDemand.OBSERVING)

        sm.onFrameQualityIssues(setOf(UncertaintyReason.LOW_LIGHT))
        sm.onFrameQualityIssues(emptySet())
        sm.onUserNextSegment()
        assertThat(feed.demandCalls).containsExactly("crossing" to AnalysisDemand.OBSERVING) // no camera restart

        sm.onUserStop()
        assertThat(sm.state.value).isEqualTo(INACTIVE)
        assertThat(feed.demandCalls.map { it.first }.toSet()).containsExactly("crossing")
        assertThat(feed.demandCalls.last()).isEqualTo("crossing" to AnalysisDemand.NONE)
        assertThat(feed.demandCalls.count { it.second == AnalysisDemand.OBSERVING }).isEqualTo(1)
        assertThat(feed.torchCalls).isEqualTo(0)
    }

    @Test
    fun brief17_timeoutAndRestart_toggleDemandCleanly() {
        startObservingViaStationary()
        clock.advance(20_000)
        val events = sm.tick()
        assertThat(events.states()).containsExactly(OBSERVATION_COMPLETE)
        assertThat(feed.demandCalls.last()).isEqualTo("crossing" to AnalysisDemand.NONE)

        // Still standing still: no automatic restart loop.
        clock.advance(10_000)
        sm.tick()
        assertThat(sm.state.value).isEqualTo(OBSERVATION_COMPLETE)

        sm.onUserRequestObservation()
        assertThat(sm.state.value).isEqualTo(OBSERVING_TRAFFIC)
        assertThat(feed.demandCalls.map { it.second })
            .containsExactly(AnalysisDemand.OBSERVING, AnalysisDemand.NONE, AnalysisDemand.OBSERVING).inOrder()
    }

    @Test
    fun resumedWalking_completesObservation_thenBackToNavigation() {
        startObservingViaStationary()
        val events = sm.onActivity(MotionActivity.WALKING)
        assertThat(events.states()).containsExactly(OBSERVATION_COMPLETE, APPROACH_CONTEXT_AVAILABLE).inOrder()
        assertThat(feed.demandCalls.last().second).isEqualTo(AnalysisDemand.NONE)
    }

    // ---- Device bug: one tap of "Traffic" flickered demand OBSERVING → NONE → (seconds later) OBSERVING

    @Test
    fun userRequest_walkingReportedAtStart_doesNotEndObservation_demandStaysObserving() {
        // IDLE → CROSSING_ASSIST: the activity source starts together with the observation and a single
        // step event (the user just walked up / moved the phone to tap) reports WALKING for ~2 s.
        sm.onActivity(MotionActivity.UNKNOWN)
        sm.onUserRequestObservation()
        assertThat(sm.state.value).isEqualTo(OBSERVING_TRAFFIC)
        sm.onActivity(MotionActivity.WALKING)
        assertThat(sm.state.value).isEqualTo(OBSERVING_TRAFFIC)

        // Warm-up inputs of a session without GPS must not release demand either.
        sm.onCameraState(CameraState.Starting)
        sm.onContext(Fx.context(location = null, accuracyM = null, userPosition = UserPositionAssessment.UNKNOWN))
        sm.onFrameQualityIssues(setOf(UncertaintyReason.BLUR))
        sm.onFrameQualityIssues(emptySet())
        sm.onCameraState(CameraState.Running(null, torchOn = false, hasTorch = true))
        clock.advance(19_000)
        sm.tick()
        assertThat(sm.state.value).isEqualTo(OBSERVING_TRAFFIC)
        assertThat(feed.demandCalls).containsExactly("crossing" to AnalysisDemand.OBSERVING)

        // The observation still ends on its own timeout.
        clock.advance(1_000)
        assertThat(sm.tick().states()).containsExactly(OBSERVATION_COMPLETE)
        assertThat(feed.demandCalls.map { it.second })
            .containsExactly(AnalysisDemand.OBSERVING, AnalysisDemand.NONE).inOrder()
    }

    @Test
    fun userRequest_walkingAfterStandingStill_stillCompletesObservation() {
        sm.onActivity(MotionActivity.WALKING)
        sm.onUserRequestObservation()
        sm.onActivity(MotionActivity.STATIONARY) // the user stood still while observing
        val events = sm.onActivity(MotionActivity.WALKING) // ...and walked on
        assertThat(events.states()).containsExactly(OBSERVATION_COMPLETE, APPROACH_CONTEXT_AVAILABLE).inOrder()
        assertThat(feed.demandCalls.last()).isEqualTo("crossing" to AnalysisDemand.NONE)
    }

    @Test
    fun userRequest_afterEarlierSessionElsewhere_staleContextDoesNotAnchorAndExit() {
        // Earlier session at USER, then stopped.
        startObservingViaStationary()
        sm.onUserStop()
        assertThat(sm.state.value).isEqualTo(INACTIVE)

        // Later, 300 m away, the user asks again before any fresh context exists.
        sm.onUserRequestObservation()
        val elsewhere = Fx.at(300.0, 0.0)
        val events = sm.onContext(Fx.context(location = elsewhere, crossings = emptyList()))
        assertThat(events.states()).isEmpty()
        assertThat(sm.state.value).isEqualTo(OBSERVING_TRAFFIC)
        assertThat(feed.demandCalls.last()).isEqualTo("crossing" to AnalysisDemand.OBSERVING)
    }

    @Test
    fun qualityIssuesAndCameraUnavailable_toggleUncertain() {
        startObservingViaStationary()
        val degraded = sm.onFrameQualityIssues(setOf(UncertaintyReason.LOW_LIGHT, UncertaintyReason.TRACK_TOO_SHORT))
        val change = degraded.single() as CrossingAssistEvent.StateChanged
        assertThat(change.to).isEqualTo(OBSERVATION_UNCERTAIN)
        assertThat(change.viewIssues).containsExactly(UncertaintyReason.LOW_LIGHT)

        val more = sm.onCameraState(CameraState.Unavailable(UncertaintyReason.CAMERA_UNAVAILABLE))
        assertThat((more.single() as CrossingAssistEvent.ViewIssuesChanged).viewIssues)
            .containsExactly(UncertaintyReason.LOW_LIGHT, UncertaintyReason.CAMERA_UNAVAILABLE)

        sm.onFrameQualityIssues(emptySet())
        assertThat(sm.state.value).isEqualTo(OBSERVATION_UNCERTAIN)
        val restored = sm.onCameraState(CameraState.Running(null, torchOn = false, hasTorch = true))
        assertThat(restored.states()).containsExactly(OBSERVING_TRAFFIC)
    }

    @Test
    fun cameraUnavailableAtStart_startsUncertain() {
        sm.onCameraState(CameraState.Unavailable(UncertaintyReason.CAMERA_UNAVAILABLE))
        sm.onContext(nearContext())
        sm.onUserRequestObservation()
        assertThat(sm.state.value).isEqualTo(OBSERVATION_UNCERTAIN)
        assertThat(sm.viewIssues.value).containsExactly(UncertaintyReason.CAMERA_UNAVAILABLE)
    }

    // ---- Brief 19: stale observations cleared between segments

    @Test
    fun brief19_segmentTransition_resetsHistory_andStartsFresh() {
        startObservingViaStationary(nearContext(RefugeStatus.MAPPED))
        val resetsBefore = resets
        assertThat(resetsBefore).isEqualTo(1) // every observation starts from zero

        clock.advance(15_000)
        val events = sm.onUserNextSegment()
        assertThat(events.states()).containsExactly(SEGMENT_TRANSITION, OBSERVING_TRAFFIC).inOrder()
        assertThat(resets).isEqualTo(resetsBefore + 1)
        assertThat(sm.stage.value).isEqualTo(CrossingStage.REFUGE)

        // The observation window restarted: 15 s later it has not timed out yet.
        clock.advance(15_000)
        sm.tick()
        assertThat(sm.state.value).isEqualTo(OBSERVING_TRAFFIC)
    }

    // ---- Brief 20: UNKNOWN refuge does not complete a segment

    @Test
    fun brief20_unknownRefuge_doesNotAdvanceStage_andTimeNeverCompletesSegment() {
        startObservingViaStationary(nearContext(RefugeStatus.UNKNOWN))
        assertThat(sm.stage.value).isEqualTo(CrossingStage.ROAD_SEGMENT_1)

        // Time alone never advances a stage.
        repeat(10) {
            clock.advance(30_000)
            sm.tick()
        }
        assertThat(sm.stage.value).isEqualTo(CrossingStage.ROAD_SEGMENT_1)

        sm.onUserNextSegment()
        assertThat(sm.stage.value).isEqualTo(CrossingStage.UNKNOWN)
        assertThat(sm.stage.value).isNotEqualTo(CrossingStage.REFUGE)
        assertThat(sm.stage.value).isNotEqualTo(CrossingStage.ROAD_SEGMENT_2)

        sm.onUserNextSegment()
        assertThat(sm.stage.value).isEqualTo(CrossingStage.UNKNOWN)

        // Only explicit user confirmation establishes the refuge.
        sm.onUserConfirmedRefuge()
        assertThat(sm.stage.value).isEqualTo(CrossingStage.REFUGE)
        sm.onUserNextSegment()
        assertThat(sm.stage.value).isEqualTo(CrossingStage.ROAD_SEGMENT_2)
    }

    @Test
    fun brief20_notMappedRefuge_alsoUnknownStage() {
        startObservingViaStationary(nearContext(RefugeStatus.NOT_MAPPED))
        sm.onUserNextSegment()
        assertThat(sm.stage.value).isEqualTo(CrossingStage.UNKNOWN)
    }

    @Test
    fun mappedRefuge_advancesThroughStagesOnUserActionOnly() {
        startObservingViaStationary(nearContext(RefugeStatus.MAPPED))
        sm.onActivity(MotionActivity.WALKING) // crosses segment 1
        assertThat(sm.state.value).isEqualTo(APPROACH_CONTEXT_AVAILABLE)
        assertThat(sm.stage.value).isEqualTo(CrossingStage.ROAD_SEGMENT_1)

        sm.onUserNextSegment() // at the refuge
        assertThat(sm.state.value).isEqualTo(OBSERVING_TRAFFIC)
        assertThat(sm.stage.value).isEqualTo(CrossingStage.REFUGE)
    }

    // ---- Brief 5 (state machine side): only the user confirms position

    @Test
    fun brief05_gpsProximityStaysUnconfirmed_untilUserConfirms() {
        sm.onContext(nearContext())
        assertThat(sm.userPosition.value).isEqualTo(UserPositionAssessment.NEAR_UNCONFIRMED)
        startObservingViaStationary()
        assertThat(sm.userPosition.value).isEqualTo(UserPositionAssessment.NEAR_UNCONFIRMED)
        sm.onUserConfirmedAtCrossing()
        assertThat(sm.userPosition.value).isEqualTo(UserPositionAssessment.USER_CONFIRMED)
        sm.onContext(nearContext())
        assertThat(sm.userPosition.value).isEqualTo(UserPositionAssessment.USER_CONFIRMED)
        sm.onUserStop()
        assertThat(sm.userPosition.value).isNotEqualTo(UserPositionAssessment.USER_CONFIRMED)
    }

    // ---- Exits

    @Test
    fun movingAwayBeyond30mPlusAccuracy_exits() {
        startObservingViaStationary()
        val crossing = Fx.at(8.0, 0.0)
        sm.onContext(Fx.context(location = Fx.at(-25.0, 0.0, crossing), accuracyM = 10f)) // 25 m, within accuracy margin
        assertThat(sm.state.value).isEqualTo(OBSERVING_TRAFFIC)
        val events = sm.onContext(Fx.context(location = Fx.at(-45.0, 0.0, crossing), accuracyM = 5f))
        assertThat(events.states()).containsExactly(EXITED, INACTIVE).inOrder()
        assertThat(feed.demandCalls.last().second).isEqualTo(AnalysisDemand.NONE)
        assertThat(sm.stage.value).isEqualTo(CrossingStage.SIDEWALK)
    }

    @Test
    fun routeEnded_exits() {
        startObservingViaStationary()
        assertThat(sm.onRouteEnded().states()).containsExactly(EXITED, INACTIVE).inOrder()
        assertThat(feed.demandCalls.last().second).isEqualTo(AnalysisDemand.NONE)
    }

    @Test
    fun afterUserStop_sameCrossingDoesNotRetrigger_untilUserMovesAway() {
        sm.onContext(nearContext())
        sm.onUserStop()
        sm.onContext(nearContext())
        assertThat(sm.state.value).isEqualTo(INACTIVE)

        sm.onContext(Fx.context(location = Fx.at(-50.0, 0.0), crossings = emptyList()))
        sm.onContext(nearContext())
        assertThat(sm.state.value).isEqualTo(APPROACH_CONTEXT_AVAILABLE)
    }

    @Test
    fun userRequest_worksWithoutMapDataOrGps() {
        sm.onContext(Fx.context(location = null, accuracyM = null, userPosition = UserPositionAssessment.UNKNOWN,
            uncertainties = setOf(UncertaintyReason.GPS_UNAVAILABLE, UncertaintyReason.MAP_DATA_UNAVAILABLE)))
        assertThat(sm.state.value).isEqualTo(INACTIVE)
        sm.onUserRequestObservation()
        assertThat(sm.state.value).isEqualTo(OBSERVING_TRAFFIC)
        assertThat(feed.demandCalls).containsExactly("crossing" to AnalysisDemand.OBSERVING)
    }

    @Test
    fun events_areAlsoEmittedOnSharedFlow() = runTest {
        sm.events.test {
            sm.onUserRequestObservation()
            assertThat((awaitItem() as CrossingAssistEvent.StateChanged).to).isEqualTo(USER_STOPPED_FOR_OBSERVATION)
            assertThat((awaitItem() as CrossingAssistEvent.StageChanged).to).isEqualTo(CrossingStage.ROAD_SEGMENT_1)
            assertThat((awaitItem() as CrossingAssistEvent.StateChanged).to).isEqualTo(OBSERVING_TRAFFIC)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
