package com.visionnav.app.ui.contract

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.visionnav.core.crossing.CrossingAssistState
import com.visionnav.core.crossing.CrossingStage
import com.visionnav.core.safety.SafetySeverity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeAppControllerTest {

    private val tick = 100L

    private fun TestScope.controller() = FakeAppController(backgroundScope, tickMs = tick)

    /** StateFlow conflates; skip intermediate items until [predicate] holds. */
    private suspend fun ReceiveTurbine<AppUiState>.awaitState(predicate: (AppUiState) -> Boolean): AppUiState {
        while (true) {
            val s = awaitItem()
            if (predicate(s)) return s
        }
    }

    @Test
    fun initialState_isIdleAndReportsMockDetectorHonestly() = runTest {
        val c = controller()
        val s = c.state.value
        assertThat(s.sessionMode).isEqualTo(SessionMode.IDLE)
        assertThat(s.camera.isMockDetector).isTrue()
        assertThat(s.camera.detectorModelName).isNotNull()
        assertThat(s.savedPlaces).isNotEmpty()
        assertThat(s.navigation).isNull()
    }

    @Test
    fun search_loadsThenReturnsMatchingChennaiPlaces() = runTest {
        val c = controller()
        c.state.test {
            awaitItem()
            c.dispatch(UiAction.Search("museum"))
            assertThat(awaitState { it.search.isLoading }.search.query).isEqualTo("museum")
            val done = awaitState { !it.search.isLoading && it.search.hasSearched }
            assertThat(done.search.results.map { it.name }).contains("Government Museum")
            assertThat(done.search.error).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun search_noMatchAndOfflineAndBlank() = runTest {
        val c = controller()
        c.state.test {
            awaitItem()
            c.dispatch(UiAction.Search("zzqx"))
            val none = awaitState { it.search.hasSearched && !it.search.isLoading }
            assertThat(none.search.results).isEmpty()
            assertThat(none.search.error).isNull()

            c.dispatch(UiAction.Search("offline cafe"))
            val offline = awaitState { it.search.error != null }
            assertThat(offline.search.results).isEmpty()

            c.dispatch(UiAction.Search("   "))
            val blank = awaitState { it.search.query.isEmpty() && it.search.error != null }
            assertThat(blank.search.isLoading).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun navigation_previewStartAdvanceArriveStop() = runTest {
        val c = controller()
        val place = FakeSampleData.places.first { it.name == "Spencer Plaza" }
        c.state.test {
            awaitItem()
            c.dispatch(UiAction.SelectPlace(place))
            val preview = awaitState { it.search.preview != null }.search.preview!!
            assertThat(preview.destination).isEqualTo(place)
            assertThat(preview.firstInstruction).isNotEmpty()

            c.dispatch(UiAction.StartNavigation)
            val started = awaitState { it.sessionMode == SessionMode.NAVIGATING && it.navigation != null }
            assertThat(started.navigation!!.destinationName).isEqualTo("Spencer Plaza")
            assertThat(started.navigation!!.progressFraction).isEqualTo(0f)
            assertThat(started.camera.level).isEqualTo(StatusLevel.GOOD)

            val moved = awaitState { (it.navigation?.progressFraction ?: 0f) > 0.1f }
            assertThat(moved.navigation!!.distanceToNextText).isNotNull()

            val warned = awaitState { it.safetyAlert != null }
            assertThat(warned.safetyAlert!!.severity).isEqualTo(SafetySeverity.WARNING)
            awaitState { it.safetyAlert == null }

            val arrived = awaitState { it.navigation?.hasArrived == true }
            assertThat(arrived.navigation!!.progressFraction).isEqualTo(1f)
            assertThat(arrived.sessionMode).isEqualTo(SessionMode.NAVIGATING)

            c.dispatch(UiAction.StopNavigation)
            val stopped = awaitState { it.sessionMode == SessionMode.IDLE }
            assertThat(stopped.navigation).isNull()
            assertThat(stopped.camera.level).isEqualTo(StatusLevel.OFF)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun crossingAssist_fromNavigation_observesConfirmsSegmentsAndReturns() = runTest {
        val c = controller()
        c.state.test {
            awaitItem()
            c.dispatch(UiAction.SelectPlace(FakeSampleData.places.last()))
            awaitState { it.search.preview != null }
            c.dispatch(UiAction.StartNavigation)
            awaitState { it.sessionMode == SessionMode.NAVIGATING }

            c.dispatch(UiAction.StartTrafficObservation)
            val starting = awaitState { it.sessionMode == SessionMode.CROSSING_ASSIST }
            assertThat(starting.crossing.state).isEqualTo(CrossingAssistState.USER_STOPPED_FOR_OBSERVATION)
            assertThat(starting.crossing.refugeMapped).isTrue()
            assertThat(starting.navigation).isNotNull()

            awaitState { it.crossing.state == CrossingAssistState.OBSERVING_TRAFFIC }
            val two = awaitState { it.crossing.observations.size >= 2 }
            // Newest first.
            assertThat(two.crossing.observations[0]).isEqualTo(FakeSampleData.observationSamples[1])
            assertThat(two.crossing.observations[1]).isEqualTo(FakeSampleData.observationSamples[0])

            val unclear = awaitState { it.crossing.state == CrossingAssistState.OBSERVATION_UNCERTAIN }
            assertThat(unclear.crossing.observations.first()).isEqualTo(FakeSampleData.LOW_LIGHT_OBSERVATION)
            assertThat(unclear.torch.lowLight).isTrue()

            c.dispatch(UiAction.ConfirmAtCrossing)
            val confirmed = awaitState { it.crossing.userConfirmedAtCrossing }
            assertThat(confirmed.crossing.stage).isEqualTo(CrossingStage.SIDEWALK)

            c.dispatch(UiAction.NextSegment)
            val segment = awaitState { it.crossing.state == CrossingAssistState.SEGMENT_TRANSITION }
            assertThat(segment.crossing.observations).isEmpty()
            assertThat(segment.crossing.stage).isEqualTo(CrossingStage.REFUGE)
            awaitState { it.crossing.state == CrossingAssistState.OBSERVING_TRAFFIC }

            c.dispatch(UiAction.StopTrafficObservation)
            val back = awaitState { it.sessionMode == SessionMode.NAVIGATING }
            assertThat(back.crossing.state).isEqualTo(CrossingAssistState.INACTIVE)
            assertThat(back.navigation).isNotNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun crossingAssist_fromIdle_returnsToIdle() = runTest {
        val c = controller()
        c.dispatch(UiAction.StartTrafficObservation)
        assertThat(c.state.value.sessionMode).isEqualTo(SessionMode.CROSSING_ASSIST)
        assertThat(c.state.value.crossing.contextText).isEqualTo(FakeSampleData.CROSSING_CONTEXT_UNKNOWN)
        advanceTimeBy(tick * 4)
        c.dispatch(UiAction.StopTrafficObservation)
        assertThat(c.state.value.sessionMode).isEqualTo(SessionMode.IDLE)
        assertThat(c.state.value.camera.level).isEqualTo(StatusLevel.OFF)
    }

    @Test
    fun sos_neverSendsWithoutConfirmation() = runTest {
        val c = controller()
        c.dispatch(UiAction.TriggerSos)
        assertThat(c.state.value.sos).isInstanceOf(SosUi.AwaitingConfirmation::class.java)
        advanceTimeBy(tick * 50)
        assertThat(c.state.value.sos).isInstanceOf(SosUi.AwaitingConfirmation::class.java)
        c.dispatch(UiAction.CancelSos)
        assertThat(c.state.value.sos).isEqualTo(SosUi.Idle)

        c.state.test {
            awaitItem()
            c.dispatch(UiAction.TriggerSos)
            awaitState { it.sos is SosUi.AwaitingConfirmation }
            c.dispatch(UiAction.ConfirmSos)
            awaitState { it.sos == SosUi.Sending }
            val sent = awaitState { it.sos is SosUi.Sent }
            assertThat((sent.sos as SosUi.Sent).message).contains("no message was sent")
            c.dispatch(UiAction.DismissSosResult)
            awaitState { it.sos == SosUi.Idle }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun voiceCommand_listensThenRunsWhereAmI() = runTest {
        val c = controller()
        c.state.test {
            awaitItem()
            c.dispatch(UiAction.StartVoiceCommand)
            awaitState { it.isListening }
            val done = awaitState { !it.isListening && it.lastSpoken != null }
            assertThat(done.voiceTranscript).isEqualTo("where am I")
            assertThat(done.lastSpoken).contains("Anna Salai")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun savedPlaces_saveRenameDelete() = runTest {
        val c = controller()
        val before = c.state.value.savedPlaces.size
        c.dispatch(UiAction.SavePlace("  Bus stop  "))
        val added = c.state.value.savedPlaces.last()
        assertThat(c.state.value.savedPlaces).hasSize(before + 1)
        assertThat(added.name).isEqualTo("Bus stop")
        c.dispatch(UiAction.RenameSavedPlace(added.id, "Bus stop 21G"))
        assertThat(c.state.value.savedPlaces.last().name).isEqualTo("Bus stop 21G")
        c.dispatch(UiAction.DeleteSavedPlace(added.id))
        assertThat(c.state.value.savedPlaces).hasSize(before)
    }

    @Test
    fun torch_onlyWhileCameraRuns() = runTest {
        val c = controller()
        c.dispatch(UiAction.ToggleTorch)
        assertThat(c.state.value.torch.on).isFalse()
        c.dispatch(UiAction.StartExploring)
        assertThat(c.state.value.sessionMode).isEqualTo(SessionMode.EXPLORING)
        c.dispatch(UiAction.ToggleTorch)
        assertThat(c.state.value.torch.on).isTrue()
        c.dispatch(UiAction.StopExploring)
        assertThat(c.state.value.torch).isEqualTo(TorchUi())
    }

    @Test
    fun repeatLast_reEmitsLastUtterance() = runTest {
        val c = controller()
        c.dispatch(UiAction.WhatsAround)
        val said = c.state.value.lastSpoken
        c.dispatch(UiAction.RepeatLast)
        assertThat(c.state.value.lastSpoken).isEqualTo(said)
        assertThat(said).startsWith("Demo detector")
    }
}
