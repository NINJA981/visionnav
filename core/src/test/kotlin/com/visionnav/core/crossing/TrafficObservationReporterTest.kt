package com.visionnav.core.crossing

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.announce.Announcement
import com.visionnav.core.announce.AnnouncementPriority
import com.visionnav.core.announce.SafetyPhraseGuard
import com.visionnav.core.common.FakeTimeSource
import com.visionnav.core.common.Reliability
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.crossing.Fx.estimate
import com.visionnav.core.crossing.Fx.reliable
import com.visionnav.core.crossing.Fx.unreliable
import com.visionnav.core.sensing.MotionActivity
import com.visionnav.core.tracking.ApproachState
import com.visionnav.core.tracking.ImageMotion
import com.visionnav.core.tracking.MotionEstimate
import com.visionnav.core.vision.CameraState
import com.visionnav.core.vision.ObjectClass
import org.junit.Test

class TrafficObservationReporterTest {

    private val clock = FakeTimeSource(1_000)
    private val feed = FakeFrameFeed()
    private var resets = 0
    private val defects = mutableListOf<String>()
    private val sm = CrossingAssistStateMachine(feed, { resets++ }, clock)
    private val reporter = TrafficObservationReporter(onDefect = { defects += it })
    private val spoken = mutableListOf<Announcement>()

    /** One-way road flowing east, 10 m north of the user; camera faces north (road across the view). */
    private val oneWayEast = Fx.road(RoadDirectionality.ONE_WAY, listOf(90.0))
    private val oneWayContext = Fx.context(roads = listOf(oneWayEast), crossings = listOf(Fx.mappedCrossing()))

    private fun drive(events: List<CrossingAssistEvent>, orientation: CameraOrientationEstimate? = null): List<Announcement> =
        events.flatMap { reporter.onAssistEvent(it, orientation) }.also { spoken += it }

    private fun motion(
        vararg estimates: MotionEstimate,
        orientation: CameraOrientationEstimate = unreliable(),
        context: RoadCrossingContext? = oneWayContext,
    ): List<String> = reporter.onMotion(estimates.toList(), orientation, context, 66f, clock.now).also { spoken += it }.map { it.text }

    private fun startObserving(ctx: RoadCrossingContext? = oneWayContext) {
        if (ctx != null) drive(sm.onContext(ctx))
        drive(sm.onUserRequestObservation())
        assertThat(reporter.isObserving).isTrue()
    }

    private fun assertNoForbidden(texts: Collection<String>) {
        val guard = SafetyPhraseGuard()
        for (t in texts) {
            assertThat(guard.isAllowed(t)).isTrue()
            val lower = t.lowercase()
            assertThat(lower).doesNotContain("safe")
            assertThat(lower).doesNotContain("cross now")
            assertThat(lower.replace("unclear", "")).doesNotContain("clear")
        }
    }

    // ---- Context phrases

    @Test
    fun contextPhrases_crossingKindAndRoadDirection() {
        val texts = drive(sm.onContext(oneWayContext), unreliable()).map { it.text }
        assertThat(texts).containsExactly("Mapped crossing with traffic signals ahead.", "One-way road.").inOrder()
        assertThat(spoken.map { it.priority }.toSet()).containsExactly(AnnouncementPriority.NAVIGATION)

        val possible = Fx.context(
            roads = listOf(Fx.road(RoadDirectionality.TWO_WAY, listOf(90.0, 270.0))),
            crossings = listOf(CrossingCandidate.PossibleCrossing(Fx.at(10.0, 0.0), null, 10.0)),
        )
        val possibleOut = TrafficObservationReporter().describeContext(possible, null, 0)
        assertThat(possibleOut.map { it.text })
            .containsExactly("Possible crossing ahead. Stop and assess your surroundings.", "Two-way road.").inOrder()
        assertThat(possibleOut.first().priority).isEqualTo(AnnouncementPriority.NAVIGATION)

        val refuge = Fx.context(crossings = listOf(Fx.mappedCrossing(refuge = RefugeStatus.MAPPED, control = CrossingControl.UNKNOWN)))
        assertThat(TrafficObservationReporter().describeContext(refuge, null, 0).first().text).isEqualTo("Crossing ahead. Refuge island mapped.")
    }

    // ---- Brief 11: unreliable orientation → image-space wording only

