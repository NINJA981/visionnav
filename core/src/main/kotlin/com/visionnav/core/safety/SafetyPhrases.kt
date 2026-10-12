package com.visionnav.core.safety

import com.visionnav.core.announce.Announcement
import com.visionnav.core.announce.AnnouncementPriority
import com.visionnav.core.announce.Earcon
import com.visionnav.core.announce.HapticPattern
import com.visionnav.core.tracking.ApproachState

/**
 * Converts [SafetyEvent]s into short [Announcement]s.
 *
 * - CRITICAL → CRITICAL priority, [Earcon.CRITICAL_ALERT], [HapticPattern.CRITICAL], text starts
 *   with "Stop." (an instruction to pause, never to proceed).
 * - WARNING → WARNING priority with warning earcon/haptic.
 * - CAUTION → OBSERVATION priority, no earcon/haptic.
 * - INFO → no announcement.
 *
 * Wording is relative ("very close", "close", "ahead, slightly left") — never metres or timings —
 * and never states that anything is clear or safe. `dedupeKey = "safety:<trackId>:<severity>"`.
 */
object SafetyPhrases {

    /** Spoken text for [event], e.g. "Stop. Car very close, ahead." */
    fun text(event: SafetyEvent): String {
        val subject = SpokenWords.capitalize(SpokenWords.singular(event.objectClass))
        val motion = when (event.approach) {
            ApproachState.APPROACHING -> "approaching"
            ApproachState.MOVING_AWAY -> "moving away"
            ApproachState.CROSSING -> "passing"
            ApproachState.STATIONARY, ApproachState.UNKNOWN -> null
        }
        val proximity = when (event.proximity) {
            ProximityBand.VERY_NEAR -> "very close"
            ProximityBand.NEAR -> "close"
            else -> null
        }
        val direction = SpokenWords.direction(event.sector, event.inWalkingPath)
        val body = when {
            motion != null && proximity != null -> "$subject $motion, $proximity, $direction"
            motion != null -> "$subject $motion, $direction"
            proximity != null -> "$subject $proximity, $direction"
            else -> "$subject $direction"
        }
        val prefix = if (event.severity == SafetySeverity.CRITICAL) "Stop. " else ""
        return "$prefix$body."
    }

    /**
     * @param previousSeverity last severity announced for this track (null if none). When the new
     *   severity is higher the announcement is marked as an escalation so cooldowns don't swallow it.
     * @return null for INFO events.
     */
    fun toAnnouncement(event: SafetyEvent, previousSeverity: SafetySeverity? = null): Announcement? {
        val (priority, earcon, haptic, ttl) = when (event.severity) {
            SafetySeverity.INFO -> return null
            SafetySeverity.CAUTION -> Style(AnnouncementPriority.OBSERVATION, Earcon.NONE, HapticPattern.NONE, 3_000L)
            SafetySeverity.WARNING -> Style(AnnouncementPriority.WARNING, Earcon.WARNING, HapticPattern.WARNING, 2_500L)
            SafetySeverity.CRITICAL -> Style(AnnouncementPriority.CRITICAL, Earcon.CRITICAL_ALERT, HapticPattern.CRITICAL, 1_500L)
        }
        return Announcement(
            text = text(event),
            priority = priority,
            dedupeKey = dedupeKey(event.trackId, event.severity),
            ttlMs = ttl,
            earcon = earcon,
            haptic = haptic,
            escalation = previousSeverity != null && event.severity > previousSeverity,
            createdAtMs = event.elapsedMs,
        )
    }

    fun dedupeKey(trackId: Long, severity: SafetySeverity): String = "safety:$trackId:$severity"

    private data class Style(val priority: AnnouncementPriority, val earcon: Earcon, val haptic: HapticPattern, val ttlMs: Long)
}

/**
 * Stateful wrapper around [SafetyPhrases] that turns the per-frame event stream into a sparse
 * announcement stream: a track is announced when it first reaches CAUTION or above, again when its
 * severity escalates (marked `escalation = true`), and repeated every [repeatAfterMs] while it stays
 * at WARNING or above. De-escalation is silent (no "all clear"-style messages, by design).
 *
 * Downstream `AnnouncementPolicy` still applies its own dedupe/cooldown; this just avoids flooding it.
 */
class SafetyAnnouncer(private val repeatAfterMs: Long = 5_000) {

    private class Last(var severity: SafetySeverity, var announcedAtMs: Long)

    private val last = HashMap<Long, Last>()

    fun announcementsFor(events: List<SafetyEvent>): List<Announcement> {
        val ids = events.mapTo(HashSet()) { it.trackId }
        last.keys.retainAll(ids)
        val out = ArrayList<Announcement>()
        for (e in events) {
            val prev = last[e.trackId]
            if (e.severity < SafetySeverity.CAUTION) {
                // Remember the lower level so a later rise counts as an escalation.
                if (prev == null) last[e.trackId] = Last(e.severity, Long.MIN_VALUE) else prev.severity = e.severity
                continue
            }
            val shouldSpeak = when {
                prev == null || prev.announcedAtMs == Long.MIN_VALUE -> true
                e.severity > prev.severity -> true
                e.severity >= SafetySeverity.WARNING && e.elapsedMs - prev.announcedAtMs >= repeatAfterMs -> true
                else -> false
            }
            if (shouldSpeak) {
                SafetyPhrases.toAnnouncement(e, prev?.severity)?.let { out += it }
                last[e.trackId] = Last(e.severity, e.elapsedMs)
            } else {
                prev?.severity = e.severity
            }
        }
        return out
    }

    fun reset() = last.clear()
}
