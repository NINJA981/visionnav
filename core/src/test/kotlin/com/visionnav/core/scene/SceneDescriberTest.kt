package com.visionnav.core.scene

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.visionnav.core.announce.AnnouncementPriority
import com.visionnav.core.announce.SafetyPhraseGuard
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.tracking.Track
import com.visionnav.core.tracking.TrackFixtures.boxBottom
import com.visionnav.core.tracking.TrackFixtures.trackWithBox
import com.visionnav.core.vision.ObjectClass
import org.junit.Test

class SceneDescriberTest {

    private val describer = SceneDescriber()
    private val guard = SafetyPhraseGuard()

    @Test
    fun `nothing detected never claims the area is clear`() {
        val d = describer.describe(emptyList(), 1_000)
        assertThat(d.text).isEqualTo("Nothing recognised in the camera view.")
        assertThat(d.items).isEmpty()
        assertThat(guard.isAllowed(d.text)).isTrue()
        assertThat(d.text.lowercase()).doesNotContain("clear")
        assertThat(d.text.lowercase()).doesNotContain("nothing there")
    }

    @Test
    fun `quality issue is prefixed`() {
        val d = describer.describe(emptyList(), 1_000, listOf(UncertaintyReason.LOW_LIGHT, UncertaintyReason.BLUR))
        assertThat(d.text).isEqualTo("Low light, view limited. Nothing recognised in the camera view.")
        assertThat(d.qualityIssue).isEqualTo(UncertaintyReason.LOW_LIGHT)

        val withObjects = describer.describe(
            listOf(trackWithBox(1, ObjectClass.CAR, boxBottom(0.5f, 0.7f, 0.2f, 0.2f))),
            1_000,
            listOf(UncertaintyReason.GLARE),
        )
        assertThat(withObjects.text).startsWith("Glare, view limited. ")
    }

    @Test
    fun `grouped summary by side and proximity, most salient first`() {
        val tracks = listOf(
            trackWithBox(1, ObjectClass.PERSON, boxBottom(0.47f, bottom = 0.75f, w = 0.08f, h = 0.3f)),
            trackWithBox(2, ObjectClass.PERSON, boxBottom(0.53f, bottom = 0.75f, w = 0.08f, h = 0.3f)),
            trackWithBox(3, ObjectClass.CAR, boxBottom(0.85f, bottom = 0.55f, w = 0.12f, h = 0.08f)),
            trackWithBox(4, ObjectClass.BENCH, boxBottom(0.15f, bottom = 0.8f, w = 0.2f, h = 0.3f)),
        )
        val d = describer.describe(tracks, 1_000)
        assertThat(d.text).isEqualTo("Two people ahead. A car on the right, far. A bench on the left, close.")
        assertThat(d.items.first().trackIds).containsExactly(1L, 2L)
        assertThat(d.omittedGroups).isEqualTo(0)
    }

    @Test
    fun `at most three groups are spoken, vehicles and in-path first`() {
        val tracks = listOf(
            trackWithBox(1, ObjectClass.BENCH, boxBottom(0.1f, 0.6f, 0.05f, 0.05f)),
            trackWithBox(2, ObjectClass.POTTED_PLANT, boxBottom(0.9f, 0.6f, 0.05f, 0.05f)),
            trackWithBox(3, ObjectClass.DOG, boxBottom(0.3f, 0.6f, 0.05f, 0.05f)),
            trackWithBox(4, ObjectClass.TRUCK, boxBottom(0.9f, 0.55f, 0.1f, 0.1f)),
            trackWithBox(5, ObjectClass.CHAIR, boxBottom(0.5f, 0.8f, 0.1f, 0.15f)),
        )
        val d = describer.describe(tracks, 1_000)
        assertThat(d.items).hasSize(3)
        assertThat(d.items.map { it.objectClass }).containsExactly(ObjectClass.CHAIR, ObjectClass.TRUCK, ObjectClass.DOG).inOrder()
        assertThat(d.omittedGroups).isEqualTo(2)
        assertThat(d.text).endsWith("More objects in view.")
    }

    @Test
    fun `stale tracks are not described`() {
        val stale = trackWithBox(1, ObjectClass.CAR, boxBottom(0.5f, 0.7f, 0.2f, 0.2f), missedFrames = 5)
        assertThat(describer.describe(listOf(stale), 1_000).text).isEqualTo(SceneDescriber.NOTHING_RECOGNISED)
    }

    @Test
    fun `counts use words and plurals`() {
        val many = (1..12).map { trackWithBox(it.toLong(), ObjectClass.BUS, boxBottom(0.5f, 0.6f, 0.05f, 0.1f)) }
        assertThat(describer.describe(many, 1_000).text).startsWith("Many buses ahead")
        val three = (1..3).map { trackWithBox(it.toLong(), ObjectClass.BENCH, boxBottom(0.1f, 0.6f, 0.05f, 0.05f)) }
        assertThat(describer.describe(three, 1_000).text).isEqualTo("Three benches on the left, far.")
    }

    @Test
    fun `announcement is observation priority`() {
        val a = describer.describe(emptyList(), 1_234).toAnnouncement()
        assertThat(a.priority).isEqualTo(AnnouncementPriority.OBSERVATION)
        assertThat(a.createdAtMs).isEqualTo(1_234L)
    }

    @Test
    fun `every scene phrase combination passes the phrase guard`() {
        val xs = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
        val heights = listOf(0.05f, 0.15f, 0.3f, 0.5f, 0.8f)
        val bottoms = listOf(0.5f, 0.9f, 0.99f)
        val issues: List<List<UncertaintyReason>> = listOf(emptyList<UncertaintyReason>()) + UncertaintyReason.entries.map { listOf(it) }
        var checked = 0
        for (cls in ObjectClass.entries) for (x in xs) for (h in heights) for (b in bottoms) for (count in listOf(1, 2, 5, 11)) {
            val tracks: List<Track> = (1..count).map { trackWithBox(it.toLong(), cls, boxBottom(x, b, 0.1f, h)) }
            for (issue in listOf(emptyList(), listOf(UncertaintyReason.LOW_LIGHT))) {
                val text = describer.describe(tracks, 1_000, issue).text
                assertWithMessage(text).that(guard.isAllowed(text)).isTrue()
                assertWithMessage(text).that(text).doesNotContainMatch("[0-9]")
                checked++
            }
        }
        for (issue in issues) {
            val text = describer.describe(emptyList(), 1_000, issue).text
            assertWithMessage(text).that(guard.isAllowed(text)).isTrue()
            checked++
        }
        // A mixed busy scene.
        val mixed = ObjectClass.entries.mapIndexed { i, c -> trackWithBox(i.toLong(), c, boxBottom(xs[i % xs.size], 0.9f, 0.1f, heights[i % heights.size])) }
        for (issue in issues) {
            val text = describer.describe(mixed, 1_000, issue).text
            assertWithMessage(text).that(guard.isAllowed(text)).isTrue()
            checked++
        }
        assertThat(checked).isGreaterThan(1_000)
    }
}
