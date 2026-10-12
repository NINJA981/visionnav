package com.visionnav.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import com.visionnav.app.ui.components.ListGroup
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

private const val EPS = 0.001f

/**
 * Settings in plain-language groups. Every row is a kerbstone whose state is a written word;
 * there are no Material switches, sliders or radio dots.
 */
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
        Group(stringResource(R.string.settings_speech), first = true)
        SubLabel(stringResource(R.string.settings_verbosity))
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
            decreaseText = stringResource(R.string.settings_rate_slower),
            increaseText = stringResource(R.string.settings_rate_faster),
            onDecrease = { onUpdate { it.copy(speechRate = SettingsRepository.clampSpeech(it.speechRate - AppSettings.SPEECH_STEP)) } },
            onIncrease = { onUpdate { it.copy(speechRate = SettingsRepository.clampSpeech(it.speechRate + AppSettings.SPEECH_STEP)) } },
            canDecrease = settings.speechRate > AppSettings.SPEECH_MIN + EPS,
            canIncrease = settings.speechRate < AppSettings.SPEECH_MAX - EPS,
        )
        StepperRow(
            title = stringResource(R.string.settings_pitch),
            valueText = stringResource(R.string.settings_value_times, format1(settings.speechPitch)),
            decreaseText = stringResource(R.string.settings_pitch_lower),
            increaseText = stringResource(R.string.settings_pitch_higher),
            onDecrease = { onUpdate { it.copy(speechPitch = SettingsRepository.clampSpeech(it.speechPitch - AppSettings.SPEECH_STEP)) } },
            onIncrease = { onUpdate { it.copy(speechPitch = SettingsRepository.clampSpeech(it.speechPitch + AppSettings.SPEECH_STEP)) } },
            canDecrease = settings.speechPitch > AppSettings.SPEECH_MIN + EPS,
            canIncrease = settings.speechPitch < AppSettings.SPEECH_MAX - EPS,
        )

        Group(stringResource(R.string.settings_directions))
        SubLabel(stringResource(R.string.settings_direction_style))
        RadioGroup(
            options = listOf(
                RadioOption(DirectionStyle.LEFT_RIGHT, stringResource(R.string.direction_left_right), stringResource(R.string.direction_left_right_desc)),
                RadioOption(DirectionStyle.CLOCK_FACE, stringResource(R.string.direction_clock), stringResource(R.string.direction_clock_desc)),
            ),
            selected = settings.directionStyle,
            onSelect = { v -> onUpdate { it.copy(directionStyle = v) } },
        )
        SubLabel(stringResource(R.string.settings_units))
        RadioGroup(
            options = listOf(
                RadioOption(DistanceUnits.METRIC, stringResource(R.string.units_metric), stringResource(R.string.units_metric_desc)),
                RadioOption(DistanceUnits.IMPERIAL, stringResource(R.string.units_imperial), stringResource(R.string.units_imperial_desc)),
            ),
            selected = settings.units,
            onSelect = { v -> onUpdate { it.copy(units = v) } },
        )

        Group(stringResource(R.string.settings_alerts))
        ListGroup {
            ToggleRow(
                title = stringResource(R.string.settings_haptics),
                description = stringResource(R.string.settings_haptics_desc),
                checked = settings.hapticsEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(hapticsEnabled = v, hapticsOnly = if (!v) false else it.hapticsOnly) } },
            )
            ToggleRow(
                title = stringResource(R.string.settings_haptics_only),
                description = stringResource(
                    if (settings.hapticsEnabled) R.string.settings_haptics_only_desc else R.string.settings_haptics_only_needs,
                ),
                checked = settings.hapticsOnly,
                enabled = settings.hapticsEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(hapticsOnly = v) } },
            )
        }

        Group(stringResource(R.string.settings_camera))
        ToggleRow(
            title = stringResource(R.string.settings_auto_torch),
            description = stringResource(R.string.settings_auto_torch_desc),
            checked = settings.autoTorchInLowLight,
            onCheckedChange = { v -> onUpdate { it.copy(autoTorchInLowLight = v) } },
        )

        Group(stringResource(R.string.settings_contacts))
        EmergencyContactsEditor(settings.emergencyContacts, onAddContact, onRemoveContact)

        Group(stringResource(R.string.settings_display))
        RadioGroup(
            options = listOf(
                RadioOption(ThemeMode.DARK, stringResource(R.string.theme_dark)),
                RadioOption(ThemeMode.LIGHT, stringResource(R.string.theme_light)),
                RadioOption(ThemeMode.SYSTEM, stringResource(R.string.theme_system)),
            ),
            selected = settings.themeMode,
            onSelect = { v -> onUpdate { it.copy(themeMode = v) } },
        )
        Spacer(Modifier.height(4.dp))
        BigActionButton(
            text = stringResource(R.string.settings_replay_onboarding),
            onClick = onReplayOnboarding,
            style = ActionStyle.QUIET,
            minHeight = MinTouchTarget,
        )

        Group(stringResource(R.string.settings_developer))
        ListGroup {
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
        }
    }
}

/** Group heading with room above it, so groups read as separate stretches of kerb. */
@Composable
private fun Group(text: String, first: Boolean = false) {
    if (!first) Spacer(Modifier.height(18.dp))
    SectionHeading(text)
}

/** Names a choice whose options would be ambiguous on their own ("Minimal", "Metric"). */
@Composable
private fun SubLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = VisionNavTheme.aura.textSecondary,
        modifier = Modifier.semantics { heading() },
    )
}

private fun format1(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)

@Preview(name = "Settings", showBackground = true, heightDp = 4200)
@Composable
private fun SettingsPreview() {
    VisionNavTheme {
        SettingsScreen(
            settings = AppSettings(emergencyContacts = listOf(EmergencyContact("Priya", "+91 98400 12345"))),
            onUpdate = {}, onAddContact = {}, onRemoveContact = {}, onReplayOnboarding = {}, onBack = {},
        )
    }
}
