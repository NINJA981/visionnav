package com.visionnav.core.motion

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.tracking.ApproachState
import com.visionnav.core.tracking.ImageMotion
import com.visionnav.core.tracking.ObjectTracker
import com.visionnav.core.tracking.TrackFixtures.boxAt
import com.visionnav.core.tracking.TrackFixtures.boxBottom
import com.visionnav.core.tracking.TrackFixtures.det
import com.visionnav.core.tracking.TrackFixtures.samples
import com.visionnav.core.tracking.TrackFixtures.track
import com.visionnav.core.tracking.TrackSample
import com.visionnav.core.vision.ObjectClass
import org.junit.Test
import kotlin.math.exp

class MotionEstimatorTest {

    private val estimator = MotionEstimator()
    private val hfovRad = Math.toRadians(66.0).toFloat()

    // ---- lateral ---------------------------------------------------------------------------

    @Test
    fun `consistent increasing x is MOVING_RIGHT_IN_IMAGE`() {
        // 0.25 -> 0.30 -> 0.355 -> ... over 700 ms
        val s = samples(8) { i -> boxAt(0.25f + 0.05f * i + 0.0025f * i * (i - 1)) }
        val e = estimator.estimate(track(s), nowMs = 700)
        assertThat(e.imageMotion).isEqualTo(ImageMotion.MOVING_RIGHT_IN_IMAGE)
        assertThat(e.lateralSpeed!!).isGreaterThan(0.08f)
        assertThat(e.confidence).isGreaterThan(0.2f)
        assertThat(e.reasons).isEmpty()
        assertThat(e.approach).isEqualTo(ApproachState.CROSSING) // lateral dominant, scale stable
    }

    @Test
    fun `consistent decreasing x is MOVING_LEFT_IN_IMAGE`() {
        val s = samples(10) { i -> boxAt(0.80f - 0.04f * i) }
        val e = estimator.estimate(track(s), nowMs = 900)
        assertThat(e.imageMotion).isEqualTo(ImageMotion.MOVING_LEFT_IN_IMAGE)
        assertThat(e.lateralSpeed!!).isLessThan(-0.08f)
        assertThat(e.reasons).isEmpty()
    }

    @Test
    fun `single outlier box does not flip a consistent direction`() {
        val s = samples(12) { i -> if (i == 6) boxAt(0.2f) else boxAt(0.3f + 0.03f * i) }
        val e = estimator.estimate(track(s), nowMs = 1_100)
        assertThat(e.imageMotion).isEqualTo(ImageMotion.MOVING_RIGHT_IN_IMAGE)
    }

    @Test
    fun `small noisy jitter is not a confident direction`() {
        val s = samples(12) { i -> boxAt(0.5f + if (i % 2 == 0) 0.01f else -0.01f) }
        val e = estimator.estimate(track(s), nowMs = 1_100)
        assertThat(e.imageMotion).isAnyOf(ImageMotion.STATIONARY_OR_LOW_MOTION, ImageMotion.UNKNOWN)
        assertThat(e.imageMotion).isEqualTo(ImageMotion.STATIONARY_OR_LOW_MOTION)
        assertThat(e.approach).isEqualTo(ApproachState.STATIONARY)
    }

    @Test
    fun `slow consistent drift below thresholds is STATIONARY_OR_LOW_MOTION`() {
        val s = samples(10) { i -> boxAt(0.5f + 0.003f * i) } // 0.03 width/s, net 0.027
        val e = estimator.estimate(track(s), nowMs = 900)
        assertThat(e.imageMotion).isEqualTo(ImageMotion.STATIONARY_OR_LOW_MOTION)
    }

