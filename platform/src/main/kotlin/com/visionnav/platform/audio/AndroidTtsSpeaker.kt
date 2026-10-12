package com.visionnav.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.visionnav.core.announce.Speaker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Speaker] over Android [TextToSpeech].
 *
 * - **Language**: Locale.US, falling back to Locale.UK, then generic English
 *   (`isLanguageAvailable` checked first). If no English voice is installed [isReady] stays false.
 * - **Semantics**: QUEUE_FLUSH — every [speak] interrupts whatever is playing. Queuing, priority
 *   and preemption are owned by core's `AudioQueueManager`, not by this class.
 * - **Callbacks**: `onDone(true)` when an utterance finishes, `onDone(false)` when it is
 *   interrupted, stopped, fails, or cannot start (TTS not ready). Each utterance's callback fires
 *   **exactly once**, always on the **main thread**, never synchronously inside [speak].
 * - **Audio**: `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` / `CONTENT_TYPE_SPEECH`. Transient audio
 *   focus with ducking (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) is requested per utterance and
 *   abandoned shortly after the last utterance ends, so music/podcasts duck instead of pausing and
 *   recover between announcements.
 */
@Singleton
class AndroidTtsSpeaker @Inject constructor(
    @ApplicationContext private val context: Context,
) : Speaker {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pending = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val focusLock = Any()
    private var focusHeld = false

    private val _ready = MutableStateFlow(false)

    /** Observable readiness (TTS engine bound and an English voice selected). */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    override val isReady: Boolean get() = _ready.value

    /** The locale actually selected, or null until ready. */
    @Volatile
    var selectedLocale: Locale? = null
        private set

    @Volatile
    private var shutDown = false

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ /* We never pause; speech is short. */ }, mainHandler)
            .build()

    private val abandonFocusRunnable = Runnable { abandonFocusIfIdle() }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        onInit(status)
    }

    private fun onInit(status: Int) {
        if (shutDown) return
        if (status != TextToSpeech.SUCCESS) {
            _ready.value = false
            return
        }
        tts.setAudioAttributes(audioAttributes)
        tts.setOnUtteranceProgressListener(progressListener)
        val locale = CANDIDATE_LOCALES.firstOrNull { candidate ->
            tts.isLanguageAvailable(candidate) >= TextToSpeech.LANG_AVAILABLE &&
                tts.setLanguage(candidate) >= TextToSpeech.LANG_AVAILABLE
        }
        selectedLocale = locale
        _ready.value = locale != null
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) = complete(utteranceId, true)

        override fun onStop(utteranceId: String, interrupted: Boolean) = complete(utteranceId, false)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = complete(utteranceId, false)

        override fun onError(utteranceId: String, errorCode: Int) = complete(utteranceId, false)
    }

    override fun speak(utteranceId: String, text: String, onDone: (completed: Boolean) -> Unit) {
        if (!isReady || shutDown || text.isBlank()) {
            mainHandler.post { onDone(false) }
            return
        }
        // QUEUE_FLUSH will interrupt everything currently pending; resolve those callbacks now
        // (the engine's own onStop may follow; the map guarantees exactly-once delivery).
        for (id in pending.keys.toList()) complete(id, false)
        pending[utteranceId] = onDone
        requestFocus()
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) complete(utteranceId, false)
    }

    override fun stop() {
        if (!shutDown) tts.stop()
        for (id in pending.keys.toList()) complete(id, false)
    }

    /** Speech rate multiplier (1.0 = normal), clamped to [0.5, 3.0]. */
    fun setSpeechRate(rate: Float) {
        if (!shutDown) tts.setSpeechRate(rate.coerceIn(MIN_RATE, MAX_RATE))
    }

    /** Pitch multiplier (1.0 = normal), clamped to [0.5, 2.0]. */
    fun setPitch(pitch: Float) {
        if (!shutDown) tts.setPitch(pitch.coerceIn(MIN_PITCH, MAX_PITCH))
    }

    /** Releases the TTS engine. The instance is unusable afterwards ([isReady] = false). */
    fun shutdown() {
        if (shutDown) return
        stop()
        shutDown = true
        _ready.value = false
        tts.shutdown()
        mainHandler.removeCallbacks(abandonFocusRunnable)
        abandonFocusNow()
    }

    private fun complete(utteranceId: String, completed: Boolean) {
        val callback = pending.remove(utteranceId) ?: return // Guards against double callbacks.
        mainHandler.post { callback(completed) }
        scheduleFocusRelease()
    }

    private fun requestFocus() {
        mainHandler.removeCallbacks(abandonFocusRunnable)
        synchronized(focusLock) {
            if (focusHeld) return
            focusHeld = audioManager.requestAudioFocus(focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            // If focus is denied (e.g. during a phone call) we still speak: an accessibility
            // aid must not go silent; the system mixes or attenuates as policy dictates.
        }
    }

    private fun scheduleFocusRelease() {
        if (pending.isEmpty()) {
            mainHandler.removeCallbacks(abandonFocusRunnable)
            mainHandler.postDelayed(abandonFocusRunnable, FOCUS_RELEASE_DELAY_MS)
        }
    }

    private fun abandonFocusIfIdle() {
        if (pending.isEmpty()) abandonFocusNow()
    }

    private fun abandonFocusNow() {
        synchronized(focusLock) {
            if (!focusHeld) return
            audioManager.abandonAudioFocusRequest(focusRequest)
            focusHeld = false
        }
    }

    companion object {
        private val CANDIDATE_LOCALES = listOf(Locale.US, Locale.UK, Locale.ENGLISH)
        private const val MIN_RATE = 0.5f
        private const val MAX_RATE = 3.0f
        private const val MIN_PITCH = 0.5f
        private const val MAX_PITCH = 2.0f

        /** Keeps focus across back-to-back utterances so other audio doesn't pump up and down. */
        private const val FOCUS_RELEASE_DELAY_MS = 400L
    }
}
