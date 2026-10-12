package com.visionnav.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.visionnav.core.announce.Verbosity
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Qualifies the preferences DataStore that holds [AppSettings]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

/**
 * DataStore-backed user settings. Reading never throws: an I/O error yields defaults, and
 * unknown enum names (e.g. after a downgrade) fall back to the default value.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @SettingsDataStore private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    /** Atomically reads, transforms and writes the full settings object. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs.write(updated)
        }
    }

    suspend fun setOnboardingComplete(complete: Boolean) = update { it.copy(onboardingComplete = complete) }
    suspend fun setVerbosity(value: Verbosity) = update { it.copy(verbosity = value) }
    suspend fun setSpeechRate(value: Float) = update { it.copy(speechRate = clampSpeech(value)) }
    suspend fun setSpeechPitch(value: Float) = update { it.copy(speechPitch = clampSpeech(value)) }
    suspend fun setDirectionStyle(value: DirectionStyle) = update { it.copy(directionStyle = value) }
    suspend fun setHapticsEnabled(value: Boolean) = update { it.copy(hapticsEnabled = value) }
    suspend fun setHapticsOnly(value: Boolean) = update { it.copy(hapticsOnly = value) }
    suspend fun setAutoTorch(value: Boolean) = update { it.copy(autoTorchInLowLight = value) }
    suspend fun setUnits(value: DistanceUnits) = update { it.copy(units = value) }
    suspend fun setThemeMode(value: ThemeMode) = update { it.copy(themeMode = value) }
    suspend fun setDebugOverlay(value: Boolean) = update { it.copy(debugOverlay = value) }
    suspend fun setExperimentalFeatures(value: Boolean) = update { it.copy(experimentalFeatures = value) }
    suspend fun setEmergencyContacts(value: List<EmergencyContact>) = update { it.copy(emergencyContacts = value) }

    suspend fun addEmergencyContact(contact: EmergencyContact) =
        update { s -> s.copy(emergencyContacts = s.emergencyContacts + contact) }

    suspend fun removeEmergencyContact(contact: EmergencyContact) =
        update { s -> s.copy(emergencyContacts = s.emergencyContacts - contact) }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboardingComplete = this[Keys.ONBOARDING] ?: d.onboardingComplete,
            verbosity = enumOr(this[Keys.VERBOSITY], d.verbosity),
            speechRate = clampSpeech(this[Keys.SPEECH_RATE] ?: d.speechRate),
            speechPitch = clampSpeech(this[Keys.SPEECH_PITCH] ?: d.speechPitch),
            directionStyle = enumOr(this[Keys.DIRECTION_STYLE], d.directionStyle),
            hapticsEnabled = this[Keys.HAPTICS] ?: d.hapticsEnabled,
            hapticsOnly = this[Keys.HAPTICS_ONLY] ?: d.hapticsOnly,
            autoTorchInLowLight = this[Keys.AUTO_TORCH] ?: d.autoTorchInLowLight,
            units = enumOr(this[Keys.UNITS], d.units),
            themeMode = enumOr(this[Keys.THEME], d.themeMode),
            emergencyContacts = decodeContacts(this[Keys.CONTACTS]),
            debugOverlay = this[Keys.DEBUG_OVERLAY] ?: d.debugOverlay,
            experimentalFeatures = this[Keys.EXPERIMENTAL] ?: d.experimentalFeatures,
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.ONBOARDING] = s.onboardingComplete
        this[Keys.VERBOSITY] = s.verbosity.name
        this[Keys.SPEECH_RATE] = clampSpeech(s.speechRate)
        this[Keys.SPEECH_PITCH] = clampSpeech(s.speechPitch)
        this[Keys.DIRECTION_STYLE] = s.directionStyle.name
        this[Keys.HAPTICS] = s.hapticsEnabled
        this[Keys.HAPTICS_ONLY] = s.hapticsOnly
        this[Keys.AUTO_TORCH] = s.autoTorchInLowLight
        this[Keys.UNITS] = s.units.name
        this[Keys.THEME] = s.themeMode.name
        this[Keys.CONTACTS] = json.encodeToString(contactListSerializer, s.emergencyContacts)
        this[Keys.DEBUG_OVERLAY] = s.debugOverlay
        this[Keys.EXPERIMENTAL] = s.experimentalFeatures
    }

    private fun decodeContacts(raw: String?): List<EmergencyContact> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(contactListSerializer, raw) }.getOrDefault(emptyList())
    }

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val VERBOSITY = stringPreferencesKey("verbosity")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
        val DIRECTION_STYLE = stringPreferencesKey("direction_style")
        val HAPTICS = booleanPreferencesKey("haptics_enabled")
        val HAPTICS_ONLY = booleanPreferencesKey("haptics_only")
        val AUTO_TORCH = booleanPreferencesKey("auto_torch_low_light")
        val UNITS = stringPreferencesKey("units")
        val THEME = stringPreferencesKey("theme_mode")
        val CONTACTS = stringPreferencesKey("emergency_contacts_json")
        val DEBUG_OVERLAY = booleanPreferencesKey("debug_overlay")
        val EXPERIMENTAL = booleanPreferencesKey("experimental_features")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val contactListSerializer = ListSerializer(EmergencyContact.serializer())

        /** Clamps to the supported range and snaps to one decimal place. */
        fun clampSpeech(value: Float): Float {
            if (value.isNaN()) return 1.0f
            val clamped = value.coerceIn(AppSettings.SPEECH_MIN, AppSettings.SPEECH_MAX)
            return (clamped * 10f).roundToInt() / 10f
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
    }
}
