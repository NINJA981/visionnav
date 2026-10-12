package com.visionnav.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.visionnav.app.R
import com.visionnav.app.settings.AppSettings
import com.visionnav.app.settings.DirectionStyle
import com.visionnav.app.settings.DistanceUnits
import com.visionnav.app.settings.EmergencyContact
import com.visionnav.app.settings.SettingsRepository
import com.visionnav.app.settings.ThemeMode
import com.visionnav.app.ui.components.ActionStyle
import com.visionnav.app.ui.components.BigActionButton
import com.visionnav.app.ui.components.EmergencyContactsEditor
import com.visionnav.app.ui.components.MinTouchTarget
import com.visionnav.app.ui.components.RadioGroup
import com.visionnav.app.ui.components.RadioOption
import com.visionnav.app.ui.components.SectionHeading
import com.visionnav.app.ui.components.StepperRow
import com.visionnav.app.ui.components.ToggleRow
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.theme.VisionNavTheme
import com.visionnav.core.announce.Verbosity
import java.util.Locale

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onAddContact: (EmergencyContact) -> Unit,
    onRemoveContact: (EmergencyContact) -> Unit,
    onReplayOnboarding: () -> Unit,
    onBack: () -> Unit,
) {
    VnScreen(title = stringResource(R.string.settings_title), onBack = onBack) {
        SectionHeading(stringResource(R.string.settings_speech))
        SectionLabel(stringResource(R.string.settings_verbosity))
        RadioGroup(
            options = listOf(
                RadioOption(Verbosity.MINIMAL, stringResource(R.string.verbosity_minimal), stringResource(R.string.verbosity_minimal_desc)),
                RadioOption(Verbosity.STANDARD, stringResource(R.string.verbosity_standard), stringResource(R.string.verbosity_standard_desc)),
                RadioOption(Verbosity.DETAILED, stringResource(R.string.verbosity_detailed), stringResource(R.string.verbosity_detailed_desc)),
            ),
            selected = settings.verbosity,
            onSelect = { v -> onUpdate { it.copy(verbosity = v) } },
        )
        StepperRow(
            title = stringResource(R.string.settings_rate),
            valueText = stringResource(R.string.settings_value_times, format1(settings.speechRate)),
            onDecrease = { onUpdate { it.copy(speechRate = SettingsRepository.clampSpeech(it.speechRate - AppSettings.SPEECH_STEP)) } },
            onIncrease = { onUpdate { it.copy(speechRate = SettingsRepository.clampSpeech(it.speechRate + AppSettings.SPEECH_STEP)) } },
        )
        StepperRow(
            title = stringResource(R.string.settings_pitch),
            valueText = stringResource(R.string.settings_value_times, format1(settings.speechPitch)),
            onDecrease = { onUpdate { it.copy(speechPitch = SettingsRepository.clampSpeech(it.speechPitch - AppSettings.SPEECH_STEP)) } },
            onIncrease = { onUpdate { it.copy(speechPitch = SettingsRepository.clampSpeech(it.speechPitch + AppSettings.SPEECH_STEP)) } },
        )

        SectionHeading(stringResource(R.string.settings_directions))
        RadioGroup(
            options = listOf(
                RadioOption(DirectionStyle.LEFT_RIGHT, stringResource(R.string.direction_left_right), stringResource(R.string.direction_left_right_desc)),
                RadioOption(DirectionStyle.CLOCK_FACE, stringResource(R.string.direction_clock), stringResource(R.string.direction_clock_desc)),
            ),
            selected = settings.directionStyle,
            onSelect = { v -> onUpdate { it.copy(directionStyle = v) } },
        )

        SectionHeading(stringResource(R.string.settings_vibration))
        ToggleRow(
            title = stringResource(R.string.settings_haptics),
            description = stringResource(R.string.settings_haptics_desc),
            checked = settings.hapticsEnabled,
            onCheckedChange = { v -> onUpdate { it.copy(hapticsEnabled = v, hapticsOnly = if (!v) false else it.hapticsOnly) } },
        )
        ToggleRow(
            title = stringResource(R.string.settings_haptics_only),
            description = stringResource(R.string.settings_haptics_only_desc),
            checked = settings.hapticsOnly,
            enabled = settings.hapticsEnabled,
            onCheckedChange = { v -> onUpdate { it.copy(hapticsOnly = v) } },
        )

        SectionHeading(stringResource(R.string.settings_camera))
        ToggleRow(
            title = stringResource(R.string.settings_auto_torch),
            description = stringResource(R.string.settings_auto_torch_desc),
            checked = settings.autoTorchInLowLight,
            onCheckedChange = { v -> onUpdate { it.copy(autoTorchInLowLight = v) } },
        )

        SectionHeading(stringResource(R.string.settings_units))
        RadioGroup(
            options = listOf(
                RadioOption(DistanceUnits.METRIC, stringResource(R.string.units_metric), stringResource(R.string.units_metric_desc)),
                RadioOption(DistanceUnits.IMPERIAL, stringResource(R.string.units_imperial), stringResource(R.string.units_imperial_desc)),
            ),
            selected = settings.units,
            onSelect = { v -> onUpdate { it.copy(units = v) } },
        )

        SectionHeading(stringResource(R.string.settings_appearance))
        RadioGroup(
            options = listOf(
                RadioOption(ThemeMode.DARK, stringResource(R.string.theme_dark)),
                RadioOption(ThemeMode.LIGHT, stringResource(R.string.theme_light)),
                RadioOption(ThemeMode.SYSTEM, stringResource(R.string.theme_system)),
            ),
            selected = settings.themeMode,
            onSelect = { v -> onUpdate { it.copy(themeMode = v) } },
        )

        SectionHeading(stringResource(R.string.settings_contacts))
        EmergencyContactsEditor(settings.emergencyContacts, onAddContact, onRemoveContact)

        SectionHeading(stringResource(R.string.settings_advanced))
        ToggleRow(
            title = stringResource(R.string.settings_debug),
            description = stringResource(R.string.settings_debug_desc),
            checked = settings.debugOverlay,
            onCheckedChange = { v -> onUpdate { it.copy(debugOverlay = v) } },
        )
        ToggleRow(
            title = stringResource(R.string.settings_experimental),
            description = stringResource(R.string.settings_experimental_desc),
            checked = settings.experimentalFeatures,
            onCheckedChange = { v -> onUpdate { it.copy(experimentalFeatures = v) } },
        )
        BigActionButton(
            text = stringResource(R.string.settings_replay_onboarding),
            onClick = onReplayOnboarding,
            icon = Icons.Filled.School,
            minHeight = MinTouchTarget,
            style = ActionStyle.QUIET,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    androidx.compose.material3.Text(text, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
}

private fun format1(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)

@Preview(name = "Settings", showBackground = true, heightDp = 3000)
@Composable
private fun SettingsPreview() {
    VisionNavTheme {
        SettingsScreen(
            settings = AppSettings(emergencyContacts = listOf(EmergencyContact("Priya", "+91 98400 12345"))),
            onUpdate = {}, onAddContact = {}, onRemoveContact = {}, onReplayOnboarding = {}, onBack = {},
        )
    }
}
