package com.visionnav.core.announce

import com.visionnav.core.common.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tunables for [AudioQueueManager].
 *
 * @param maxQueuedPerPriority maximum number of *waiting* items per priority; when exceeded the
 *   oldest waiting item of that priority is dropped. Keeps a burst of observations from building a
 *   backlog that would be spoken long after it stopped being true.
 * @param criticalMinTtlMs floor for a CRITICAL item's TTL. CRITICAL only ever waits behind another
 *   CRITICAL (or a TTS engine that is still initialising) and its earcon/haptic have already fired,
 *   so a tiny TTL would silently swallow the spoken explanation. It still expires eventually,
 *   because a hazard description spoken many seconds late is misleading.
 * @param requeueOnPreempt priorities whose interrupted utterance is put back in the queue (if still
 *   within its TTL). Navigation instructions remain useful after a short interruption; observations
 *   are snapshots that are better dropped and re-observed.
 * @param historySize number of [AudioLogEntry] kept in [AudioQueueManager.history].
 */
data class AudioQueueConfig(
    val maxQueuedPerPriority: Map<AnnouncementPriority, Int> = mapOf(
        AnnouncementPriority.CRITICAL to 8,
        AnnouncementPriority.WARNING to 4,
        AnnouncementPriority.NAVIGATION to 4,
        AnnouncementPriority.OBSERVATION to 3,
        AnnouncementPriority.INFO to 2,
    ),
    val criticalMinTtlMs: Long = 3_000L,
    val requeueOnPreempt: Set<AnnouncementPriority> = setOf(AnnouncementPriority.NAVIGATION),
    val historySize: Int = 100,
    /**
     * When true, an announcement rejected **only** by the global ambient rate limit is held (within
     * its TTL) and released once the limit allows, instead of being dropped. Producers such as the
     * traffic reporter deduplicate on their side and never resend a state they believe was announced,
     * so dropping here would silently lose a fresh observation.
     */
    val deferAmbientRateLimited: Boolean = true,
    val maxDeferred: Int = 3,
)

/** What happened to an announcement. Used in [AudioLogEntry] for the debug/session log. */
enum class AudioOutcome {
    /** Speech started. */
    STARTED,
    COMPLETED,
    /** Speech was cut off (preempted, stopAll, mute, or the engine reported an interruption). */
    INTERRUPTED,
    /** Put back in the queue after being preempted. */
    REQUEUED,
    /** Waited longer than its TTL and was discarded unspoken. */
    EXPIRED,
    /** Held back by the ambient rate limit; it will be released when the limit allows (or expire). */
    DEFERRED,
    /** Rejected by [AnnouncementPolicy] (verbosity, cooldown, rate limit). */
    DROPPED_BY_POLICY,
    /** Rejected by [SafetyPhraseGuard]. This is a **defect** in a phrase generator. */
    BLOCKED_BY_GUARD,
    /** Replaced in the queue by a newer item with the same key. */
    REPLACED,
    /** Dropped because a same-key item with higher priority is already waiting. */
    SUPERSEDED,
    /** Dropped because too many items of its priority were waiting. */
    QUEUE_OVERFLOW,
    /** Speech is muted: only the haptic pattern was played. */
    HAPTIC_ONLY,
    /** Discarded by [AudioQueueManager.stopAll] or [AudioQueueManager.setMuted]. */
    CLEARED,
}

data class AudioLogEntry(
    val atMs: Long,
    val announcement: Announcement,
    val outcome: AudioOutcome,
    val detail: String? = null,
)

/** Immediate result of [AudioQueueManager.submit]. */
sealed interface SubmitResult {
    /** Speech started (or was scheduled to start) immediately. */
    data object Speaking : SubmitResult
    data object Queued : SubmitResult
    data object HapticOnly : SubmitResult
    data class Dropped(val reason: AnnouncementPolicy.DropReason) : SubmitResult
    data object Superseded : SubmitResult
    data object Blocked : SubmitResult
}