    @Test
    fun brief11_unreliableOrientation_usesImageWordingOnly() {
        startObserving()
        val texts = motion(estimate(1, imageMotion = ImageMotion.MOVING_RIGHT_IN_IMAGE), estimate(2, imageMotion = ImageMotion.MOVING_LEFT_IN_IMAGE), orientation = unreliable())
        assertThat(texts).containsExactly(
            "Car moving left to right in the camera view.",
            "Car moving right to left in the camera view.",
        )
        assertThat(reporter.roadPhrase(oneWayContext, unreliable())).isEqualTo("One-way road.")
        val all = spoken.map { it.text.lowercase() }
        assertThat(all.none { "your left" in it || "your right" in it }).isTrue()
    }

    @Test
    fun brief11_reliableOrientation_allowsWorldWording() {
        startObserving()
        val texts = motion(estimate(1, cls = ObjectClass.BUS, imageMotion = ImageMotion.MOVING_RIGHT_IN_IMAGE), orientation = reliable(0.0))
        assertThat(texts).containsExactly("Bus passing from your left.")
        assertThat(reporter.roadPhrase(oneWayContext, reliable(0.0))).isEqualTo("One-way road. Traffic expected from your left.")
        assertThat(reporter.roadPhrase(oneWayContext, reliable(180.0))).isEqualTo("One-way road. Traffic expected from your right.")
        // Facing along the road: no side claim.
        assertThat(reporter.roadPhrase(oneWayContext, reliable(90.0))).isEqualTo("One-way road.")
        // Roundabout (inferred): never a side claim.
        val roundabout = oneWayContext.copy(roads = listOf(Fx.road(RoadDirectionality.ONE_WAY, listOf(90.0), Reliability.INFERRED)))
        assertThat(reporter.roadPhrase(roundabout, reliable(0.0))).isEqualTo("Roundabout. Traffic likely one-way.")
    }

    // ---- Brief 14: unexpected camera observation not discarded

    @Test
    fun brief14_mismatchIsReturned_andObservationStillReported() {
        startObserving()
        val wrongWay = estimate(1, imageMotion = ImageMotion.MOVING_LEFT_IN_IMAGE) // travelling west on an eastbound one-way
        assertThat(FlowFusion().compare(wrongWay, reliable(0.0), oneWayEast, 66f)).isEqualTo(FlowComparison.EXPECTED_FLOW_MISMATCH)

        val first = motion(wrongWay, orientation = reliable(0.0))
        assertThat(first).containsExactly("Car passing from your right.") // reported, not dropped; mismatch not yet persistent

        clock.advance(800)
        assertThat(motion(wrongWay, orientation = reliable(0.0))).isEmpty()
        clock.advance(800)
        assertThat(motion(wrongWay, orientation = reliable(0.0))).containsExactly("Vehicle moving against the expected direction.")
    }

    @Test
    fun brief14_twoMismatchingTracks_confirmImmediately() {
        startObserving()
        val texts = motion(
            estimate(1, imageMotion = ImageMotion.MOVING_LEFT_IN_IMAGE),
            estimate(2, cls = ObjectClass.TRUCK, imageMotion = ImageMotion.MOVING_LEFT_IN_IMAGE),
            orientation = reliable(0.0),
        )
        assertThat(texts).containsExactly(
            "Car passing from your right.",
            "Truck passing from your right.",
            "Vehicle moving against the expected direction.",
        ).inOrder()
    }

    @Test
    fun mismatchWording_neverWithUnreliableOrientation() {
        startObserving()
        repeat(10) {
            motion(estimate(1, imageMotion = ImageMotion.MOVING_LEFT_IN_IMAGE), estimate(2, imageMotion = ImageMotion.MOVING_LEFT_IN_IMAGE), orientation = unreliable())
            clock.advance(500)
        }
        assertThat(spoken.map { it.text }).doesNotContain(CrossingPhrases.AGAINST_EXPECTED)
    }

    // ---- Brief 15: missing map metadata doesn't disable vehicle reporting

    @Test
    fun brief15_missingMapMetadata_stillReportsVehicles() {
        val ctx = RoadContextInterpreter().interpret(metadata = null, location = Fx.fix(), route = null, nowMs = clock.now)
        assertThat(ctx.uncertainties).contains(UncertaintyReason.MAP_DATA_UNAVAILABLE)
        drive(sm.onContext(ctx))
        assertThat(sm.state.value).isEqualTo(CrossingAssistState.INACTIVE)
        val start = drive(sm.onUserRequestObservation()).map { it.text }
        assertThat(start).containsExactly("Observing traffic. Camera sees ahead only.", "Road direction unavailable.").inOrder()

        val texts = motion(estimate(1, imageMotion = ImageMotion.MOVING_RIGHT_IN_IMAGE), estimate(2, cls = ObjectClass.MOTORCYCLE, approach = ApproachState.APPROACHING, imageMotion = ImageMotion.STATIONARY_OR_LOW_MOTION), context = ctx)
        assertThat(texts).containsExactly("Car moving left to right in the camera view.", "Motorcycle approaching.")
        assertThat(motion(estimate(3, cls = ObjectClass.BICYCLE, approach = ApproachState.MOVING_AWAY, imageMotion = ImageMotion.STATIONARY_OR_LOW_MOTION), context = null))
            .containsExactly("Bicycle moving away.")
    }

