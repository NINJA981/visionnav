package com.visionnav.core.tracking

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.tracking.TrackFixtures.boxAt
import com.visionnav.core.tracking.TrackFixtures.det
import com.visionnav.core.vision.ObjectClass
import org.junit.Test

class ObjectTrackerTest {

    @Test
    fun `tentative track needs two hits before it is exposed`() {
        val tracker = ObjectTracker()
        assertThat(tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.3f))), 0, null)).isEmpty()
        val second = tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.31f))), 100, null)
        assertThat(second).hasSize(1)
        assertThat(second.single().history).hasSize(2)
    }

    @Test
    fun `single-frame false positive is never exposed and is removed`() {
        val tracker = ObjectTracker()
        tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.5f))), 0, null)
        assertThat(tracker.update(emptyList(), 100, null)).isEmpty()
        assertThat(tracker.update(emptyList(), 200, null)).isEmpty()
        assertThat(tracker.liveTrackCount).isEqualTo(0)
    }

    @Test
    fun `keeps the same id across frames for a moving object`() {
        val tracker = ObjectTracker()
        val ids = mutableSetOf<Long>()
        for (i in 0 until 20) {
            val out = tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.2f + 0.025f * i, w = 0.15f))), i * 100L, null)
            if (i > 0) {
                assertThat(out).hasSize(1)
                ids += out.single().id
                assertThat(out.single().identitySuspect).isFalse()
            }
        }
        assertThat(ids).containsExactly(1L)
    }

    @Test
    fun `fast mover without box overlap is still associated via centre-distance fallback`() {
        val tracker = ObjectTracker()
        // Moves 0.08 per frame with width 0.06: consecutive boxes never overlap.
        var lastId: Long? = null
        for (i in 0 until 8) {
            val out = tracker.update(listOf(det(ObjectClass.BICYCLE, boxAt(0.1f + 0.08f * i, w = 0.06f, h = 0.2f))), i * 100L, null)
            if (i > 0) {
                val id = out.single().id
                if (lastId != null) assertThat(id).isEqualTo(lastId)
                lastId = id
            }
        }
    }

    @Test
    fun `two objects keep distinct stable ids`() {
        val tracker = ObjectTracker()
        val seen = mutableMapOf<String, Long>()
        for (i in 0 until 15) {
            val a = det(ObjectClass.PERSON, boxAt(0.20f + 0.01f * i, w = 0.08f, h = 0.3f))
            val b = det(ObjectClass.PERSON, boxAt(0.75f - 0.01f * i, w = 0.08f, h = 0.3f))
            val out = tracker.update(listOf(a, b), i * 100L, null)
            if (i == 0) continue
            assertThat(out).hasSize(2)
            val left = out.minBy { it.latest.box.centerX }
            val right = out.maxBy { it.latest.box.centerX }
            seen.getOrPut("left") { left.id }.let { assertThat(left.id).isEqualTo(it) }
            seen.getOrPut("right") { right.id }.let { assertThat(right.id).isEqualTo(it) }
        }
        assertThat(seen.getValue("left")).isNotEqualTo(seen.getValue("right"))
    }

    @Test
    fun `ids are monotonically increasing and never reused, including after reset`() {
        val tracker = ObjectTracker(TrackerConfig(maxMissedFrames = 2))
        tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 0, null)
        val first = tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 100, null).single().id
        // Object disappears long enough to be removed, then reappears at the same place.
        for (t in 2..6) tracker.update(emptyList(), t * 100L, null)
        assertThat(tracker.liveTrackCount).isEqualTo(0)
        tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 700, null)
        val second = tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 800, null).single().id
        assertThat(second).isGreaterThan(first)

        tracker.reset()
        assertThat(tracker.confirmedTracks()).isEmpty()
        tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 900, null)
        val third = tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 1000, null).single().id
        assertThat(third).isGreaterThan(second)
    }

    @Test
    fun `coasting track is reported for a couple of frames then hidden then removed`() {
        val tracker = ObjectTracker(TrackerConfig(reportLostForFrames = 2, maxMissedFrames = 4, maxMissedMs = 10_000))
        tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 0, null)
        tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 100, null)
        assertThat(tracker.update(emptyList(), 200, null).single().missedFrames).isEqualTo(1)
        assertThat(tracker.update(emptyList(), 300, null).single().missedFrames).isEqualTo(2)
        assertThat(tracker.update(emptyList(), 400, null)).isEmpty()
        assertThat(tracker.liveTrackCount).isEqualTo(1)
        tracker.update(emptyList(), 500, null)
        tracker.update(emptyList(), 600, null)
        assertThat(tracker.liveTrackCount).isEqualTo(0)
    }

    @Test
    fun `track is re-acquired after a short gap with the same id`() {
        val tracker = ObjectTracker()
        tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.5f))), 0, null)
        val id = tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.5f))), 100, null).single().id
        tracker.update(emptyList(), 200, null)
        tracker.update(emptyList(), 300, null)
        val back = tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.51f))), 400, null)
        assertThat(back.single().id).isEqualTo(id)
        assertThat(back.single().missedFrames).isEqualTo(0)
    }

    @Test
    fun `removed after maxMissedMs even if few frames were missed`() {
        val tracker = ObjectTracker(TrackerConfig(maxMissedMs = 500))
        tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 0, null)
        tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 100, null)
        tracker.update(emptyList(), 700, null)
        assertThat(tracker.liveTrackCount).isEqualTo(0)
    }

    @Test
    fun `low-confidence detection extends an existing track in the second pass`() {
        val tracker = ObjectTracker()
        tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.5f), 0.9f)), 0, null)
        val id = tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.5f), 0.9f)), 100, null).single().id
        val out = tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.505f), 0.42f)), 200, null)
        assertThat(out.single().id).isEqualTo(id)
        assertThat(out.single().missedFrames).isEqualTo(0)
    }

    @Test
    fun `low-confidence detection below the new-track floor does not spawn a track`() {
        val tracker = ObjectTracker()
        tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.5f), 0.42f)), 0, null)
        tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.5f), 0.42f)), 100, null)
        assertThat(tracker.liveTrackCount).isEqualTo(0)
    }

    @Test
    fun `vehicle family confusion keeps the same track, unrelated classes never match`() {
        val tracker = ObjectTracker()
        tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f, w = 0.3f))), 0, null)
        val id = tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f, w = 0.3f))), 100, null).single().id
        val out = tracker.update(listOf(det(ObjectClass.TRUCK, boxAt(0.51f, w = 0.3f), 0.6f)), 200, null)
        assertThat(out.single().id).isEqualTo(id)
        assertThat(out.single().objectClass).isEqualTo(ObjectClass.CAR) // majority vote

        val tracker2 = ObjectTracker()
        tracker2.update(listOf(det(ObjectClass.PERSON, boxAt(0.5f))), 0, null)
        val pid = tracker2.update(listOf(det(ObjectClass.PERSON, boxAt(0.5f))), 100, null).single().id
        tracker2.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 200, null)
        val after = tracker2.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 300, null)
        assertThat(after.map { it.objectClass }).contains(ObjectClass.CAR)
        assertThat(after.single { it.objectClass == ObjectClass.CAR }.id).isNotEqualTo(pid)
    }

    @Test
    fun `history is bounded`() {
        val tracker = ObjectTracker(TrackerConfig(historySize = 5))
        var out: List<Track> = emptyList()
        for (i in 0 until 12) out = tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), i * 100L, null)
        assertThat(out.single().history).hasSize(5)
        assertThat(out.single().history.last().elapsedMs).isEqualTo(1100L)
        assertThat(out.single().firstSeenMs).isEqualTo(0L)
    }

    @Test
    fun `camera yaw rate is stored in samples`() {
        val tracker = ObjectTracker()
        tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 0, 0.1f)
        val out = tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 100, null)
        assertThat(out.single().history.map { it.cameraYawRateRadPerSec }).containsExactly(0.1f, null).inOrder()
    }

    @Test
    fun `implausible jump marks identity suspect and invalidates history`() {
        val tracker = ObjectTracker()
        for (i in 0 until 6) tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.40f + 0.005f * i))), i * 100L, null)
        // Jump of ~2 box widths in one frame (still inside the association gate).
        val out = tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.62f))), 600, null).single()
        assertThat(out.identitySuspect).isTrue()
        assertThat(out.historyValidFromMs).isEqualTo(600L)
        assertThat(out.validHistory).hasSize(1)
        assertThat(out.history.size).isGreaterThan(1)
    }

    @Test
    fun `implausible size change marks identity suspect`() {
        val tracker = ObjectTracker()
        for (i in 0 until 4) tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f, w = 0.2f, h = 0.15f))), i * 100L, null)
        val out = tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f, w = 0.4f, h = 0.3f))), 400, null).single()
        assertThat(out.identitySuspect).isTrue()
        assertThat(out.historyValidFromMs).isEqualTo(400L)
    }

    @Test
    fun `ambiguous association between two near-equal candidates marks identity suspect`() {
        val tracker = ObjectTracker()
        tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.5f, w = 0.1f, h = 0.3f))), 0, null)
        tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.5f, w = 0.1f, h = 0.3f))), 100, null)
        // Two people now overlap the old box almost equally (one slightly left, one slightly right).
        val out = tracker.update(
            listOf(
                det(ObjectClass.PERSON, boxAt(0.47f, w = 0.1f, h = 0.3f), 0.80f),
                det(ObjectClass.PERSON, boxAt(0.535f, w = 0.1f, h = 0.3f), 0.79f),
            ),
            200,
            null,
        )
        val original = out.single { it.id == 1L }
        assertThat(original.identitySuspect).isTrue()
        assertThat(original.historyValidFromMs).isEqualTo(200L)
    }

    @Test
    fun `suspect flag clears once enough fresh history has accumulated`() {
        val tracker = ObjectTracker(TrackerConfig(suspectClearSamples = 4))
        for (i in 0 until 3) tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.3f))), i * 100L, null)
        tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.5f))), 300, null)
        var out = tracker.confirmedTracks().single()
        assertThat(out.identitySuspect).isTrue()
        for (i in 4 until 7) out = tracker.update(listOf(det(ObjectClass.PERSON, boxAt(0.5f))), i * 100L, null).single()
        assertThat(out.identitySuspect).isFalse()
        assertThat(out.historyValidFromMs).isEqualTo(300L)
    }

    @Test
    fun `yaw-aware prediction keeps a static object associated while the camera pans`() {
        val tracker = ObjectTracker()
        val hfovRad = Math.toRadians(66.0).toFloat()
        val yaw = 0.3f
        var lastId: Long? = null
        for (i in 0 until 10) {
            // Static object drifts left in the image because the camera turns right.
            val cx = 0.8f - yaw * (i * 0.1f) / hfovRad
            val out = tracker.update(listOf(det(ObjectClass.BENCH, boxAt(cx, w = 0.05f, h = 0.1f))), i * 100L, yaw)
            if (i > 0) {
                val t = out.single()
                assertThat(t.identitySuspect).isFalse()
                if (lastId != null) assertThat(t.id).isEqualTo(lastId)
                lastId = t.id
            }
        }
    }

    @Test
    fun `timestamp going backwards resets tracks without reusing ids`() {
        val tracker = ObjectTracker()
        tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 1_000, null)
        val id = tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 1_100, null).single().id
        assertThat(tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 500, null)).isEmpty()
        val again = tracker.update(listOf(det(ObjectClass.CAR, boxAt(0.5f))), 600, null).single().id
        assertThat(again).isGreaterThan(id)
    }
}
