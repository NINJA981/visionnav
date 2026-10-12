package com.visionnav.core.announce

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.announce.testing.FakeEarconPlayer
import com.visionnav.core.announce.testing.FakeHapticPlayer
import com.visionnav.core.announce.testing.FakeSpeaker
import com.visionnav.core.common.FakeTimeSource
import kotlinx.coroutines.test.runTest
import app.cash.turbine.test
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class AudioQueueManagerTest {
    private val time = FakeTimeSource(10_000)
    private val speaker = FakeSpeaker()
    private val earcons = FakeEarconPlayer()
    private val haptics = FakeHapticPlayer()
    private val blockedByGuard = mutableListOf<String>()
    private val log = mutableListOf<AudioLogEntry>()

    private fun manager(
        policyConfig: AnnouncementPolicyConfig = AnnouncementPolicyConfig(),
        config: AudioQueueConfig = AudioQueueConfig(),
    ) = AudioQueueManager(
        speaker = speaker,
        earcons = earcons,
        haptics = haptics,
        time = time,
        guard = SafetyPhraseGuard { blockedByGuard += it },
        policy = AnnouncementPolicy(time, policyConfig),
        logSink = { synchronized(log) { log += it } },
        config = config,
    )

    /** Policy that never throttles, to exercise queue mechanics in isolation. */
    private val permissive = AnnouncementPolicyConfig(
        sameKeyCooldownMs = AnnouncementPriority.entries.associateWith { 0L },
        ambientMinIntervalMs = 0,
    )

    private fun obs(text: String, key: String = text, ttl: Long = 4_000) =
        Announcement(text, AnnouncementPriority.OBSERVATION, key, ttlMs = ttl)

    private fun nav(text: String, key: String = text, ttl: Long = 8_000) =
        Announcement(text, AnnouncementPriority.NAVIGATION, key, ttlMs = ttl, earcon = Earcon.TURN, haptic = HapticPattern.TURN_RIGHT)

    private fun critical(text: String, key: String = text) =
        Announcement(text, AnnouncementPriority.CRITICAL, key, earcon = Earcon.CRITICAL_ALERT, haptic = HapticPattern.CRITICAL)

    // --- Test 21: no audio spam ------------------------------------------------------------

    @Test
    fun `test 21 duplicate observations within cooldown are spoken once`() {
        val m = manager()
        repeat(6) {
            m.submit(obs("Vehicle approaching.", key = "traffic:track-4"))
            if (speaker.currentText != null) speaker.finish()
            time.advance(500)
        }
        assertThat(speaker.spokenTexts).containsExactly("Vehicle approaching.")
        time.advance(4_000)
        m.submit(obs("Vehicle approaching.", key = "traffic:track-4"))
        assertThat(speaker.spokenTexts).hasSize(2)
    }

    @Test
    fun `distinct observations are globally rate limited`() {
        val m = manager()
        m.submit(obs("Vehicle approaching.", key = "t1"))
        speaker.finish()
        time.advance(1_000)
        val r = m.submit(obs("Vehicle moving away.", key = "t2"))
        assertThat(r).isEqualTo(SubmitResult.Dropped(AnnouncementPolicy.DropReason.AMBIENT_RATE_LIMIT))
        assertThat(speaker.spokenTexts).containsExactly("Vehicle approaching.")
    }

    // --- Test 22 & preemption --------------------------------------------------------------

    @Test
    fun `critical preempts in-flight observation and the observation is dropped`() {
        val m = manager()
        m.submit(obs("Vehicle moving left to right in the camera view."))
        assertThat(speaker.currentText).isEqualTo("Vehicle moving left to right in the camera view.")

        val r = m.submit(critical("Stop. Obstacle directly ahead."))
        assertThat(r).isEqualTo(SubmitResult.Speaking)
        assertThat(speaker.stopCount).isEqualTo(1)
        assertThat(speaker.currentText).isEqualTo("Stop. Obstacle directly ahead.")
        assertThat(earcons.played).contains(Earcon.CRITICAL_ALERT)
        assertThat(haptics.played).contains(HapticPattern.CRITICAL)

        speaker.finish()
        assertThat(speaker.currentText).isNull()
        assertThat(speaker.spokenTexts).hasSize(2)
        assertThat(m.speaking.value).isNull()
    }

    @Test
    fun `critical preempts navigation and interrupted navigation is re-queued within ttl`() {
        val m = manager()
        m.submit(nav("Turn right onto Anna Salai in 40 metres."))
        m.submit(critical("Stop. Obstacle directly ahead."))
        assertThat(speaker.currentText).isEqualTo("Stop. Obstacle directly ahead.")
        time.advance(2_000)
        speaker.finish()
        assertThat(speaker.currentText).isEqualTo("Turn right onto Anna Salai in 40 metres.")
        assertThat(m.history.value.map { it.outcome }).contains(AudioOutcome.REQUEUED)
    }

    @Test
    fun `interrupted navigation past its ttl is not re-spoken`() {
        val m = manager()
        m.submit(nav("Turn right now.", ttl = 3_000))
        m.submit(critical("Stop. Obstacle directly ahead."))
        time.advance(3_500)
        speaker.finish()
        assertThat(speaker.currentText).isNull()
        assertThat(speaker.spokenTexts).containsExactly("Turn right now.", "Stop. Obstacle directly ahead.").inOrder()
        assertThat(m.history.value.last().outcome).isEqualTo(AudioOutcome.EXPIRED)
    }

    @Test
    fun `test 22 observation submitted during critical waits and expires when stale`() {
        val m = manager()
        m.submit(critical("Stop. Person directly ahead."))
        val r = m.submit(obs("Vehicle moving away.", ttl = 3_000))
        assertThat(r).isEqualTo(SubmitResult.Queued)
        assertThat(speaker.currentText).isEqualTo("Stop. Person directly ahead.")
        time.advance(3_001)
        speaker.finish()
        assertThat(speaker.spokenTexts).containsExactly("Stop. Person directly ahead.")
        assertThat(m.history.value.single { it.announcement.text == "Vehicle moving away." && it.outcome == AudioOutcome.EXPIRED })
            .isNotNull()
    }

    @Test
    fun `observation submitted during critical is spoken afterwards when still fresh`() {
        val m = manager()
        m.submit(critical("Stop. Person directly ahead."))
        m.submit(obs("Vehicle moving away.", ttl = 4_000))
        time.advance(1_500)
        speaker.finish()
        assertThat(speaker.currentText).isEqualTo("Vehicle moving away.")
    }

    @Test
    fun `critical is never delayed by queued lower items`() {
        val m = manager(policyConfig = permissive)
        m.submit(obs("Car parked on the left."))
        m.submit(nav("Turn left in 30 metres."))      // preempts the observation
        m.submit(obs("Bicycle in the camera view."))  // waits
        m.submit(Announcement("Battery at 20 percent.", AnnouncementPriority.INFO, "battery"))
        m.submit(Announcement("Obstacle ahead.", AnnouncementPriority.WARNING, "obstacle"))
        assertThat(speaker.currentText).isEqualTo("Obstacle ahead.")

        val r = m.submit(critical("Stop. Vehicle very close."))
        assertThat(r).isEqualTo(SubmitResult.Speaking)
        assertThat(speaker.currentText).isEqualTo("Stop. Vehicle very close.")
    }

    @Test
    fun `waiting items are spoken by priority then fifo`() {
        val m = manager(policyConfig = permissive)
        m.submit(critical("Stop."))
        m.submit(obs("Observation one.", ttl = 60_000))
        m.submit(nav("Nav one."))
        m.submit(obs("Observation two.", ttl = 60_000))
        m.submit(Announcement("Warning one.", AnnouncementPriority.WARNING, "w1", ttlMs = 60_000))
        m.submit(nav("Nav two."))
        speaker.finishAll()
        assertThat(speaker.spokenTexts).containsExactly(
            "Stop.", "Warning one.", "Nav one.", "Nav two.", "Observation one.", "Observation two.",
        ).inOrder()
    }

    @Test
    fun `second critical waits for the first but its cues fire immediately`() {
        val m = manager()
        m.submit(critical("Stop. Person directly ahead.", key = "a"))
        haptics.played.clear()
        earcons.played.clear()
        m.submit(critical("Stop. Pole on the right.", key = "b"))
        assertThat(speaker.currentText).isEqualTo("Stop. Person directly ahead.")
        assertThat(haptics.played).containsExactly(HapticPattern.CRITICAL)
        assertThat(earcons.played).containsExactly(Earcon.CRITICAL_ALERT)
        speaker.finish()
        assertThat(speaker.currentText).isEqualTo("Stop. Pole on the right.")
        // Cues are not repeated when the words finally play.
        assertThat(haptics.played).hasSize(1)
    }

    @Test
    fun `critical with tiny ttl still gets its minimum ttl`() {
        val m = manager()
        m.submit(critical("Stop. Person directly ahead.", key = "a"))
        m.submit(critical("Stop. Pole on the right.", key = "b").copy(ttlMs = 100))
        time.advance(2_000)
        speaker.finish()
        assertThat(speaker.currentText).isEqualTo("Stop. Pole on the right.")
    }

    // --- Test 24: forbidden text never reaches the speaker ------------------------------------

    @Test
    fun `test 24 forbidden text is never spoken and is logged as a defect`() {
        val m = manager()
        for (p in AnnouncementPriority.entries) {
            val r = m.submit(Announcement("It is safe to cross now", p, "bad-$p"))
            assertThat(r).isEqualTo(SubmitResult.Blocked)
        }
        assertThat(m.repeatLast()).isFalse()
        assertThat(speaker.spoken).isEmpty()
        assertThat(blockedByGuard).hasSize(AnnouncementPriority.entries.size)
        assertThat(log.filter { it.outcome == AudioOutcome.BLOCKED_BY_GUARD }).hasSize(AnnouncementPriority.entries.size)
        assertThat(log.first().detail).contains("DEFECT")
    }

    @Test
    fun `allowed crossing wording is spoken`() {
        val m = manager()
        m.submit(nav("Road crossing ahead. Stop at the kerb and assess traffic."))
        assertThat(speaker.currentText).isEqualTo("Road crossing ahead. Stop at the kerb and assess traffic.")
    }

    // --- muted / haptics-only ---------------------------------------------------------------

    @Test
    fun `haptics fire in muted mode without speech or earcons`() {
        val m = manager()
        m.setMuted(true)
        val r1 = m.submit(nav("Turn right in 40 metres."))
        val r2 = m.submit(critical("Stop. Obstacle directly ahead."))
        assertThat(r1).isEqualTo(SubmitResult.HapticOnly)
        assertThat(r2).isEqualTo(SubmitResult.HapticOnly)
        assertThat(haptics.played).containsExactly(HapticPattern.TURN_RIGHT, HapticPattern.CRITICAL).inOrder()
        assertThat(speaker.spoken).isEmpty()
        assertThat(earcons.played).isEmpty()
    }

    @Test
    fun `muting interrupts current speech and unmuting resumes speaking new items`() {
        val m = manager()
        m.submit(nav("Turn right in 40 metres."))
        m.setMuted(true)
        assertThat(speaker.stopCount).isEqualTo(1)
        assertThat(m.speaking.value).isNull()
        m.setMuted(false)
        m.submit(nav("Turn left in 20 metres."))
        assertThat(speaker.currentText).isEqualTo("Turn left in 20 metres.")
    }

    // --- speaker not ready ------------------------------------------------------------------

    @Test
    fun `speaker not ready holds the queue but critical cues fire immediately`() {
        speaker.ready = false
        val m = manager()
        m.submit(nav("Turn right in 40 metres."))
        assertThat(haptics.played).isEmpty()
        m.submit(critical("Stop. Obstacle directly ahead."))
        assertThat(haptics.played).containsExactly(HapticPattern.CRITICAL)
        assertThat(earcons.played).containsExactly(Earcon.CRITICAL_ALERT)
        assertThat(speaker.spoken).isEmpty()

        speaker.ready = true
        m.onSpeakerReady()
        assertThat(speaker.currentText).isEqualTo("Stop. Obstacle directly ahead.")
        assertThat(haptics.played).containsExactly(HapticPattern.CRITICAL) // not repeated
        speaker.finish()
        assertThat(speaker.currentText).isEqualTo("Turn right in 40 metres.")
    }

    // --- queue mechanics --------------------------------------------------------------------

    @Test
    fun `newer queued item with the same key replaces the older one`() {
        val m = manager(policyConfig = permissive)
        m.submit(nav("Turn right in 40 metres."))
        m.submit(obs("Vehicle moving left to right in the camera view.", key = "traffic:9"))
        m.submit(obs("Vehicle approaching.", key = "traffic:9"))
        assertThat(m.queuedCount).isEqualTo(1)
        speaker.finishAll()
        assertThat(speaker.spokenTexts).containsExactly("Turn right in 40 metres.", "Vehicle approaching.").inOrder()
    }

    @Test
    fun `observation queue is capped and drops the oldest`() {
        val m = manager(policyConfig = permissive)
        m.submit(nav("Turn right in 40 metres."))
        (1..5).forEach { m.submit(obs("Observation $it.", ttl = 60_000)) }
        assertThat(m.queuedCount).isEqualTo(3)
        speaker.finishAll()
        assertThat(speaker.spokenTexts.drop(1)).containsExactly("Observation 3.", "Observation 4.", "Observation 5.").inOrder()
        assertThat(log.count { it.outcome == AudioOutcome.QUEUE_OVERFLOW }).isEqualTo(2)
    }

    @Test
    fun `same or lower priority waits instead of preempting`() {
        val m = manager(policyConfig = permissive)
        m.submit(nav("Turn right in 40 metres."))
        m.submit(nav("Turn left in 80 metres."))
        m.submit(obs("Bench on the left."))
        assertThat(speaker.stopCount).isEqualTo(0)
        assertThat(speaker.currentText).isEqualTo("Turn right in 40 metres.")
    }

    @Test
    fun `repeatLast re-speaks the last navigation instruction bypassing cooldown`() {
        val m = manager()
        m.submit(nav("Turn right onto Anna Salai in 40 metres.", key = "nav:prepare:1"))
        speaker.finish()
        time.advance(3_000)
        m.submit(obs("Bench on the left."))
        speaker.finish()
        assertThat(m.repeatLast()).isTrue()
        assertThat(speaker.currentText).isEqualTo("Turn right onto Anna Salai in 40 metres.")
        speaker.finish()
        assertThat(m.repeatLast(preferNavigation = false)).isTrue()
        assertThat(speaker.currentText).isEqualTo("Turn right onto Anna Salai in 40 metres.")
    }

    @Test
    fun `repeatLast with nothing spoken returns false`() {
        assertThat(manager().repeatLast()).isFalse()
    }

    @Test
    fun `stopAll clears speech and queue`() {
        val m = manager(policyConfig = permissive)
        m.submit(nav("Turn right in 40 metres."))
        m.submit(obs("Bench on the left."))
        m.stopAll()
        assertThat(speaker.stopCount).isEqualTo(1)
        assertThat(m.queuedCount).isEqualTo(0)
        assertThat(m.speaking.value).isNull()
        assertThat(speaker.spoken).hasSize(1)
    }

    @Test
    fun `verbosity minimal drops info through the manager`() {
        val m = manager()
        m.setVerbosity(Verbosity.MINIMAL)
        m.submit(Announcement("Battery at 20 percent.", AnnouncementPriority.INFO, "battery"))
        m.submit(obs("Bench on the left."))
        assertThat(speaker.spoken).isEmpty()
        m.submit(obs("Vehicle approaching.", key = AnnouncementKeys.important("traffic:1")))
        assertThat(speaker.currentText).isEqualTo("Vehicle approaching.")
    }

    @Test
    fun `stale interrupt callbacks are ignored`() {
        val m = manager()
        m.submit(nav("Turn right in 40 metres."))
        val firstId = speaker.spoken.single().id
        m.submit(critical("Stop."))
        // A late completion callback for the old utterance id must not end the critical.
        speaker.finish()
        assertThat(m.speaking.value).isNotNull() // requeued navigation now speaking
        assertThat(speaker.spoken.last().id).isNotEqualTo(firstId)
    }

    @Test
    fun `speaking flow reflects current utterance`() = runTest {
        val m = manager()
        m.speaking.test {
            assertThat(awaitItem()).isNull()
            m.submit(nav("Turn right in 40 metres."))
            assertThat(awaitItem()?.text).isEqualTo("Turn right in 40 metres.")
            speaker.finish()
            assertThat(awaitItem()).isNull()
        }
    }

    @Test
    fun `history is bounded`() {
        val m = manager(policyConfig = permissive, config = AudioQueueConfig(historySize = 5))
        repeat(20) {
            m.submit(nav("Instruction $it."))
            speaker.finish()
        }
        assertThat(m.history.value).hasSize(5)
        assertThat(m.history.value.last().announcement.text).isEqualTo("Instruction 19.")
    }

    @Test
    fun `speaker completing synchronously drains the queue without recursion problems`() {
        val sync = object : Speaker {
            val texts = mutableListOf<String>()
            override fun speak(utteranceId: String, text: String, onDone: (completed: Boolean) -> Unit) {
                texts += text
                onDone(true)
            }
            override fun stop() {}
            override val isReady = true
        }
        val m = AudioQueueManager(sync, earcons, haptics, time, SafetyPhraseGuard(), AnnouncementPolicy(time, permissive))
        repeat(50) { m.submit(nav("Instruction $it.")) }
        assertThat(sync.texts).hasSize(50)
        assertThat(m.speaking.value).isNull()
    }

    @Test
    fun `concurrent submits and callbacks keep the pipeline consistent`() {
        val m = manager(policyConfig = permissive, config = AudioQueueConfig(
            maxQueuedPerPriority = AnnouncementPriority.entries.associateWith { 1_000 },
        ))
        val threads = 8
        val perThread = 200
        val start = CountDownLatch(1)
        val errors = AtomicInteger()
        val workers = (0 until threads).map { t ->
            Thread {
                start.await()
                try {
                    repeat(perThread) { i ->
                        val p = AnnouncementPriority.entries[(t + i) % AnnouncementPriority.entries.size]
                        m.submit(Announcement("T$t item $i.", p, "t$t-$i", ttlMs = Long.MAX_VALUE / 4))
                        if (i % 3 == 0) runCatching { speaker.finish() }
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }
        speaker.finishAll(max = 10_000)
        assertThat(errors.get()).isEqualTo(0)
        assertThat(m.speaking.value).isNull()
        assertThat(m.queuedCount).isEqualTo(0)
    }

    @Test
    fun `a speaker that throws does not wedge the pipeline`() {
        val flaky = object : Speaker {
            var calls = 0
            val texts = mutableListOf<String>()
            override fun speak(utteranceId: String, text: String, onDone: (completed: Boolean) -> Unit) {
                if (calls++ == 0) throw IllegalStateException("TTS died")
                texts += text
            }
            override fun stop() {}
            override val isReady = true
        }
        val m = AudioQueueManager(flaky, earcons, haptics, time, SafetyPhraseGuard(), AnnouncementPolicy(time, permissive))
        m.submit(nav("Turn right in 40 metres."))
        assertThat(m.speaking.value).isNull()
        m.submit(critical("Stop. Obstacle directly ahead."))
        assertThat(flaky.texts).containsExactly("Stop. Obstacle directly ahead.")
    }
}