    // ---- Brief 16: camera observations are not proof of safety

    @Test
    fun brief16_vehicleFreeFrames_produceNothingReassuring() {
        startObserving()
        val before = spoken.size
        repeat(100) {
            assertThat(motion()).isEmpty()
            assertThat(motion(estimate(9, cls = ObjectClass.PERSON), estimate(10, cls = ObjectClass.DOG))).isEmpty()
            clock.advance(200)
            reporter.tick(clock.now).also { spoken += it }
        }
        val during = spoken.drop(before).map { it.text }
        assertThat(during.toSet()).containsExactly(CrossingPhrases.COVERAGE_REMINDER) // only the coverage limit
        assertThat(spoken.drop(before).map { it.priority }.toSet()).containsExactly(AnnouncementPriority.INFO)

        val onRequest = reporter.describeOnRequest(emptyList(), unreliable(), clock.now).map { it.text }
        assertThat(onRequest).containsExactly("No vehicles recognised in the camera view. Camera sees ahead only.")
        assertNoForbidden(spoken.map { it.text } + onRequest)
    }

    @Test
    fun brief16_notObserving_noTrafficOutput() {
        assertThat(reporter.onMotion(listOf(estimate(1)), unreliable(), null, 66f, 0)).isEmpty()
        assertThat(reporter.tick(100_000)).isEmpty()
    }

    // ---- Brief 19: stale observations cleared between segments

    @Test
    fun brief19_segmentTransition_clearsReporterDedupe_andResetsHistory() {
        startObserving(Fx.context(roads = listOf(oneWayEast), crossings = listOf(Fx.mappedCrossing(refuge = RefugeStatus.MAPPED))))
        val car = estimate(7, imageMotion = ImageMotion.MOVING_RIGHT_IN_IMAGE)
        assertThat(motion(car)).hasSize(1)
        assertThat(motion(car)).isEmpty()

        val resetsBefore = resets
        val transition = drive(sm.onUserNextSegment()).map { it.text }
        assertThat(resets).isEqualTo(resetsBefore + 1)
        assertThat(transition).contains("Observing traffic. Camera sees ahead only.")
        assertThat(sm.stage.value).isEqualTo(CrossingStage.REFUGE)

        // Same track id, same state, immediately after: spoken again because the segment started from zero.
        assertThat(motion(car)).containsExactly("Car moving left to right in the camera view.")
    }

    // ---- Brief 21: duplicate observations don't spam

    @Test
    fun brief21_duplicatesCooldownEscalationAndSummary() {
        startObserving()
        val crossingCar = estimate(1, imageMotion = ImageMotion.MOVING_RIGHT_IN_IMAGE)
        var count = 0
        repeat(50) {
            count += motion(crossingCar).size
            clock.advance(100)
        }
        assertThat(count).isEqualTo(1)

        // State change inside the 4 s per-track cooldown is held back…
        val track2 = estimate(2, imageMotion = ImageMotion.MOVING_LEFT_IN_IMAGE)
        assertThat(motion(track2)).hasSize(1)
        clock.advance(1_000)
        val away = estimate(2, imageMotion = ImageMotion.STATIONARY_OR_LOW_MOTION, approach = ApproachState.MOVING_AWAY)
        assertThat(motion(away)).isEmpty()
        // …but an escalation to APPROACHING bypasses it.
        clock.advance(500)
        val approaching = reporter.onMotion(listOf(estimate(2, approach = ApproachState.APPROACHING)), unreliable(), oneWayContext, 66f, clock.now)
        assertThat(approaching.map { it.text }).containsExactly("Car approaching.")
        assertThat(approaching.single().escalation).isTrue()
        assertThat(approaching.single().priority).isEqualTo(AnnouncementPriority.OBSERVATION)

        // After the cooldown, a genuine change is spoken.
        clock.advance(4_000)
        assertThat(motion(away)).containsExactly("Car moving away.")
    }