/**
 * The single speech pipeline. Every utterance in the app goes through here, in the order
 * `SafetyPhraseGuard → AnnouncementPolicy → priority queue → Speaker`.
 *
 * ### Audio priority rules (safety rationale)
 * - Items are ordered by [AnnouncementPriority.rank], then FIFO.
 * - A new item with a **higher** rank than the in-flight utterance **preempts** it: the speaker is
 *   stopped and the new item speaks immediately. This is what guarantees that a CRITICAL warning is
 *   never delayed by a traffic description or a turn instruction. An interrupted NAVIGATION item is
 *   re-queued (if still within TTL); interrupted OBSERVATION/INFO items are dropped because they
 *   describe a moment that has passed.
 * - Same-or-lower rank waits. Waiting items expire after their `ttlMs` (measured from
 *   `createdAtMs` when that is a plausible monotonic timestamp, otherwise from submit time), so
 *   stale observations are never spoken late.
 * - A newer waiting item with the same `dedupeKey` replaces the older one (keeps observations
 *   fresh); waiting queues are capped per priority.
 * - CRITICAL items fire their earcon + haptic **at submit time** even if they must wait (another
 *   CRITICAL in flight, or TTS still initialising), so the alert itself is never late.
 * - Muted (haptics-only mode): nothing is spoken and no earcons play, but haptic patterns still
 *   fire for every admitted announcement.
 * - Every text passes [SafetyPhraseGuard]; blocked text is never spoken and is logged as a defect.
 *
 * ### Threading
 * All state is guarded by one lock. Calls into [Speaker], [EarconPlayer] and [HapticPlayer] are
 * never made while holding the lock: they are appended to an ordered effect queue and executed by
 * whichever thread is currently draining it. This keeps hardware calls in decision order while
 * avoiding deadlocks with TTS callbacks that arrive on binder threads (including callbacks invoked
 * synchronously from inside `speak()` or `stop()`). Callbacks for utterances that are no longer
 * current are ignored by utterance id.
 */
