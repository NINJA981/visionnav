package com.visionnav.core.announce

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.announce.AnnouncementPolicy.Decision
import com.visionnav.core.announce.AnnouncementPolicy.DropReason
import com.visionnav.core.common.FakeTimeSource
import org.junit.Test

class AnnouncementPolicyTest {
    private val time = FakeTimeSource(1_000)
    private val policy = AnnouncementPolicy(time)

    private fun a(key: String, p: AnnouncementPriority, escalation: Boolean = false) =
        Announcement("text $key", p, key, escalation = escalation)

    @Test
    fun `same-key cooldown per priority`() {
        val cases = mapOf(
            AnnouncementPriority.CRITICAL to 2_000L,
            AnnouncementPriority.WARNING to 3_000L,
            AnnouncementPriority.NAVIGATION to 8_000L,
            AnnouncementPriority.OBSERVATION to 4_000L,
            AnnouncementPriority.INFO to 6_000L,
        )
        for ((p, cooldown) in cases) {
            val key = "k-$p"
            time.advance(10_000) // clear the global ambient window between cases
            assertThat(policy.admit(a(key, p))).isEqualTo(Decision.Accept)
            time.advance(cooldown - 1)
            assertThat(policy.admit(a(key, p))).isEqualTo(Decision.Drop(DropReason.COOLDOWN))
            time.advance(1)
            assertThat(policy.admit(a(key, p))).isEqualTo(Decision.Accept)
        }
    }

    @Test
    fun `distinct navigation keys are never cooled down`() {
        repeat(5) { i ->
            assertThat(policy.admit(a("nav:$i", AnnouncementPriority.NAVIGATION))).isEqualTo(Decision.Accept)
        }
    }

    @Test
    fun `global ambient limit allows at most one observation or info per 2_5 s`() {
        assertThat(policy.admit(a("obs:1", AnnouncementPriority.OBSERVATION))).isEqualTo(Decision.Accept)
        time.advance(1_000)
        assertThat(policy.admit(a("obs:2", AnnouncementPriority.OBSERVATION)))
            .isEqualTo(Decision.Drop(DropReason.AMBIENT_RATE_LIMIT))
        assertThat(policy.admit(a("info:1", AnnouncementPriority.INFO)))
            .isEqualTo(Decision.Drop(DropReason.AMBIENT_RATE_LIMIT))
        // Higher priorities are not subject to the ambient limit.
        assertThat(policy.admit(a("warn:1", AnnouncementPriority.WARNING))).isEqualTo(Decision.Accept)
        time.advance(1_500)
        assertThat(policy.admit(a("obs:2", AnnouncementPriority.OBSERVATION))).isEqualTo(Decision.Accept)
    }

    @Test
    fun `escalation to a higher priority bypasses cooldown`() {
        assertThat(policy.admit(a("hazard:7", AnnouncementPriority.WARNING))).isEqualTo(Decision.Accept)
        time.advance(500)
        assertThat(policy.admit(a("hazard:7", AnnouncementPriority.CRITICAL, escalation = true))).isEqualTo(Decision.Accept)
        // Same priority again with escalation flag is not an escalation.
        time.advance(500)
        assertThat(policy.admit(a("hazard:7", AnnouncementPriority.CRITICAL, escalation = true)))
            .isEqualTo(Decision.Drop(DropReason.COOLDOWN))
    }

    @Test
    fun `escalation flag without a higher priority does not bypass`() {
        policy.admit(a("hazard:1", AnnouncementPriority.CRITICAL))
        time.advance(100)
        assertThat(policy.admit(a("hazard:1", AnnouncementPriority.WARNING, escalation = true)))
            .isEqualTo(Decision.Drop(DropReason.COOLDOWN))
    }

    @Test
    fun `minimal verbosity drops info and unimportant observations only`() {
        policy.verbosity = Verbosity.MINIMAL
        assertThat(policy.admit(a("info", AnnouncementPriority.INFO))).isEqualTo(Decision.Drop(DropReason.VERBOSITY))
        assertThat(policy.admit(a("obs", AnnouncementPriority.OBSERVATION))).isEqualTo(Decision.Drop(DropReason.VERBOSITY))
        assertThat(policy.admit(a(AnnouncementKeys.important("traffic:3"), AnnouncementPriority.OBSERVATION)))
            .isEqualTo(Decision.Accept)
        assertThat(policy.admit(a("nav", AnnouncementPriority.NAVIGATION))).isEqualTo(Decision.Accept)
        assertThat(policy.admit(a("warn", AnnouncementPriority.WARNING))).isEqualTo(Decision.Accept)
        assertThat(policy.admit(a("crit", AnnouncementPriority.CRITICAL))).isEqualTo(Decision.Accept)
    }

    @Test
    fun `important exact keys from config survive minimal`() {
        val p = AnnouncementPolicy(time, AnnouncementPolicyConfig(importantKeys = setOf("camera:unavailable")), Verbosity.MINIMAL)
        assertThat(p.admit(a("camera:unavailable", AnnouncementPriority.OBSERVATION))).isEqualTo(Decision.Accept)
    }

    @Test
    fun `detailed allows everything`() {
        policy.verbosity = Verbosity.DETAILED
        assertThat(policy.admit(a("info", AnnouncementPriority.INFO))).isEqualTo(Decision.Accept)
    }

    @Test
    fun `evaluate does not record`() {
        val x = a("obs", AnnouncementPriority.OBSERVATION)
        assertThat(policy.evaluate(x)).isEqualTo(Decision.Accept)
        assertThat(policy.evaluate(x)).isEqualTo(Decision.Accept)
        assertThat(policy.admit(x)).isEqualTo(Decision.Accept)
        assertThat(policy.evaluate(x)).isNotEqualTo(Decision.Accept)
    }

    @Test
    fun `dropped announcements do not extend the cooldown`() {
        policy.admit(a("obs", AnnouncementPriority.OBSERVATION))
        time.advance(3_000)
        policy.admit(a("obs", AnnouncementPriority.OBSERVATION)) // dropped
        time.advance(1_000)
        assertThat(policy.admit(a("obs", AnnouncementPriority.OBSERVATION))).isEqualTo(Decision.Accept)
    }
}
