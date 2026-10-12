package com.visionnav.core.announce

/**
 * Audio priority. Higher [rank] preempts lower in-flight speech. Ordering is a safety
 * requirement: CRITICAL must always be able to interrupt everything else.
 */
enum class AnnouncementPriority(val rank: Int) {
    INFO(0),
    OBSERVATION(1),
    NAVIGATION(2),
    WARNING(3),
    CRITICAL(4),
}

enum class Earcon { NONE, CRITICAL_ALERT, WARNING, TURN, ARRIVED, OBSERVATION_START, OBSERVATION_END, CONFIRM, ERROR }

enum class HapticPattern { NONE, CRITICAL, WARNING, TURN_LEFT, TURN_RIGHT, ARRIVED, TICK }

/**
 * One utterance request.
 * @param dedupeKey announcements with the same key inside [AnnouncementPolicy] cooldown are dropped.
 * @param ttlMs queued items older than this are discarded instead of being spoken late.
 */
data class Announcement(
    val text: String,
    val priority: AnnouncementPriority,
    val dedupeKey: String,
    val ttlMs: Long = 4_000,
    val earcon: Earcon = Earcon.NONE,
    val haptic: HapticPattern = HapticPattern.NONE,
    /** If true, a CRITICAL/WARNING with the same key may repeat when it escalates. */
    val escalation: Boolean = false,
    val createdAtMs: Long = 0,
)

/** Low-level speech output. Implemented over Android TTS in :platform; fake in tests. */
interface Speaker {
    /** Speak [text]; invoke [onDone] when finished or interrupted. Must not block. */
    fun speak(utteranceId: String, text: String, onDone: (completed: Boolean) -> Unit)
    fun stop()
    val isReady: Boolean
}

interface EarconPlayer { fun play(earcon: Earcon) }

interface HapticPlayer { fun play(pattern: HapticPattern) }

enum class Verbosity { MINIMAL, STANDARD, DETAILED }
