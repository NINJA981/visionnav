package com.visionnav.app.settings

import com.visionnav.core.announce.Verbosity
import kotlinx.serialization.Serializable

/** How turns and object positions are described. */
enum class DirectionStyle { LEFT_RIGHT, CLOCK_FACE }

/** Distance units used in spoken and displayed text. Metric is the default. */
enum class DistanceUnits { METRIC, IMPERIAL }

/** Colour scheme. Dark is the default because it suits most low-vision users. */
enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** A person who receives the SOS text message. Stored on the device only. */
@Serializable
data class EmergencyContact(val name: String, val phone: String)

/**
 * User preferences, persisted by [SettingsRepository]. Every field has a safe default so a
 * missing or corrupt preference never prevents the app from starting.
 */
data class AppSettings(
    val onboardingComplete: Boolean = false,
    val verbosity: Verbosity = Verbosity.STANDARD,
    /** TTS speech rate multiplier, 1.0 = normal. Clamped to [SPEECH_MIN]..[SPEECH_MAX]. */
    val speechRate: Float = 1.0f,
    /** TTS pitch multiplier, 1.0 = normal. Clamped to [SPEECH_MIN]..[SPEECH_MAX]. */
    val speechPitch: Float = 1.0f,
    val directionStyle: DirectionStyle = DirectionStyle.LEFT_RIGHT,
    val hapticsEnabled: Boolean = true,
    /** Speech muted; vibration patterns only. */
    val hapticsOnly: Boolean = false,
    val autoTorchInLowLight: Boolean = false,
    val units: DistanceUnits = DistanceUnits.METRIC,
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val emergencyContacts: List<EmergencyContact> = emptyList(),
    val debugOverlay: Boolean = false,
    val experimentalFeatures: Boolean = false,
) {
    companion object {
        const val SPEECH_MIN = 0.5f
        const val SPEECH_MAX = 2.0f
        const val SPEECH_STEP = 0.1f
    }
}