    @Test
    fun `back and forth large motion is UNKNOWN with MOTION_INCONSISTENT`() {
        // Moves right then left: big displacement steps, signs disagree.
        val xs = listOf(0.3f, 0.36f, 0.42f, 0.48f, 0.54f, 0.48f, 0.42f, 0.36f, 0.30f, 0.36f, 0.42f, 0.48f)
        val s = samples(xs.size) { i -> boxAt(xs[i]) }
        val e = estimator.estimate(track(s), nowMs = 1_100)
        assertThat(e.imageMotion).isEqualTo(ImageMotion.UNKNOWN)
        assertThat(e.reasons).contains(UncertaintyReason.MOTION_INCONSISTENT)
    }

    @Test
    fun `too few frames is UNKNOWN with TRACK_TOO_SHORT`() {
        val s = samples(4, stepMs = 250) { i -> boxAt(0.2f + 0.1f * i) }
        val e = estimator.estimate(track(s), nowMs = 750)
        assertThat(e.imageMotion).isEqualTo(ImageMotion.UNKNOWN)
        assertThat(e.approach).isEqualTo(ApproachState.UNKNOWN)
        assertThat(e.reasons).containsExactly(UncertaintyReason.TRACK_TOO_SHORT)
        assertThat(e.confidence).isEqualTo(0f)
        assertThat(e.lateralSpeed).isNull()
    }

    @Test
    fun `enough frames but too short a span is UNKNOWN with TRACK_TOO_SHORT`() {
        val s = samples(8, stepMs = 50) { i -> boxAt(0.2f + 0.05f * i) } // 350 ms
        val e = estimator.estimate(track(s), nowMs = 350)
        assertThat(e.reasons).containsExactly(UncertaintyReason.TRACK_TOO_SHORT)
    }

    @Test
    fun `samples outside the window or in the future are ignored`() {
        val old = samples(10) { i -> boxAt(0.2f + 0.05f * i) } // t = 0..900
        val e = estimator.estimate(track(old), nowMs = 5_000) // all older than the 3 s window
        assertThat(e.reasons).containsExactly(UncertaintyReason.TRACK_TOO_SHORT)
        assertThat(e.observationCount).isEqualTo(0)

        val early = estimator.estimate(track(old), nowMs = 300) // only 4 samples at or before now
        assertThat(early.reasons).containsExactly(UncertaintyReason.TRACK_TOO_SHORT)
    }

    // ---- identity --------------------------------------------------------------------------

