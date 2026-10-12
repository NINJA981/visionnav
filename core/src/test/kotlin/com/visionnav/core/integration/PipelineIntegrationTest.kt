package com.visionnav.core.integration

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.announce.Announcement
import com.visionnav.core.announce.AnnouncementPolicy
import com.visionnav.core.announce.AnnouncementPriority
import com.visionnav.core.announce.AudioQueueManager
import com.visionnav.core.announce.SafetyPhraseGuard
import com.visionnav.core.announce.testing.FakeEarconPlayer
import com.visionnav.core.announce.testing.FakeHapticPlayer
import com.visionnav.core.announce.testing.FakeSpeaker
import com.visionnav.core.common.FakeTimeSource
import com.visionnav.core.common.Reliability
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.crossing.CameraOrientationEstimate
import com.visionnav.core.crossing.CrossingAssistEvent
import com.visionnav.core.crossing.CrossingAssistState
import com.visionnav.core.crossing.CrossingAssistStateMachine
import com.visionnav.core.crossing.FakeFrameFeed
import com.visionnav.core.crossing.TrafficObservationReporter
import com.visionnav.core.motion.MotionEstimator
import com.visionnav.core.safety.SafetyAnnouncer
import com.visionnav.core.safety.SafetyRiskEngine
import com.visionnav.core.tracking.DetectionFilter
import com.visionnav.core.tracking.ObjectTracker
import com.visionnav.core.vision.AnalysisDemand
import com.visionnav.core.vision.Detection
import com.visionnav.core.vision.NormBox
import com.visionnav.core.vision.ObjectClass
import org.junit.Before
import org.junit.Test

/**
 * Wires the real components exactly as the app's VisionNavEngine does
 * (filter → tracker → motion → risk → announcer, plus crossing state machine → reporter, all into one
 * AudioQueueManager) and drives them with synthetic detection sequences.
 */
class PipelineIntegrationTest {

    private lateinit var time: FakeTimeSource
    private lateinit var speaker: FakeSpeaker
    private lateinit var audio: AudioQueueManager
    private lateinit var feed: FakeFrameFeed
    private lateinit var tracker: ObjectTracker
    private lateinit var crossing: CrossingAssistStateMachine
    private lateinit var reporter: TrafficObservationReporter
    private val filter = DetectionFilter()
    private val estimator = MotionEstimator()
    private val risk = SafetyRiskEngine()
    private val safetyAnnouncer = SafetyAnnouncer()
    private val guardBlocks = mutableListOf<String>()
    private val unknownOrientation = CameraOrientationEstimate(null, Reliability.UNKNOWN, setOf(UncertaintyReason.HEADING_UNAVAILABLE))

    @Before
    fun setUp() {
        time = FakeTimeSource(10_000)
        speaker = FakeSpeaker()
        audio = AudioQueueManager(speaker, FakeEarconPlayer(), FakeHapticPlayer(), time, SafetyPhraseGuard { guardBlocks += it }, AnnouncementPolicy(time))
        feed = FakeFrameFeed()
        tracker = ObjectTracker()
        crossing = CrossingAssistStateMachine(feed, { tracker.reset() }, time)
        reporter = TrafficObservationReporter(onDefect = { guardBlocks += it })
    }

    private fun submit(a: Announcement) = audio.submit(a.copy(createdAtMs = time.elapsedMs()))

    private fun List<CrossingAssistEvent>.speak() = forEach { e -> reporter.onAssistEvent(e, unknownOrientation).forEach(::submit) }

    /** One analysed frame through the whole pipeline. */
    private fun frame(detections: List<Detection>, finishSpeech: Boolean = true) {
        val now = time.elapsedMs()
        val tracks = tracker.update(filter.filter(detections), now, 0f)
        val estimates = estimator.estimateAll(tracks, now, 66f)
        safetyAnnouncer.announcementsFor(risk.evaluate(tracks, estimates, now)).forEach(::submit)
        crossing.onFrameQualityIssues(emptySet()).speak()
        reporter.onMotion(estimates.values.toList(), unknownOrientation, null, 66f, now).forEach(::submit)
        crossing.tick(now).speak()
        audio.tick()
        if (finishSpeech) speaker.finishAll()
        time.advance(100)
    }

