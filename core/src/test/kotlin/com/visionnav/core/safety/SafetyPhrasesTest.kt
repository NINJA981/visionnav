package com.visionnav.core.safety

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.visionnav.core.announce.AnnouncementPriority
import com.visionnav.core.announce.Earcon
import com.visionnav.core.announce.HapticPattern
import com.visionnav.core.announce.SafetyPhraseGuard
import com.visionnav.core.tracking.ApproachState
import com.visionnav.core.vision.ObjectClass
import org.junit.Test

class SafetyPhrasesTest {

    private fun event(
        severity: SafetySeverity,
        cls: ObjectClass = ObjectClass.CAR,
        sector: ViewSector = ViewSector.CENTER,
        proximity: ProximityBand = ProximityBand.VERY_NEAR,
        approach: ApproachState = ApproachState.UNKNOWN,
        inPath: Boolean = true,
        trackId: Long = 7,
        ms: Long = 1_000,
    ) = SafetyEvent(trackId, cls, severity, sector, proximity, approach, inPath, ms)

    @Test
    fun `critical very near car ahead`() {
        val a = SafetyPhrases.toAnnouncement(event(SafetySeverity.CRITICAL))!!
        assertThat(a.text).isEqualTo("Stop. Car very close, ahead.")
        assertThat(a.priority).isEqualTo(AnnouncementPriority.CRITICAL)
        assertThat(a.earcon).isEqualTo(Earcon.CRITICAL_ALERT)
        assertThat(a.haptic).isEqualTo(HapticPattern.CRITICAL)
        assertThat(a.dedupeKey).isEqualTo("safety:7:CRITICAL")
        assertThat(a.createdAtMs).isEqualTo(1_000L)
    }

    @Test
    fun `warning and caution wording and priorities`() {
        val person = SafetyPhrases.toAnnouncement(
            event(SafetySeverity.WARNING, ObjectClass.PERSON, ViewSector.LEFT, ProximityBand.MEDIUM, inPath = true),
        )!!
        assertThat(person.text).isEqualTo("Person ahead, slightly left.")
        assertThat(person.priority).isEqualTo(AnnouncementPriority.WARNING)
        assertThat(person.earcon).isEqualTo(Earcon.WARNING)
        assertThat(person.haptic).isEqualTo(HapticPattern.WARNING)

        val bike = SafetyPhrases.toAnnouncement(
            event(SafetySeverity.CAUTION, ObjectClass.BICYCLE, ViewSector.FAR_RIGHT, ProximityBand.MEDIUM, ApproachState.APPROACHING, inPath = false),
        )!!
        assertThat(bike.text).isEqualTo("Bicycle approaching, on the right.")
        assertThat(bike.priority).isEqualTo(AnnouncementPriority.OBSERVATION)
        assertThat(bike.earcon).isEqualTo(Earcon.NONE)

        val car = SafetyPhrases.toAnnouncement(
            event(SafetySeverity.CRITICAL, ObjectClass.CAR, ViewSector.RIGHT, ProximityBand.NEAR, ApproachState.APPROACHING, inPath = false),
        )!!
        assertThat(car.text).isEqualTo("Stop. Car approaching, close, slightly right.")
    }

    @Test
    fun `info produces no announcement`() {
        assertThat(SafetyPhrases.toAnnouncement(event(SafetySeverity.INFO))).isNull()
    }

    @Test
    fun `escalation flag is set only for severity increases`() {
        assertThat(SafetyPhrases.toAnnouncement(event(SafetySeverity.CRITICAL), SafetySeverity.WARNING)!!.escalation).isTrue()
        assertThat(SafetyPhrases.toAnnouncement(event(SafetySeverity.WARNING), SafetySeverity.CRITICAL)!!.escalation).isFalse()
        assertThat(SafetyPhrases.toAnnouncement(event(SafetySeverity.WARNING), SafetySeverity.WARNING)!!.escalation).isFalse()
        assertThat(SafetyPhrases.toAnnouncement(event(SafetySeverity.WARNING), null)!!.escalation).isFalse()
    }

    @Test
    fun `every safety phrase combination passes the phrase guard and uses no numbers`() {
        val guard = SafetyPhraseGuard()
        var checked = 0
        for (cls in ObjectClass.entries) for (sev in SafetySeverity.entries) for (sector in ViewSector.entries)
            for (prox in ProximityBand.entries) for (approach in ApproachState.entries) for (inPath in listOf(true, false)) {
                val e = event(sev, cls, sector, prox, approach, inPath)
                val text = SafetyPhrases.text(e)
                assertWithMessage(text).that(guard.isAllowed(text)).isTrue()
                assertWithMessage(text).that(text).doesNotContainMatch("[0-9]")
                assertWithMessage(text).that(text.lowercase()).doesNotContainMatch("metre|second|safe|clear")
                SafetyPhrases.toAnnouncement(e)?.let { assertWithMessage(it.text).that(guard.isAllowed(it.text)).isTrue() }
                checked++
            }
        assertThat(checked).isEqualTo(ObjectClass.entries.size * 4 * 5 * 5 * 5 * 2)
    }

    @Test
    fun `announcer speaks on first appearance and escalation, not every frame`() {
        val announcer = SafetyAnnouncer(repeatAfterMs = 5_000)
        val caution = event(SafetySeverity.CAUTION, proximity = ProximityBand.MEDIUM, ms = 0)
        assertThat(announcer.announcementsFor(listOf(caution))).hasSize(1)
        assertThat(announcer.announcementsFor(listOf(caution.copy(elapsedMs = 100)))).isEmpty()

        val critical = announcer.announcementsFor(listOf(event(SafetySeverity.CRITICAL, ms = 200)))
        assertThat(critical.single().escalation).isTrue()
        assertThat(critical.single().dedupeKey).isEqualTo("safety:7:CRITICAL")

        // De-escalation is silent; repeat only after the interval while still >= WARNING.
        assertThat(announcer.announcementsFor(listOf(event(SafetySeverity.WARNING, ms = 300)))).isEmpty()
        assertThat(announcer.announcementsFor(listOf(event(SafetySeverity.WARNING, ms = 4_000)))).isEmpty()
        assertThat(announcer.announcementsFor(listOf(event(SafetySeverity.WARNING, ms = 5_300)))).hasSize(1)
    }

    @Test
    fun `announcer ignores INFO but counts a later rise as escalation`() {
        val announcer = SafetyAnnouncer()
        assertThat(announcer.announcementsFor(listOf(event(SafetySeverity.INFO, ms = 0)))).isEmpty()
        val a = announcer.announcementsFor(listOf(event(SafetySeverity.WARNING, ms = 100))).single()
        assertThat(a.escalation).isTrue()
    }
}