class AudioQueueManager(
    private val speaker: Speaker,
    private val earcons: EarconPlayer,
    private val haptics: HapticPlayer,
    private val time: TimeSource,
    private val guard: SafetyPhraseGuard,
    private val policy: AnnouncementPolicy,
    private val logSink: (AudioLogEntry) -> Unit = {},
    private val config: AudioQueueConfig = AudioQueueConfig(),
) {
    private class Entry(
        val announcement: Announcement,
        val seq: Long,
        val originMs: Long,
        /** True once the earcon/haptic were played (CRITICAL fires cues before it can speak). */
        var cuesFired: Boolean = false,
    ) {
        val rank: Int get() = announcement.priority.rank
    }

    private val lock = Any()
    private val queue = ArrayList<Entry>()
    private val deferred = ArrayList<Entry>()
    private var inFlight: Entry? = null
    private var inFlightUtteranceId: String? = null
    private var seqCounter = 0L
    private var utteranceCounter = 0L
    private var muted = false
    private var lastSpoken: Announcement? = null
    private var lastNavigation: Announcement? = null

    private val effects = ArrayDeque<() -> Unit>()
    private var draining = false

    private val _speaking = MutableStateFlow<Announcement?>(null)

    /** The announcement currently being spoken, or null. */
    val speaking: StateFlow<Announcement?> = _speaking.asStateFlow()

    private val _history = MutableStateFlow<List<AudioLogEntry>>(emptyList())

    /** Bounded, most-recent-last log of what happened to each announcement (debug overlay / session log). */
    val history: StateFlow<List<AudioLogEntry>> = _history.asStateFlow()

    val isMuted: Boolean get() = synchronized(lock) { muted }

    /** Number of items waiting (not counting the in-flight one). */
    val queuedCount: Int get() = synchronized(lock) { queue.size }

    /** Submits an announcement. Never blocks on speech. */
    fun submit(a: Announcement): SubmitResult {
        // The guard is pure; run it outside the lock so its defect callback never runs locked.
        val blocked = guard.check(a.text) is SafetyPhraseGuard.Verdict.Blocked
        return transact { if (blocked) blockedLocked(a) else submitLocked(a, bypassPolicy = false) }
    }

    /**
     * Re-speaks the last NAVIGATION utterance if there is one (the instruction the user most likely
     * wants repeated), otherwise the last spoken utterance of any kind. Bypasses cooldowns because
     * the user asked explicitly. Returns false if there is nothing to repeat.
     */
    fun repeatLast(preferNavigation: Boolean = true): Boolean {
        val source = synchronized(lock) {
            if (preferNavigation) lastNavigation ?: lastSpoken else lastSpoken ?: lastNavigation
        } ?: return false
        val repeat = source.copy(
            dedupeKey = REPEAT_KEY_PREFIX + source.dedupeKey.removePrefix(REPEAT_KEY_PREFIX),
            createdAtMs = 0,
            escalation = false,
        )
        val blocked = guard.check(repeat.text) is SafetyPhraseGuard.Verdict.Blocked
        val result = transact { if (blocked) blockedLocked(repeat) else submitLocked(repeat, bypassPolicy = true) }
        return result !is SubmitResult.Blocked
    }

    /** Stops current speech and discards everything waiting. */
    fun stopAll() = transact {
        clearAllLocked()
    }

    /**
     * Haptics-only mode. Muting interrupts current speech and discards waiting speech; while muted
     * every admitted announcement plays only its haptic pattern.
     */
    fun setMuted(value: Boolean) = transact {
        if (value != muted) {
            muted = value
            if (value) clearAllLocked() else pumpLocked()
        }
    }

    fun setVerbosity(v: Verbosity) {
        policy.verbosity = v
    }

    /** Call when the TTS engine finishes initialising so held items can be spoken. */
    fun onSpeakerReady() = transact { pumpLocked() }

    /** Call periodically (e.g. every 500 ms) so rate-limited items are released on time. */
    fun tick() = transact { pumpLocked() }

    // ---------------------------------------------------------------------------------------
    // Internals. Everything named *Locked runs under [lock].
    // ---------------------------------------------------------------------------------------

    /** Runs [block] under the lock, then drains side effects outside the lock. */
    private inline fun <T> transact(block: () -> T): T {
        val result: T
        val shouldDrain: Boolean
        synchronized(lock) {
            result = block()
            shouldDrain = !draining && effects.isNotEmpty()
            if (shouldDrain) draining = true
        }
        if (shouldDrain) drainEffects()
        return result
    }

    private fun drainEffects() {
        while (true) {
            val effect = synchronized(lock) {
                val next = effects.removeFirstOrNull()
                if (next == null) draining = false
                next
            } ?: return
            try {
                effect()
            } catch (t: Throwable) {
                // A misbehaving output device must not wedge the pipeline; keep draining.
                // (Logged without an announcement because no single item is at fault.)
                System.err.println("AudioQueueManager effect failed: $t")
            }
        }
    }

    private fun blockedLocked(a: Announcement): SubmitResult {
        logLocked(a, AudioOutcome.BLOCKED_BY_GUARD, "DEFECT: forbidden safety phrase")
        return SubmitResult.Blocked
    }

    private fun submitLocked(a: Announcement, bypassPolicy: Boolean): SubmitResult {
        val now = time.elapsedMs()
        if (!bypassPolicy) {
            val decision = policy.admit(a)
            if (decision is AnnouncementPolicy.Decision.Drop) {
                if (decision.reason == AnnouncementPolicy.DropReason.AMBIENT_RATE_LIMIT && config.deferAmbientRateLimited && !muted) {
                    return deferLocked(a, now)
                }
                logLocked(a, AudioOutcome.DROPPED_BY_POLICY, decision.reason.name)
                return SubmitResult.Dropped(decision.reason)
            }
        }
        if (muted) {
            if (a.haptic != HapticPattern.NONE) effects.addLast { haptics.play(a.haptic) }
            logLocked(a, AudioOutcome.HAPTIC_ONLY)
            return SubmitResult.HapticOnly
        }

        val origin = if (a.createdAtMs in 1..now) a.createdAtMs else now
        val entry = Entry(a, seqCounter++, origin)

        // Replace-by-key among waiting items.
        val sameKey = queue.filter { it.announcement.dedupeKey == a.dedupeKey }
        if (sameKey.any { it.rank > entry.rank }) {
            logLocked(a, AudioOutcome.SUPERSEDED)
            return SubmitResult.Superseded
        }
        for (old in sameKey) {
            queue.remove(old)
            logLocked(old.announcement, AudioOutcome.REPLACED)
        }

        val current = inFlight
        val canSpeakNow = speaker.isReady
        val mustWait = !canSpeakNow || (current != null && current.rank >= entry.rank)
        if (a.priority == AnnouncementPriority.CRITICAL && mustWait) {
            // CRITICAL must never be late: if it cannot be spoken this instant, the alert cues
            // still fire now and the words follow as soon as possible.
            fireCuesLocked(entry)
        }

        if (current != null && canSpeakNow && entry.rank > current.rank) {
            preemptLocked(current, now)
            queue.add(entry)
            enforceCapLocked(current.announcement.priority)
            pumpLocked()
            return SubmitResult.Speaking
        }

        queue.add(entry)
        enforceCapLocked(a.priority)
        if (current == null && canSpeakNow) {
            pumpLocked()
            return if (inFlight === entry) SubmitResult.Speaking else SubmitResult.Queued
        }
        return SubmitResult.Queued
    }

    private fun deferLocked(a: Announcement, now: Long): SubmitResult {
        val origin = if (a.createdAtMs in 1..now) a.createdAtMs else now
        deferred.filter { it.announcement.dedupeKey == a.dedupeKey }.forEach {
            deferred.remove(it)
            logLocked(it.announcement, AudioOutcome.REPLACED)
        }
        deferred.add(Entry(a, seqCounter++, origin))
        while (deferred.size > config.maxDeferred) {
            val oldest = deferred.minBy { it.seq }
            deferred.remove(oldest)
            logLocked(oldest.announcement, AudioOutcome.QUEUE_OVERFLOW)
        }
        logLocked(a, AudioOutcome.DEFERRED)
        return SubmitResult.Queued
    }

    /** Moves at most one deferred item into the queue once the ambient limit allows it. */
    private fun releaseDeferredLocked(now: Long) {
        val it = deferred.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (isExpired(e, now)) {
                it.remove()
                logLocked(e.announcement, AudioOutcome.EXPIRED)
            }
        }
        if (deferred.isEmpty() || now < policy.ambientAvailableAtMs()) return
        val next = deferred.minWith(compareByDescending<Entry> { it.rank }.thenBy { it.seq })
        deferred.remove(next)
        when (val d = policy.admit(next.announcement)) {
            AnnouncementPolicy.Decision.Accept -> {
                queue.add(next)
                enforceCapLocked(next.announcement.priority)
            }
            is AnnouncementPolicy.Decision.Drop -> logLocked(next.announcement, AudioOutcome.DROPPED_BY_POLICY, d.reason.name)
        }
    }

    private fun preemptLocked(current: Entry, now: Long) {
        inFlight = null
        inFlightUtteranceId = null
        _speaking.value = null
        effects.addLast { speaker.stop() }
        logLocked(current.announcement, AudioOutcome.INTERRUPTED, "preempted")
        if (current.announcement.priority in config.requeueOnPreempt && !isExpired(current, now)) {
            queue.add(current)
            logLocked(current.announcement, AudioOutcome.REQUEUED)
        }
    }

    private fun enforceCapLocked(priority: AnnouncementPriority) {
        val cap = config.maxQueuedPerPriority[priority] ?: return
        while (true) {
            val same = queue.filter { it.announcement.priority == priority }
            if (same.size <= cap) return
            val oldest = same.minBy { it.seq }
            queue.remove(oldest)
            logLocked(oldest.announcement, AudioOutcome.QUEUE_OVERFLOW)
        }
    }

    private fun pumpLocked() {
        if (inFlight != null || muted || !speaker.isReady) return
        val now = time.elapsedMs()
        releaseDeferredLocked(now)
        purgeExpiredLocked(now)
        val next = queue.maxWithOrNull(compareBy<Entry> { it.rank }.thenByDescending { it.seq }) ?: return
        queue.remove(next)
        startSpeakingLocked(next)
    }

    private fun startSpeakingLocked(entry: Entry) {
        fireCuesLocked(entry)
        val id = "vn-${utteranceCounter++}"
        inFlight = entry
        inFlightUtteranceId = id
        val a = entry.announcement
        _speaking.value = a
        lastSpoken = a
        if (a.priority == AnnouncementPriority.NAVIGATION) lastNavigation = a
        logLocked(a, AudioOutcome.STARTED)
        effects.addLast {
            try {
                speaker.speak(id, a.text) { completed -> onSpeakDone(id, completed) }
            } catch (t: Throwable) {
                // A failing engine must not leave this item "in flight" forever and block CRITICAL.
                onSpeakDone(id, completed = false)
                throw t
            }
        }
    }

    private fun onSpeakDone(utteranceId: String, completed: Boolean) = transact {
        if (utteranceId == inFlightUtteranceId) {
            val done = inFlight
            inFlight = null
            inFlightUtteranceId = null
            _speaking.value = null
            if (done != null) {
                logLocked(done.announcement, if (completed) AudioOutcome.COMPLETED else AudioOutcome.INTERRUPTED)
            }
            pumpLocked()
        }
    }

    private fun fireCuesLocked(entry: Entry) {
        if (entry.cuesFired) return
        entry.cuesFired = true
        val a = entry.announcement
        if (a.earcon != Earcon.NONE) effects.addLast { earcons.play(a.earcon) }
        if (a.haptic != HapticPattern.NONE) effects.addLast { haptics.play(a.haptic) }
    }

    private fun purgeExpiredLocked(now: Long) {
        val it = queue.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (isExpired(e, now)) {
                it.remove()
                logLocked(e.announcement, AudioOutcome.EXPIRED)
            }
        }
    }

    private fun isExpired(e: Entry, now: Long): Boolean {
        val a = e.announcement
        val ttl = if (a.priority == AnnouncementPriority.CRITICAL) maxOf(a.ttlMs, config.criticalMinTtlMs) else a.ttlMs
        return now - e.originMs > ttl
    }

    private fun clearAllLocked() {
        val current = inFlight
        if (current != null) {
            inFlight = null
            inFlightUtteranceId = null
            _speaking.value = null
            logLocked(current.announcement, AudioOutcome.INTERRUPTED, "stopped")
            effects.addLast { speaker.stop() }
        }
        for (e in queue) logLocked(e.announcement, AudioOutcome.CLEARED)
        queue.clear()
        for (e in deferred) logLocked(e.announcement, AudioOutcome.CLEARED)
        deferred.clear()
    }

    private fun logLocked(a: Announcement, outcome: AudioOutcome, detail: String? = null) {
        val entry = AudioLogEntry(time.elapsedMs(), a, outcome, detail)
        val list = _history.value
        _history.value = if (list.size >= config.historySize) list.drop(list.size - config.historySize + 1) + entry else list + entry
        effects.addLast { logSink(entry) }
    }

    private companion object {
        const val REPEAT_KEY_PREFIX = "repeat:"
    }
}