    private fun car(cx: Float, w: Float = 0.16f, h: Float = 0.12f, top: Float = 0.42f) =
        Detection(ObjectClass.CAR, 0.85f, NormBox(cx - w / 2, top, cx + w / 2, top + h))

    @Test
    fun `car crossing the view left to right is described in camera-view terms during observation`() {
        crossing.onUserRequestObservation().speak()
        assertThat(crossing.state.value).isEqualTo(CrossingAssistState.OBSERVING_TRAFFIC)
        // Observation reuses the single camera session through demand only.
        assertThat(feed.demandCalls.map { it.second }).contains(AnalysisDemand.OBSERVING)
        assertThat(feed.demandCalls.map { it.first }.toSet()).containsExactly("crossing")

        for (i in 0 until 20) frame(listOf(car(0.20f + i * 0.025f)))

        val spoken = speaker.spokenTexts
        assertThat(spoken).contains("Car moving left to right in the camera view.")
        // Unreliable orientation: no world-frame claims.
        assertThat(spoken.none { it.contains("your left") || it.contains("your right") }).isTrue()
        // No duplicate spam for the same track/state.
        assertThat(spoken.count { it == "Car moving left to right in the camera view." }).isEqualTo(1)
        assertThat(guardBlocks).isEmpty()
    }

    @Test
    fun `empty road produces no reassurance at all`() {
        crossing.onUserRequestObservation().speak()
        repeat(40) { frame(emptyList()) }
        val spoken = speaker.spokenTexts
        assertThat(spoken.none { SafetyPhraseGuard().check(it) is SafetyPhraseGuard.Verdict.Blocked }).isTrue()
        assertThat(spoken.none { it.contains("clear", ignoreCase = true) || it.contains("safe", ignoreCase = true) }).isTrue()
        assertThat(spoken.none { it.contains("No vehicles", ignoreCase = true) }).isTrue()
    }

    @Test
    fun `critical safety alert preempts an in-flight traffic description`() {
        crossing.onUserRequestObservation().speak()
        speaker.finishAll()
        time.advance(3_000) // past the ambient rate-limit window
        // A long OBSERVATION is being spoken and not finished.
        audio.submit(Announcement("Car moving left to right in the camera view.", AnnouncementPriority.OBSERVATION, "obs:test", createdAtMs = time.elapsedMs()))
        assertThat(speaker.currentText).isEqualTo("Car moving left to right in the camera view.")

        // A car fills the lower centre of the view and keeps growing → CRITICAL.
        for (i in 0 until 8) {
            val h = 0.45f + i * 0.04f
            frame(listOf(Detection(ObjectClass.CAR, 0.9f, NormBox(0.25f, 1f - h - 0.02f, 0.75f, 0.98f))), finishSpeech = false)
        }
        val critical = speaker.spoken.map { it.text }.firstOrNull { it.startsWith("Stop.") }
        assertThat(critical).isNotNull()
        assertThat(speaker.currentText).startsWith("Stop.")
    }

    @Test
    fun `next segment clears stale vehicle history before observing again`() {
        crossing.onUserRequestObservation().speak()
        for (i in 0 until 15) frame(listOf(car(0.20f + i * 0.03f)))
        assertThat(tracker.confirmedTracks()).isNotEmpty()

        crossing.onUserConfirmedRefuge().speak()
        crossing.onUserNextSegment().speak()
        assertThat(tracker.confirmedTracks()).isEmpty()
        assertThat(crossing.state.value).isAnyOf(CrossingAssistState.OBSERVING_TRAFFIC, CrossingAssistState.SEGMENT_TRANSITION)
    }

    @Test
    fun `stopping releases camera demand`() {
        crossing.onUserRequestObservation().speak()
        crossing.onUserStop().speak()
        assertThat(feed.demandCalls.last()).isEqualTo("crossing" to AnalysisDemand.NONE)
    }
}
