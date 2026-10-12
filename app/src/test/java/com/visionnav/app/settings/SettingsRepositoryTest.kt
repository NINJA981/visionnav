package com.visionnav.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.visionnav.core.announce.Verbosity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val jobs = mutableListOf<Job>()

    private val file: File by lazy { File(tmp.root, "settings_test.preferences_pb") }

    /**
     * Each store gets its own scope so it can be closed before re-opening the same file.
     * Uses OkioStorage: the default java.io FileStorage commits with File.renameTo, which cannot
     * replace an existing file on a Windows host JVM (irrelevant on Android devices).
     */
    private fun openStore(): Pair<DataStore<Preferences>, Job> {
        val job = Job()
        jobs += job
        val store = PreferenceDataStoreFactory.create(
            storage = OkioStorage(
                fileSystem = FileSystem.SYSTEM,
                serializer = PreferencesSerializer,
                producePath = { file.absolutePath.toPath() },
            ),
            scope = CoroutineScope(Dispatchers.IO + job),
        )
        return store to job
    }

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
    }

    @Test
    fun emptyStore_yieldsDefaults() = runTest {
        val (store, _) = openStore()
        val repo = SettingsRepository(store)
        assertThat(repo.current()).isEqualTo(AppSettings())
        assertThat(repo.current().units).isEqualTo(DistanceUnits.METRIC)
        assertThat(repo.current().themeMode).isEqualTo(ThemeMode.LIGHT)
        assertThat(repo.current().onboardingComplete).isFalse()
    }

    @Test
    fun allFields_roundTripThroughDisk() = runTest {
        val custom = AppSettings(
            onboardingComplete = true,
            verbosity = Verbosity.DETAILED,
            speechRate = 1.4f,
            speechPitch = 0.8f,
            directionStyle = DirectionStyle.CLOCK_FACE,
            hapticsEnabled = true,
            hapticsOnly = true,
            autoTorchInLowLight = true,
            units = DistanceUnits.IMPERIAL,
            themeMode = ThemeMode.LIGHT,
            emergencyContacts = listOf(
                EmergencyContact("Priya", "+91 98400 12345"),
                EmergencyContact("Arun \"Anna\" K", "044-2345-6789"),
            ),
            debugOverlay = true,
            experimentalFeatures = true,
        )
        val (first, firstJob) = openStore()
        SettingsRepository(first).update { custom }
        assertThat(SettingsRepository(first).current()).isEqualTo(custom)
        firstJob.cancelAndJoin()

        // A brand-new DataStore instance reading the same file sees identical settings.
        val (second, _) = openStore()
        assertThat(SettingsRepository(second).current()).isEqualTo(custom)
    }

    @Test
    fun individualSetters_persist() = runTest {
        val (store, _) = openStore()
        val repo = SettingsRepository(store)
        repo.setVerbosity(Verbosity.MINIMAL)
        repo.setDirectionStyle(DirectionStyle.CLOCK_FACE)
        repo.setHapticsOnly(true)
        repo.setAutoTorch(true)
        repo.setDebugOverlay(true)
        repo.setOnboardingComplete(true)
        val s = repo.current()
        assertThat(s.verbosity).isEqualTo(Verbosity.MINIMAL)
        assertThat(s.directionStyle).isEqualTo(DirectionStyle.CLOCK_FACE)
        assertThat(s.hapticsOnly).isTrue()
        assertThat(s.autoTorchInLowLight).isTrue()
        assertThat(s.debugOverlay).isTrue()
        assertThat(s.onboardingComplete).isTrue()
    }

    @Test
    fun speechValues_areClampedAndSnapped() = runTest {
        val (store, _) = openStore()
        val repo = SettingsRepository(store)
        repo.setSpeechRate(9f)
        repo.setSpeechPitch(0.01f)
        assertThat(repo.current().speechRate).isEqualTo(AppSettings.SPEECH_MAX)
        assertThat(repo.current().speechPitch).isEqualTo(AppSettings.SPEECH_MIN)
        repo.setSpeechRate(1.23f)
        assertThat(repo.current().speechRate).isEqualTo(1.2f)
        assertThat(SettingsRepository.clampSpeech(Float.NaN)).isEqualTo(1.0f)
    }

    @Test
    fun emergencyContacts_addAndRemove() = runTest {
        val (store, _) = openStore()
        val repo = SettingsRepository(store)
        val a = EmergencyContact("Priya", "+91 98400 12345")
        val b = EmergencyContact("Meena", "98401 55555")
        repo.addEmergencyContact(a)
        repo.addEmergencyContact(b)
        assertThat(repo.current().emergencyContacts).containsExactly(a, b).inOrder()
        repo.removeEmergencyContact(a)
        assertThat(repo.current().emergencyContacts).containsExactly(b)
    }

    @Test
    fun unknownOrCorruptValues_fallBackToDefaults() = runTest {
        val (store, _) = openStore()
        store.edit {
            it[stringPreferencesKey("verbosity")] = "SHOUTY"
            it[stringPreferencesKey("theme_mode")] = ""
            it[stringPreferencesKey("emergency_contacts_json")] = "{not json"
        }
        val s = SettingsRepository(store).current()
        assertThat(s.verbosity).isEqualTo(Verbosity.STANDARD)
        assertThat(s.themeMode).isEqualTo(ThemeMode.LIGHT)
        assertThat(s.emergencyContacts).isEmpty()
    }
}
