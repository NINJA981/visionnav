package com.visionnav.platform.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import com.visionnav.core.announce.HapticPattern
import com.visionnav.core.announce.HapticPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [HapticPlayer] over the system [Vibrator] (via [VibratorManager] on API 31+), playing the
 * waveforms in [HapticPatterns].
 *
 * - Does nothing if the device has no vibrator ([hasVibrator] = false).
 * - Uses per-segment amplitudes when the hardware supports amplitude control; otherwise the same
 *   rhythm at default strength (patterns are designed to be distinct by rhythm alone).
 * - Usage attributes: CRITICAL uses alarm usage (`VibrationAttributes.USAGE_ALARM` on API 33+,
 *   `AudioAttributes.USAGE_ALARM` before), so it is not muted like touch feedback; all other
 *   patterns use accessibility usage. Every call replaces the vibration in progress.
 * - Requires the normal-level `VIBRATE` permission (declared in the module manifest).
 */
@Singleton
class VibratorHapticPlayer @Inject constructor(
    @ApplicationContext context: Context,
) : HapticPlayer {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** Whether haptics can be felt on this device. */
    val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    override fun play(pattern: HapticPattern) {
        val waveform = HapticPatterns.forPattern(pattern) ?: return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (v.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(waveform.timingsMs, waveform.amplitudes, NO_REPEAT)
        } else {
            VibrationEffect.createWaveform(waveform.timingsMs, NO_REPEAT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, vibrationAttributes(waveform.critical))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, audioAttributes(waveform.critical))
            }
        } catch (_: RuntimeException) {
            // Vibrator service failure must never crash the safety pipeline.
        }
    }

    /** Cancels any vibration in progress. */
    fun cancel() {
        vibrator?.cancel()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun vibrationAttributes(critical: Boolean): VibrationAttributes =
        VibrationAttributes.createForUsage(
            if (critical) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_ACCESSIBILITY,
        )

    private fun audioAttributes(critical: Boolean): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(
                if (critical) {
                    AudioAttributes.USAGE_ALARM
                } else {
                    AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private companion object {
        const val NO_REPEAT = -1
    }
}
