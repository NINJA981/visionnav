package com.visionnav.core.announce

import com.visionnav.core.common.TimeSource

/**
 * Tunables for [AnnouncementPolicy].
 *
 * Rationale for the defaults: a blind pedestrian relies on hearing real traffic. Every second
 * of synthetic speech masks engine noise and tyre sound, so routine speech (OBSERVATION/INFO) is
 * aggressively de-duplicated and globally rate-limited, while safety speech (CRITICAL/WARNING)
 * only gets a short anti-stutter cooldown so a persisting hazard is re-announced quickly.
 *
 * @param sameKeyCooldownMs minimum gap between two accepted announcements that share a
 *   `dedupeKey`, by priority. Distinct keys never cool each other down (except via the global
 *   ambient limit below), so e.g. two different NAVIGATION instructions are both accepted.
 * @param ambientMinIntervalMs global minimum gap between *any* two accepted OBSERVATION/INFO
 *   announcements, regardless of key ("≤ 1 per 2.5 s").
 * @param importantKeyPrefixes OBSERVATION announcements whose `dedupeKey` starts with one of these
 *   prefixes survive [Verbosity.MINIMAL]. See [AnnouncementKeys.important].
 * @param importantKeys exact `dedupeKey`s that survive [Verbosity.MINIMAL].
 */
data class AnnouncementPolicyConfig(
    val sameKeyCooldownMs: Map<AnnouncementPriority, Long> = mapOf(
        AnnouncementPriority.CRITICAL to 2_000L,
        AnnouncementPriority.WARNING to 3_000L,
        AnnouncementPriority.NAVIGATION to 8_000L,
        AnnouncementPriority.OBSERVATION to 4_000L,
        AnnouncementPriority.INFO to 6_000L,
    ),
    val ambientMinIntervalMs: Long = 2_500L,
    val importantKeyPrefixes: Set<String> = setOf(AnnouncementKeys.IMPORTANT_PREFIX),
    val importantKeys: Set<String> = emptySet(),
)

/** Helpers for building `dedupeKey`s that carry policy meaning. */
object AnnouncementKeys {
    /** Prefix marking an OBSERVATION as important enough to be spoken in [Verbosity.MINIMAL]. */
    const val IMPORTANT_PREFIX: String = "important:"

    /** Marks [key] as important, e.g. `important("traffic:approaching:12")`. */
    fun important(key: String): String = if (key.startsWith(IMPORTANT_PREFIX)) key else IMPORTANT_PREFIX + key
}

/**
 * Admission control for announcements: de-duplication, cooldown, global ambient rate limit and
 * verbosity filtering. Pure logic over an injected [TimeSource]; it never speaks anything.
 *
 * Rules, in order:
 * 1. **Verbosity.** [Verbosity.MINIMAL] drops INFO and every OBSERVATION not flagged important
 *    (key prefix / key set, see [AnnouncementPolicyConfig]). STANDARD and DETAILED drop nothing.
 *    CRITICAL, WARNING and NAVIGATION are never filtered by verbosity: they carry safety or
 *    wayfinding meaning the user cannot opt out of without stopping navigation altogether.
 * 2. **Escalation.** An announcement with `escalation = true` whose priority is *higher* than the
 *    last accepted one with the same key bypasses both the cooldown and the ambient limit: a
 *    hazard that got worse must never be suppressed because a milder version was just spoken.
 * 3. **Same-key cooldown** per priority (CRITICAL 2 s, WARNING 3 s, NAVIGATION 8 s,
 *    OBSERVATION 4 s, INFO 6 s by default).
 * 4. **Global ambient limit**: at most one OBSERVATION/INFO per 2.5 s across all keys, so routine
 *    descriptions never become a continuous stream that masks traffic sounds.
 *
 * Accepting an announcement ([admit]) records it for future decisions; [evaluate] only peeks.
 * Thread-safe.
 */
class AnnouncementPolicy(
    private val time: TimeSource,
    private val config: AnnouncementPolicyConfig = AnnouncementPolicyConfig(),
    initialVerbosity: Verbosity = Verbosity.STANDARD,
) {
    sealed interface Decision {
        data object Accept : Decision
        data class Drop(val reason: DropReason) : Decision
    }

    enum class DropReason { VERBOSITY, COOLDOWN, AMBIENT_RATE_LIMIT }

    private data class KeyRecord(val atMs: Long, val priority: AnnouncementPriority)

    private val lock = Any()
    private val lastByKey = HashMap<String, KeyRecord>()
    private var lastAmbientMs: Long? = null

    @Volatile
    var verbosity: Verbosity = initialVerbosity

    /** Decides without recording anything. */
    fun evaluate(a: Announcement): Decision = synchronized(lock) { decide(a, time.elapsedMs()) }

    /** Decides and, if accepted, records the announcement for later cooldown decisions. */
    fun admit(a: Announcement): Decision = synchronized(lock) {
        val now = time.elapsedMs()
        val d = decide(a, now)
        if (d == Decision.Accept) record(a, now)
        d
    }

    /** Forgets all history (e.g. when a navigation session ends). */
    fun reset() = synchronized(lock) {
        lastByKey.clear()
        lastAmbientMs = null
    }

    /** Earliest time at which an OBSERVATION/INFO announcement can pass the global ambient limit. */
    fun ambientAvailableAtMs(): Long = synchronized(lock) {
        lastAmbientMs?.let { it + config.ambientMinIntervalMs } ?: Long.MIN_VALUE
    }

    fun isImportant(a: Announcement): Boolean =
        a.dedupeKey in config.importantKeys || config.importantKeyPrefixes.any { a.dedupeKey.startsWith(it) }

    private fun decide(a: Announcement, now: Long): Decision {
        if (!passesVerbosity(a)) return Decision.Drop(DropReason.VERBOSITY)
        val last = lastByKey[a.dedupeKey]
        val escalates = a.escalation && last != null && a.priority.rank > last.priority.rank
        if (escalates) return Decision.Accept
        if (last != null) {
            val cooldown = config.sameKeyCooldownMs[a.priority] ?: 0L
            if (now - last.atMs < cooldown) return Decision.Drop(DropReason.COOLDOWN)
        }
        if (a.priority.isAmbient()) {
            val lastAmbient = lastAmbientMs
            if (lastAmbient != null && now - lastAmbient < config.ambientMinIntervalMs) {
                return Decision.Drop(DropReason.AMBIENT_RATE_LIMIT)
            }
        }
        return Decision.Accept
    }

    private fun passesVerbosity(a: Announcement): Boolean = when (verbosity) {
        Verbosity.MINIMAL -> when (a.priority) {
            AnnouncementPriority.INFO -> false
            AnnouncementPriority.OBSERVATION -> isImportant(a)
            else -> true
        }
        Verbosity.STANDARD, Verbosity.DETAILED -> true
    }

    private fun record(a: Announcement, now: Long) {
        lastByKey[a.dedupeKey] = KeyRecord(now, a.priority)
        if (a.priority.isAmbient()) lastAmbientMs = now
        if (lastByKey.size > MAX_KEYS) {
            // Bound memory on long sessions: forget keys whose longest cooldown has long passed.
            val horizon = now - (config.sameKeyCooldownMs.values.maxOrNull() ?: 0L)
            lastByKey.entries.removeAll { it.value.atMs < horizon }
        }
    }

    private fun AnnouncementPriority.isAmbient() =
        this == AnnouncementPriority.OBSERVATION || this == AnnouncementPriority.INFO

    private companion object {
        const val MAX_KEYS = 512
    }
}