    @Test
    fun brief21_summaryModeAboveThreeMovingVehicles() {
        startObserving()
        val many = (1L..5L).map { estimate(it, imageMotion = if (it % 2 == 0L) ImageMotion.MOVING_LEFT_IN_IMAGE else ImageMotion.MOVING_RIGHT_IN_IMAGE) }.toTypedArray()
        val texts = mutableListOf<String>()
        repeat(20) {
            texts += motion(*many)
            clock.advance(200)
        }
        assertThat(texts).containsExactly("Several vehicles moving in the camera view.")
    }

    @Test
    fun youngUnknownTrack_staysSilentUntilSeenLongEnough() {
        startObserving()
        val young = estimate(1, imageMotion = ImageMotion.UNKNOWN, approach = ApproachState.UNKNOWN, reasons = setOf(UncertaintyReason.TRACK_TOO_SHORT), spanMs = 300)
        assertThat(motion(young)).isEmpty()
        val older = estimate(1, imageMotion = ImageMotion.UNKNOWN, approach = ApproachState.UNKNOWN, reasons = setOf(UncertaintyReason.TRACK_TOO_SHORT), spanMs = 1_200)
        assertThat(motion(older)).containsExactly("Car detected, direction unclear.")
    }

    // ---- Brief 23: unknown states produce the uncertainty phrases

    @Test
    fun brief23_unknownStatesProduceUncertaintyPhrases() {
        val noGps = Fx.context(location = null, accuracyM = null, crossings = listOf(CrossingCandidate.VisualCrosswalk(0.8f, 1_000)),
            userPosition = UserPositionAssessment.UNKNOWN, uncertainties = setOf(UncertaintyReason.GPS_UNAVAILABLE))
        val ctxTexts = drive(sm.onContext(noGps)).map { it.text }
        assertThat(ctxTexts).containsExactly(
            "Possible crosswalk markings in the camera view.",
            "Road direction unavailable.",
            "Location unavailable.",
        ).inOrder()

        drive(sm.onUserRequestObservation())
        motion(estimate(1, imageMotion = ImageMotion.UNKNOWN, approach = ApproachState.UNKNOWN, reasons = setOf(UncertaintyReason.MOTION_INCONSISTENT)))
        assertThat(spoken.last().text).isEqualTo("Car detected, direction unclear.")

        val cases = listOf(
            setOf(UncertaintyReason.LOW_LIGHT) to "Traffic view unclear. Low light.",
            setOf(UncertaintyReason.CAMERA_MOVING) to "Traffic view unclear. Camera moving.",
            setOf(UncertaintyReason.CAMERA_OBSTRUCTED) to "Camera view blocked.",
        )
        for ((issues, phrase) in cases) {
            val out = drive(sm.onFrameQualityIssues(issues)).map { it.text }
            assertThat(out).containsExactly(phrase)
            drive(sm.onFrameQualityIssues(emptySet()))
            clock.advance(7_000)
        }
        val camera = drive(sm.onCameraState(CameraState.Unavailable(UncertaintyReason.CAMERA_UNAVAILABLE))).map { it.text }
        assertThat(camera).containsExactly("Camera unavailable.")

        val ended = drive(sm.onUserStop()).map { it.text }
        assertThat(ended).containsExactly("Observation ended.")
        assertNoForbidden(spoken.map { it.text })
    }

    @Test
    fun brief23_unknownStageAndUnknownRoad() {
        startObserving(Fx.context(roads = listOf(Fx.road(RoadDirectionality.UNKNOWN, emptyList(), Reliability.UNKNOWN)), crossings = listOf(Fx.mappedCrossing())))
        assertThat(spoken.map { it.text }).contains("Road direction unavailable.")
        val texts = drive(sm.onUserNextSegment()).map { it.text }
        assertThat(texts.first()).isEqualTo("Refuge island not confirmed.")
        assertThat(sm.stage.value).isEqualTo(CrossingStage.UNKNOWN)
    }

    @Test
    fun timeoutAndWalkingEndObservationAudibly() {
        startObserving()
        clock.advance(20_000)
        assertThat(drive(sm.tick()).map { it.text }).containsExactly("Observation ended.")
        assertThat(reporter.isObserving).isFalse()
        drive(sm.onUserRequestObservation())
        // Walking *resumed* after standing still during the observation (a WALKING reading straight
        // after the request is the approach to the kerb and keeps observing; see the state machine test).
        drive(sm.onActivity(MotionActivity.STATIONARY))
        assertThat(drive(sm.onActivity(MotionActivity.WALKING)).map { it.text }).containsExactly("Observation ended.")
    }

