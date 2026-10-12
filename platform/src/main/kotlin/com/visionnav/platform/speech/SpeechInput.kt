package com.visionnav.platform.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Outcome of one [SpeechInput.listenOnce] call. */
sealed interface SpeechResult {
    /**
     * Recognised speech, best hypothesis first (N-best, up to [SpeechInput.MAX_RESULTS]).
     * @param confidences per-alternative confidence in [0, 1] when the recogniser supplies them
     *   (same order as [alternatives]); null otherwise.
     */
    data class Success(val alternatives: List<String>, val confidences: List<Float>?) : SpeechResult

    /** Heard nothing intelligible, or nothing at all before the recogniser's silence timeout. */
    data object NoMatch : SpeechResult

    /** `RECORD_AUDIO` not granted (checked before starting and reported by the recogniser). */
    data object PermissionDenied : SpeechResult

    /** No speech recogniser on this device, or English recognition unavailable. */
    data object Unavailable : SpeechResult

    /** Any other failure. [code] is a `SpeechRecognizer.ERROR_*` value or [SpeechInput.ERROR_TIMEOUT]. */
    data class Error(val code: Int) : SpeechResult
}

/**
 * One-shot English voice-command capture over Android [SpeechRecognizer].
 *
 * - Recogniser choice: on API 31+ the **on-device** recogniser when
 *   `SpeechRecognizer.isOnDeviceRecognitionAvailable` (audio never leaves the phone); otherwise
 *   the default recogniser with `EXTRA_PREFER_OFFLINE`. If the on-device recogniser reports the
 *   language unavailable, the call transparently retries once with the default recogniser.
 * - Request: `en-US`, free-form model, partial results off, up to [MAX_RESULTS] alternatives.
 * - Threading: `SpeechRecognizer` must be created and driven on the main thread; [listenOnce]
 *   switches to `Dispatchers.Main` itself, so it may be called from any coroutine.
 * - Cancellation: cancelling the calling coroutine cancels and destroys the recogniser.
 * - Concurrency: calls are serialised (a second call waits for the first to finish); call
 *   [cancel] first if a new request should replace the current one.
 * - This class plays no sound itself (some recogniser implementations play their own start beep).
 */
@Singleton
class SpeechInput @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    // Main-thread only.
    private var active: SpeechRecognizer? = null
    private var finishActive: ((SpeechResult) -> Unit)? = null

    /** True if any speech recogniser is installed. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Listens for one utterance and returns the recognition result. */
    suspend fun listenOnce(): SpeechResult = mutex.withLock {
        withContext(Dispatchers.Main) {
            if (!hasPermission()) return@withContext SpeechResult.PermissionDenied
            val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
                return@withContext SpeechResult.Unavailable
            }
            val first = withTimeoutOrNull(OVERALL_TIMEOUT_MS) { listenWith(onDevice) }
                ?: return@withContext SpeechResult.Error(ERROR_TIMEOUT)
            val retry = onDevice &&
                first is SpeechResult.Error &&
                first.code in LANGUAGE_ERRORS &&
                SpeechRecognizer.isRecognitionAvailable(context)
            if (!retry) return@withContext first
            withTimeoutOrNull(OVERALL_TIMEOUT_MS) { listenWith(onDevice = false) }
                ?: SpeechResult.Error(ERROR_TIMEOUT)
        }.let(::mapLanguageUnavailable)
    }

    /** Cancels the recognition in progress (the pending [listenOnce] returns [SpeechResult.NoMatch]). */
    suspend fun cancel() = withContext(Dispatchers.Main) {
        // cancel() suppresses further recogniser callbacks, so resolve the caller ourselves.
        finishActive?.invoke(SpeechResult.NoMatch)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun listenWith(onDevice: Boolean): SpeechResult =
        suspendCancellableCoroutine { cont ->
            val recognizer = try {
                if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } catch (_: RuntimeException) {
                cont.resume(SpeechResult.Unavailable)
                return@suspendCancellableCoroutine
            }
            active = recognizer

            fun finish(result: SpeechResult) {
                if (!cont.isActive) return
                if (active === recognizer) {
                    active = null
                    finishActive = null
                }
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
                cont.resume(result)
            }
            finishActive = ::finish

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val texts = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                    if (texts.isEmpty()) {
                        finish(SpeechResult.NoMatch)
                    } else {
                        val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                            ?.takeIf { it.size == texts.size }
                            ?.toList()
                        finish(SpeechResult.Success(texts.take(MAX_RESULTS), scores?.take(MAX_RESULTS)))
                    }
                }

                override fun onError(error: Int) = finish(mapError(error))
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            cont.invokeOnCancellation {
                // Recogniser calls must happen on the main thread.
                ContextCompat.getMainExecutor(context).execute {
                    if (active === recognizer) {
                        active = null
                        finishActive = null
                    }
                    runCatching { recognizer.cancel() }
                    runCatching { recognizer.destroy() }
                }
            }

            try {
                recognizer.startListening(buildIntent(preferOffline = !onDevice))
            } catch (_: RuntimeException) {
                finish(SpeechResult.Error(SpeechRecognizer.ERROR_CLIENT))
            }
        }

    private fun buildIntent(preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE_TAG)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    companion object {
        /** Maximum number of alternatives requested and returned. */
        const val MAX_RESULTS = 5

        /** [SpeechResult.Error.code] when the recogniser never answered within the safety timeout. */
        const val ERROR_TIMEOUT = -1

        private const val LANGUAGE_TAG = "en-US"
        private const val OVERALL_TIMEOUT_MS = 20_000L

        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED (12) / ERROR_LANGUAGE_UNAVAILABLE (13), API 31.
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
        private val LANGUAGE_ERRORS = setOf(ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE)

        /** Maps a `SpeechRecognizer.ERROR_*` code to a [SpeechResult]. Internal for tests. */
        internal fun mapError(error: Int): SpeechResult = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> SpeechResult.NoMatch
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechResult.PermissionDenied
            else -> SpeechResult.Error(error)
        }

        private fun mapLanguageUnavailable(result: SpeechResult): SpeechResult =
            if (result is SpeechResult.Error && result.code in LANGUAGE_ERRORS) {
                SpeechResult.Unavailable
            } else {
                result
            }
    }
}
