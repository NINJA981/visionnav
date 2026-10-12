package com.visionnav.core.scene

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.visionnav.core.announce.AnnouncementPriority
import com.visionnav.core.announce.SafetyPhraseGuard
import com.visionnav.core.safety.ProximityBand
import com.visionnav.core.tracking.TrackFixtures.boxBottom
import com.visionnav.core.tracking.TrackFixtures.trackWithBox
import com.visionnav.core.vision.ObjectClass
import org.junit.Test

class PathAnalyzerTest {

    private val analyzer = PathAnalyzer()
    private val nearBenchSlightlyRight = boxBottom(0.55f, bottom = 0.85f, w = 0.2f, h = 0.3f)

    @Test
    fun `no objects in the corridor says nothing`() {
        val r = analyzer.analyze(listOf(trackWithBox(1, ObjectClass.CAR, boxBottom(0.1f, 0.8f, 0.1f, 0.3f))), 1_000)
        assertThat(r).isInstanceOf(PathAssessment.NothingRecognisedInPath::class.java)
        assertThat(r.announcement).isNull()
        assertThat(analyzer.analyze(emptyList(), 1_000).announcement).isNull()
    }

    @Test
    fun `far object in the corridor gives no advice`() {
        val r = analyzer.analyze(listOf(trackWithBox(1, ObjectClass.BENCH, boxBottom(0.5f, 0.6f, 0.1f, 0.05f))), 1_000)
        assertThat(r).isInstanceOf(PathAssessment.ObstacleNotNear::class.java)
        assertThat(r.announcement).isNull()
    }

    @Test
    fun `near obstacle offset right suggests more space on the left`() {
        val r = analyzer.analyze(listOf(trackWithBox(4, ObjectClass.BENCH, nearBenchSlightlyRight)), 1_000) as PathAssessment.ObstacleAhead
        assertThat(r.freerSide).isEqualTo(PathSide.LEFT)
        assertThat(r.proximity).isEqualTo(ProximityBand.NEAR)
        assertThat(r.announcement.text).isEqualTo("Bench ahead. More space on the left.")
        assertThat(r.announcement.priority).isEqualTo(AnnouncementPriority.WARNING)
        assertThat(r.announcement.dedupeKey).isEqualTo("path:4:LEFT")
    }

    @Test
    fun `occupied left band makes the right side freer`() {
        val tracks = listOf(
            trackWithBox(1, ObjectClass.BENCH, nearBenchSlightlyRight),
            trackWithBox(2, ObjectClass.CAR, boxBottom(0.17f, bottom = 0.8f, w = 0.3f, h = 0.2f)),
        )
        val r = analyzer.analyze(tracks, 1_000) as PathAssessment.ObstacleAhead
        assertThat(r.leftOccupancy).isGreaterThan(0.5f)
        assertThat(r.freerSide).isEqualTo(PathSide.RIGHT)
        assertThat(r.announcement.text).isEqualTo("Bench ahead. More space on the right.")
    }

    @Test
    fun `both sides occupied is path ahead blocked`() {
        val tracks = listOf(
            trackWithBox(1, ObjectClass.PERSON, boxBottom(0.5f, bottom = 0.9f, w = 0.2f, h = 0.6f)),
            trackWithBox(2, ObjectClass.CAR, boxBottom(0.17f, bottom = 0.8f, w = 0.34f, h = 0.2f)),
            trackWithBox(3, ObjectClass.CAR, boxBottom(0.83f, bottom = 0.8f, w = 0.34f, h = 0.2f)),
        )
        val r = analyzer.analyze(tracks, 1_000)
        assertThat(r).isInstanceOf(PathAssessment.Blocked::class.java)
        assertThat(r.announcement!!.text).isEqualTo("Path ahead blocked.")
        assertThat(r.announcement!!.priority).isEqualTo(AnnouncementPriority.WARNING)
    }

    @Test
    fun `centred obstacle with symmetric sides gives no side hint`() {
        val r = analyzer.analyze(listOf(trackWithBox(1, ObjectClass.CHAIR, boxBottom(0.5f, 0.85f, 0.2f, 0.3f))), 1_000) as PathAssessment.ObstacleAhead
        assertThat(r.freerSide).isNull()
        assertThat(r.announcement.text).isEqualTo("Chair ahead.")
    }

    @Test
    fun `traffic lights are ignored`() {
        val r = analyzer.analyze(listOf(trackWithBox(1, ObjectClass.TRAFFIC_LIGHT, boxBottom(0.5f, 0.9f, 0.2f, 0.6f))), 1_000)
        assertThat(r).isInstanceOf(PathAssessment.NothingRecognisedInPath::class.java)
    }

    @Test
    fun `every path phrase combination passes the phrase guard`() {
        val guard = SafetyPhraseGuard()
        val xs = listOf(0.1f, 0.3f, 0.45f, 0.5f, 0.55f, 0.7f, 0.9f)
        val sideBoxes = listOf(
            null,
            boxBottom(0.17f, 0.8f, 0.34f, 0.2f),
            boxBottom(0.83f, 0.8f, 0.34f, 0.2f),
        )
        var checked = 0
        for (cls in ObjectClass.entries) for (x in xs) for (h in listOf(0.1f, 0.3f, 0.6f, 0.9f)) for (left in sideBoxes) for (right in sideBoxes) {
            val tracks = buildList {
                add(trackWithBox(1, cls, boxBottom(x, 0.9f, 0.2f, h)))
                left?.let { add(trackWithBox(2, ObjectClass.CAR, it)) }
                right?.let { add(trackWithBox(3, ObjectClass.CAR, it)) }
            }
            analyzer.analyze(tracks, 1_000).announcement?.let {
                assertWithMessage(it.text).that(guard.isAllowed(it.text)).isTrue()
                assertWithMessage(it.text).that(it.text).doesNotContainMatch("[0-9]")
                checked++
            }
        }
        assertThat(checked).isGreaterThan(100)
    }
}
