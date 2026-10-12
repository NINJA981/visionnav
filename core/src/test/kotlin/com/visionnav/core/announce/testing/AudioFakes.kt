package com.visionnav.core.announce.testing

import com.visionnav.core.announce.Earcon
import com.visionnav.core.announce.EarconPlayer
import com.visionnav.core.announce.HapticPattern
import com.visionnav.core.announce.HapticPlayer
import com.visionnav.core.announce.Speaker

/**
 * Deterministic [Speaker]. Speech "lasts" until the test calls [finish]. [stop] reports the
 * current utterance as interrupted (completed = false), like Android TTS does.
 */
class FakeSpeaker(var ready: Boolean = true) : Speaker {
    data class Utterance(val id: String, val text: String)

    private val lock = Any()
    private var current: Pair<Utterance, (Boolean) -> Unit>? = null

    /** Every speak() call, in order. */
    val spoken = mutableListOf<Utterance>()
    val spokenTexts: List<String> get() = synchronized(lock) { spoken.map { it.text } }
    var stopCount = 0
        private set

    override val isReady: Boolean get() = ready

    val currentText: String? get() = synchronized(lock) { current?.first?.text }

    override fun speak(utteranceId: String, text: String, onDone: (completed: Boolean) -> Unit) {
        synchronized(lock) {
            val u = Utterance(utteranceId, text)
            spoken += u
            current = u to onDone
        }
    }

    override fun stop() {
        val c = synchronized(lock) {
            stopCount++
            current.also { current = null }
        }
        c?.second?.invoke(false)
    }

    /** Completes the current utterance normally. */
    fun finish() {
        val c = synchronized(lock) { current.also { current = null } } ?: error("nothing is being spoken")
        c.second(true)
    }

    /** Completes utterances until the speaker is idle. */
    fun finishAll(max: Int = 100) {
        var n = 0
        while (currentText != null && n++ < max) finish()
    }
}

class FakeEarconPlayer : EarconPlayer {
    val played = mutableListOf<Earcon>()
    override fun play(earcon: Earcon) {
        synchronized(played) { played += earcon }
    }
}

class FakeHapticPlayer : HapticPlayer {
    val played = mutableListOf<HapticPattern>()
    override fun play(pattern: HapticPattern) {
        synchronized(played) { played += pattern }
    }
}