    @Test
    fun `tracking identity change invalidates history and yields IDENTITY_SWITCH`() {
        val tracker = ObjectTracker()
        for (i in 0 until 10) tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.30f + 0.01f * i, h = 0.3f))), i * 100L, null)
        val before = tracker.confirmedTracks().single()
        assertThat(estimator.estimate(before, 900).imageMotion).isEqualTo(ImageMotion.MOVING_RIGHT_IN_IMAGE)

        // Implausible jump: the tracker associates it but flags identity as suspect.
        val after = tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.55f, h = 0.3f))), 1_000, null).single()
        assertThat(after.identitySuspect).isTrue()
        assertThat(after.historyValidFromMs).isEqualTo(1_000L)

        val e = estimator.estimate(after, 1_000)
        assertThat(e.imageMotion).isEqualTo(ImageMotion.UNKNOWN)
        assertThat(e.approach).isEqualTo(ApproachState.UNKNOWN)
        assertThat(e.reasons).containsExactly(UncertaintyReason.IDENTITY_SWITCH)
    }

    @Test
    fun `classification resumes once enough valid history accumulates after a switch`() {
        val stale = samples(10) { i -> boxAt(0.8f - 0.05f * i) } // old object moving left
        val fresh = (10 until 20).map { i -> TrackSample(i * 100L, boxAt(0.2f + 0.04f * (i - 10)), 0.8f) }
        val t = track(stale + fresh, identitySuspect = true, historyValidFromMs = 1_000)
        val e = estimator.estimate(t, 1_900)
        assertThat(e.imageMotion).isEqualTo(ImageMotion.MOVING_RIGHT_IN_IMAGE)
        assertThat(e.observationCount).isEqualTo(10)
    }

    // ---- camera ego-motion -----------------------------------------------------------------

    @Test
    fun `large camera yaw is UNKNOWN with CAMERA_MOVING`() {
        val s = samples(10, yaw = { 0.6f }) { i -> boxAt(0.3f + 0.04f * i) }
        val e = estimator.estimate(track(s), 900)
        assertThat(e.imageMotion).isEqualTo(ImageMotion.UNKNOWN)
        assertThat(e.approach).isEqualTo(ApproachState.UNKNOWN)
        assertThat(e.reasons).containsExactly(UncertaintyReason.CAMERA_MOVING)
    }

    @Test
    fun `a single yaw spike above the limit is enough for CAMERA_MOVING`() {
        val s = samples(10, yaw = { if (it == 5) -0.5f else 0.05f }) { i -> boxAt(0.5f) }
        assertThat(estimator.estimate(track(s), 900).reasons).containsExactly(UncertaintyReason.CAMERA_MOVING)
    }

    @Test
    fun `moderate yaw is compensated - static object with camera pan is not moving`() {
        val yaw = 0.25f
        // Camera turns right: the static object drifts left at yaw/HFOV ≈ 0.22 width/s.
        val s = samples(12, yaw = { yaw }) { i -> boxAt(0.7f - yaw * (i * 0.1f) / hfovRad) }
        val e = estimator.estimate(track(s), 1_100)
        assertThat(e.imageMotion).isEqualTo(ImageMotion.STATIONARY_OR_LOW_MOTION)
        assertThat(e.reasons).isEmpty()

        // The same image drift without gyro data would look like real leftward motion.
        val noGyro = samples(12) { i -> boxAt(0.7f - yaw * (i * 0.1f) / hfovRad) }
        assertThat(estimator.estimate(track(noGyro), 1_100).imageMotion).isEqualTo(ImageMotion.MOVING_LEFT_IN_IMAGE)
    }

    @Test
    fun `yaw compensation reveals real motion against the pan`() {
        val yaw = -0.2f // camera turning left: static things drift right
        val objectSpeed = -0.15f // object truly moving left
        val s = samples(12, yaw = { yaw }) { i ->
            val t = i * 0.1f
            boxAt(0.5f + objectSpeed * t - yaw * t / hfovRad)
        }
        val e = estimator.estimate(track(s), 1_100)
        assertThat(e.imageMotion).isEqualTo(ImageMotion.MOVING_LEFT_IN_IMAGE)
        assertThat(e.lateralSpeed!!).isWithin(0.01f).of(objectSpeed)
    }

    @Test
    fun `missing gyro lowers confidence without fabricating data`() {
        val withGyro = samples(12, yaw = { 0f }) { i -> boxAt(0.3f + 0.04f * i) }
        val without = samples(12) { i -> boxAt(0.3f + 0.04f * i) }
        val a = estimator.estimate(track(withGyro), 1_100)
        val b = estimator.estimate(track(without), 1_100)
        assertThat(a.imageMotion).isEqualTo(b.imageMotion)
        assertThat(b.confidence).isLessThan(a.confidence)
        assertThat(b.lateralSpeed).isEqualTo(a.lateralSpeed)
    }

    // ---- approach --------------------------------------------------------------------------

    @Test
    fun `bounding box growth alone with static bottom edge is not APPROACHING`() {
        // Height grows 0.2 -> ~0.3 but the bottom edge stays at 0.8 (top edge moves up).
        val s = samples(11) { i ->
            val h = 0.2f * exp(0.4f * i * 0.1f)
            boxBottom(0.5f, bottom = 0.8f, w = 0.5f * h, h = h)
        }
        val e = estimator.estimate(track(s), 1_000)
        assertThat(e.approach).isEqualTo(ApproachState.UNKNOWN)
        assertThat(e.approach).isNotEqualTo(ApproachState.APPROACHING)
        assertThat(e.scaleGrowthRate!!).isGreaterThan(0.3f)
    }

    @Test
    fun `growth with inconsistent bottom edge is not APPROACHING`() {
        val bottoms = listOf(0.70f, 0.72f, 0.69f, 0.73f, 0.70f, 0.74f, 0.71f, 0.72f, 0.70f, 0.73f, 0.71f)
        val s = samples(11) { i ->
            val h = 0.2f * exp(0.4f * i * 0.1f)
            boxBottom(0.5f, bottom = bottoms[i], w = 0.5f * h, h = h)
        }
        assertThat(estimator.estimate(track(s), 1_000).approach).isEqualTo(ApproachState.UNKNOWN)
    }

    @Test
    fun `growth plus bottom-edge descent is APPROACHING`() {
        val s = samples(11) { i ->
            val t = i * 0.1f
            val h = 0.2f * exp(0.4f * t)
            boxBottom(0.5f, bottom = 0.7f + 0.1f * t, w = 0.5f * h, h = h)
        }
        val e = estimator.estimate(track(s), 1_000)
        assertThat(e.approach).isEqualTo(ApproachState.APPROACHING)
        assertThat(e.scaleGrowthRate!!).isWithin(0.02f).of(0.4f)
        assertThat(e.confidence).isGreaterThan(0f)
    }

    @Test
    fun `approaching signals under large camera yaw stay UNKNOWN`() {
        val s = samples(11, yaw = { 0.5f }) { i ->
            val t = i * 0.1f
            val h = 0.2f * exp(0.4f * t)
            boxBottom(0.5f, bottom = 0.7f + 0.1f * t, w = 0.5f * h, h = h)
        }
        val e = estimator.estimate(track(s), 1_000)
        assertThat(e.approach).isEqualTo(ApproachState.UNKNOWN)
        assertThat(e.reasons).contains(UncertaintyReason.CAMERA_MOVING)
    }

    @Test
    fun `consistent shrink and bottom-edge rise is MOVING_AWAY`() {
        val s = samples(11) { i ->
            val t = i * 0.1f
            val h = 0.4f * exp(-0.4f * t)
            boxBottom(0.5f, bottom = 0.85f - 0.08f * t, w = 0.5f * h, h = h)
        }
        assertThat(estimator.estimate(track(s), 1_000).approach).isEqualTo(ApproachState.MOVING_AWAY)
    }

    @Test
    fun `stationary object is STATIONARY`() {
        val s = samples(10) { boxBottom(0.4f, bottom = 0.75f, w = 0.1f, h = 0.3f) }
        val e = estimator.estimate(track(s), 900)
        assertThat(e.imageMotion).isEqualTo(ImageMotion.STATIONARY_OR_LOW_MOTION)
        assertThat(e.approach).isEqualTo(ApproachState.STATIONARY)
    }

    @Test
    fun `estimateAll keys estimates by track id`() {
        val a = track(samples(8) { i -> boxAt(0.2f + 0.05f * i) }, id = 3)
        val b = track(samples(2) { boxAt(0.5f) }, id = 9)
        val all = estimator.estimateAll(listOf(a, b), 700)
        assertThat(all.keys).containsExactly(3L, 9L)
        assertThat(all.getValue(9L).reasons).containsExactly(UncertaintyReason.TRACK_TOO_SHORT)
    }

    @Test
    fun `per-call field of view overrides the default`() {
        val yaw = 0.25f
        val narrowFov = Math.toRadians(40.0).toFloat()
        val s = samples(12, yaw = { yaw }) { i -> boxAt(0.7f - yaw * (i * 0.1f) / narrowFov) }
        assertThat(estimator.estimate(track(s), 1_100, horizontalFovDeg = 40f).imageMotion)
            .isEqualTo(ImageMotion.STATIONARY_OR_LOW_MOTION)
    }
}
