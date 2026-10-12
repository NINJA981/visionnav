package com.visionnav.platform.audio

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.visionnav.core.announce.Earcon
import com.visionnav.core.announce.EarconPlayer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EarconPlayer] using [ToneGenerator] with the patterns in [EarconPatterns].
 *
 * - **Stream**: `AudioManager.STREAM_MUSIC`, the stream `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`
 *   (our TTS) maps to, so earcons and speech share one volume control the user already knows.
 *   (`STREAM_ACCESSIBILITY` only has its own volume while an accessibility service is active,
 *   which would make earcon loudness depend on TalkBack state.)
 * - **Non-blocking**: [play] returns immediately; sequences run on a private background thread.
 * - **Preemption**: a new earcon cancels the sequence in progress. A non-critical earcon never
 *   cuts off a [Earcon.CRITICAL_ALERT] that is still sounding.
 * - Failures to obtain audio resources are swallowed (the earcon is skipped): an earcon is a cue,
 *   never the only carrier of safety information (speech + haptics accompany it).
 */
@Singleton
class ToneEarconPlayer @Inject constructor() : EarconPlayer {

    private val thread = HandlerThread("visionnav-earcons").also { it.start() }
    private val handler = Handler(thread.looper)

    // Only accessed on [handler]'s thread.
    private val generators = HashMap<Int, ToneGenerator>()
    private var playingCriticalUntilMs = 0L

    override fun play(earcon: Earcon) {
        if (earcon == Earcon.NONE) return
        handler.post { startSequence(earcon) }
    }

    /** Silences any earcon in progress. */
    fun stop() {
        handler.post {
            handler.removeCallbacksAndMessages(SEQUENCE_TOKEN)
            generators.values.forEach { runCatching { it.stopTone() } }
            playingCriticalUntilMs = 0L
        }
    }

    /** Releases native tone generators (call from Application teardown / tests). */
    fun release() {
        handler.removeCallbacksAndMessages(null)
        handler.post {
            generators.values.forEach { runCatching { it.release() } }
            generators.clear()
            thread.quitSafely()
        }
    }

    private fun startSequence(earcon: Earcon) {
        val now = SystemClock.uptimeMillis()
        if (earcon != Earcon.CRITICAL_ALERT && now < playingCriticalUntilMs) return
        handler.removeCallbacksAndMessages(SEQUENCE_TOKEN)
        generators.values.forEach { runCatching { it.stopTone() } }
        val generator = generatorFor(EarconPatterns.volumeFor(earcon)) ?: return
        playingCriticalUntilMs =
            if (earcon == Earcon.CRITICAL_ALERT) now + EarconPatterns.totalDurationMs(earcon) else 0L

        var offsetMs = 0L
        for (step in EarconPatterns.forEarcon(earcon)) {
            handler.postAtTime(
                { runCatching { generator.startTone(step.toneType, step.durationMs) } },
                SEQUENCE_TOKEN,
                now + offsetMs,
            )
            offsetMs += step.durationMs + step.gapAfterMs
        }
    }

    private fun generatorFor(volume: Int): ToneGenerator? =
        generators[volume] ?: try {
            ToneGenerator(AudioManager.STREAM_MUSIC, volume).also { generators[volume] = it }
        } catch (_: RuntimeException) {
            null // Audio resources exhausted / unavailable; skip this earcon.
        }

    private companion object {
        val SEQUENCE_TOKEN = Any()
    }
}