    // ---- Brief 24: exhaustive phrase safety

    @Test
    fun brief24_everyCatalogPhrasePassesGuard_andNoneSaysSafeOrCrossNow() {
        val catalog = CrossingPhrases.catalog()
        assertThat(catalog.size).isGreaterThan(60)
        val guard = SafetyPhraseGuard()
        for (p in catalog) {
            assertThat(guard.check(p)).isInstanceOf(SafetyPhraseGuard.Verdict.Allowed::class.java)
            assertThat(p.lowercase()).doesNotContain("safe")
            assertThat(p.lowercase()).doesNotContain("cross now")
        }
        // The brief's vocabulary is all present.
        assertThat(catalog).containsAtLeast(
            "Possible crossing ahead. Stop and assess your surroundings.",
            "Mapped crossing with traffic signals ahead.",
            "Crossing ahead. Refuge island mapped.",
            "One-way road. Traffic expected from your left.",
            "One-way road.",
            "Two-way road.",
            "Road direction unavailable.",
            "Observing traffic. Camera sees ahead only.",
            "Vehicle moving left to right in the camera view.",
            "Vehicle moving right to left in the camera view.",
            "Car passing from your left.",
            "Vehicle approaching.",
            "Vehicle moving away.",
            "Vehicle detected, direction unclear.",
            "Several vehicles moving in the camera view.",
            "Vehicle moving against the expected direction.",
            "Traffic view unclear. Low light.",
            "Traffic view unclear. Camera moving.",
            "Camera view blocked.",
            "Camera unavailable.",
            "Observation ended.",
        )
    }

    @Test
    fun brief24_reporterOutputsUnderExhaustiveInputs_areCatalogedAndAllowed() {
        val catalog = CrossingPhrases.catalog()
        val outputs = mutableListOf<String>()
        val orientations = listOf(reliable(0.0), reliable(135.0), unreliable(), CameraOrientationEstimate(null, Reliability.UNKNOWN, emptySet()))
        val roads = listOf(
            oneWayContext,
            Fx.context(roads = listOf(Fx.road(RoadDirectionality.TWO_WAY, listOf(90.0, 270.0)))),
            Fx.context(roads = listOf(Fx.road(RoadDirectionality.UNKNOWN, emptyList(), Reliability.UNKNOWN))),
            null,
        )
        var id = 0L
        for (o in orientations) for (ctx in roads) {
            val r = TrafficObservationReporter(onDefect = { defects += it })
            val start = CrossingAssistEvent.StateChanged(CrossingAssistState.USER_STOPPED_FOR_OBSERVATION, CrossingAssistState.OBSERVATION_UNCERTAIN,
                TransitionCause.OBSERVATION_STARTED, 0, ctx, UncertaintyReason.entries.toSet())
            outputs += r.onAssistEvent(start, o).map { it.text }
            ctx?.let { outputs += r.describeContext(it, o, 0).map { a -> a.text } }
            var t = 0L
            for (cls in ObjectClass.entries) for (im in ImageMotion.entries) for (ap in ApproachState.entries) {
                val e = estimate(++id, cls = cls, imageMotion = im, approach = ap, reasons = UncertaintyReason.entries.toSet(), spanMs = 2_000)
                outputs += r.onMotion(listOf(e), o, ctx, 66f, t).map { it.text }
                outputs += r.describeOnRequest(listOf(e), o, t).map { it.text }
                t += 5_000
            }
            outputs += r.describeOnRequest(emptyList(), o, t).map { it.text }
            outputs += r.tick(t + 60_000).map { it.text }
            for (reason in UncertaintyReason.entries) {
                outputs += r.onAssistEvent(CrossingAssistEvent.ViewIssuesChanged(setOf(reason), t), o).map { it.text }
            }
            outputs += r.onAssistEvent(CrossingAssistEvent.StageChanged(CrossingStage.ROAD_SEGMENT_1, CrossingStage.UNKNOWN, t)).map { it.text }
            outputs += r.onAssistEvent(CrossingAssistEvent.StateChanged(CrossingAssistState.OBSERVING_TRAFFIC, CrossingAssistState.OBSERVATION_COMPLETE,
                TransitionCause.TIMEOUT, t, ctx, emptySet())).map { it.text }
        }
        assertThat(outputs).isNotEmpty()
        assertThat(defects).isEmpty()
        for (text in outputs) assertThat(catalog).contains(text)
        assertNoForbidden(outputs)
    }
}
