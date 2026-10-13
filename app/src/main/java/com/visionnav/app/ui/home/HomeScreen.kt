package com.visionnav.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.visionnav.app.R
import com.visionnav.app.feature.FeatureScreen
import com.visionnav.app.ui.components.ActionStyle
import com.visionnav.app.ui.components.AlertArea
import com.visionnav.app.ui.components.BigActionButton
import com.visionnav.app.ui.components.ListGroup
import com.visionnav.app.ui.components.LongPressActionButton
import com.visionnav.app.ui.components.MinTouchTarget
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.SessionMode
import com.visionnav.app.ui.contract.StatusLevel
import com.visionnav.app.ui.contract.UiAction
import com.visionnav.app.ui.aura.ScreenGutter
import com.visionnav.app.ui.aura.StatusDot
import com.visionnav.app.ui.preview.PreviewSamples
import com.visionnav.app.ui.theme.VisionNavTheme

/**
 * Home, read top to bottom the way a TalkBack user swipes (AURA §08: where am I, what is happening,
 * do I need to do something): state, the Speak action (the one primary), four everyday actions,
 * a plain list of other places, and SOS last so it is never hit by accident.
 */
@Composable
fun HomeScreen(
    state: AppUiState,
    showDebug: Boolean,
    onAction: (UiAction) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenNavigation: () -> Unit,
    onOpenCrossing: () -> Unit,
    onOpenSavedPlaces: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDebug: () -> Unit,
    features: List<FeatureScreen> = emptyList(),
    onOpenFeature: (String) -> Unit = {},
) {
    val aura = VisionNavTheme.aura
    val homeTitle = stringResource(R.string.home_pane_title)
    val speakHeight = (LocalConfiguration.current.screenHeightDp * 0.18f).dp.coerceAtLeast(120.dp)

    Surface(Modifier.fillMaxSize(), color = aura.background, contentColor = aura.text) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .semantics { paneTitle = homeTitle }
                .verticalScroll(rememberScrollState())
                .padding(start = ScreenGutter, end = ScreenGutter, top = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HomeHeader(state)
            StatusSentence(state)
            AlertArea(state, { onAction(UiAction.DismissAlert(it)) }, { onAction(UiAction.DismissError(it)) })

            BigActionButton(
                text = stringResource(if (state.isListening) R.string.home_pad_listening else R.string.home_pad_speak),
                supportingText = stringResource(if (state.isListening) R.string.home_listening_tap_stop else R.string.home_speak_examples),
                onClick = { onAction(if (state.isListening) UiAction.StopVoiceCommand else UiAction.StartVoiceCommand) },
                onClickLabel = stringResource(if (state.isListening) R.string.home_pad_hint_stop else R.string.home_pad_hint),
                stateDescription = if (state.isListening) stringResource(R.string.home_listening_state) else null,
                style = ActionStyle.PRIMARY,
                minHeight = speakHeight,
            )
            state.voiceTranscript?.let {
                Text(
                    stringResource(R.string.home_heard, it),
                    style = MaterialTheme.typography.bodyLarge,
                    color = aura.textSecondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            // An active session is the next thing you want: a way straight back into it.
            val navigation = state.navigation
            when {
                navigation != null && state.sessionMode == SessionMode.NAVIGATING -> BigActionButton(
                    text = stringResource(R.string.home_back_to_route),
                    supportingText = navigation.destinationName,
                    onClick = onOpenNavigation,
                    style = ActionStyle.SECONDARY,
                )
                state.sessionMode == SessionMode.CROSSING_ASSIST -> BigActionButton(
                    text = stringResource(R.string.home_back_to_traffic),
                    supportingText = state.crossing.stateText.ifBlank { null },
                    onClick = onOpenCrossing,
                    style = ActionStyle.SECONDARY,
                )
                state.sessionMode == SessionMode.EXPLORING -> BigActionButton(
                    text = stringResource(R.string.home_explore_stop),
                    onClick = { onAction(UiAction.StopExploring) },
                    style = ActionStyle.SECONDARY,
                )
                else -> Unit
            }

            Spacer(Modifier.height(4.dp))
            BigActionButton(
                text = stringResource(R.string.home_where_am_i),
                onClick = { onAction(UiAction.WhereAmI) },
                onClickLabel = stringResource(R.string.home_where_am_i_hint),
            )
            BigActionButton(
                text = stringResource(R.string.home_whats_around),
                onClick = { onAction(UiAction.WhatsAround) },
                onClickLabel = stringResource(R.string.home_whats_around_hint),
            )
            BigActionButton(
                text = stringResource(R.string.home_check_traffic),
                onClick = {
                    if (state.sessionMode != SessionMode.CROSSING_ASSIST) onAction(UiAction.StartTrafficObservation)
                    onOpenCrossing()
                },
                onClickLabel = stringResource(R.string.home_traffic_hint),
            )
            if (state.sessionMode != SessionMode.NAVIGATING) {
                BigActionButton(
                    text = stringResource(R.string.home_go_somewhere),
                    onClick = onOpenSearch,
                    onClickLabel = stringResource(R.string.home_navigate_hint),
                )
            }
            Spacer(Modifier.height(10.dp))

            // Features (calls, messages, camera tools, app control...) list themselves here.
            val featureEntries = features.filter { it.homeLabel != null }
            if (featureEntries.isNotEmpty()) {
                ListGroup {
                    featureEntries.forEach { f ->
                        BigActionButton(
                            text = f.homeLabel!!,
                            onClick = { onOpenFeature(f.route) },
                            onClickLabel = f.homeHint,
                            style = ActionStyle.QUIET,
                            minHeight = MinTouchTarget,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            // One outlined list, rows separated by dividers (AURA §07: borders over boxes).
            ListGroup {
                state.lastSpoken?.let { said ->
                    BigActionButton(
                        text = stringResource(R.string.home_repeat),
                        supportingText = said,
                        onClick = { onAction(UiAction.RepeatLast) },
                        style = ActionStyle.QUIET,
                        minHeight = MinTouchTarget,
                    )
                }
                BigActionButton(stringResource(R.string.home_saved_places), onOpenSavedPlaces, style = ActionStyle.QUIET, minHeight = MinTouchTarget)
                if (state.sessionMode == SessionMode.IDLE) {
                    BigActionButton(
                        text = stringResource(R.string.home_explore_start),
                        onClick = { onAction(UiAction.StartExploring) },
                        onClickLabel = stringResource(R.string.home_explore_hint),
                        style = ActionStyle.QUIET,
                        minHeight = MinTouchTarget,
                    )
                }
                BigActionButton(stringResource(R.string.home_settings), onOpenSettings, style = ActionStyle.QUIET, minHeight = MinTouchTarget)
                BigActionButton(stringResource(R.string.home_about), onOpenAbout, style = ActionStyle.QUIET, minHeight = MinTouchTarget)
                if (showDebug) {
                    BigActionButton(stringResource(R.string.home_debug), onOpenDebug, style = ActionStyle.QUIET, minHeight = MinTouchTarget)
                }
            }
            Spacer(Modifier.height(10.dp))
            SosHold(onTrigger = { onAction(UiAction.TriggerSos) })
        }
    }
}

/** AURA §09 header: wordmark and the one state the user needs, as a dot and a word. */
@Composable
private fun HomeHeader(state: AppUiState) {
    val aura = VisionNavTheme.aura
    val (word, color) = when {
        state.safetyAlert != null -> stringResource(R.string.home_state_attention) to aura.risk
        state.sessionMode == SessionMode.NAVIGATING -> stringResource(R.string.home_state_on_route) to aura.safe
        state.sessionMode == SessionMode.CROSSING_ASSIST -> stringResource(R.string.home_state_traffic) to aura.info
        state.sessionMode == SessionMode.EXPLORING -> stringResource(R.string.home_state_exploring) to aura.info
        else -> stringResource(R.string.home_state_ready) to aura.safe
    }
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = aura.text)
        StatusDot(word, color)
    }
}

/** One sentence about the phone's senses. Problems are worded differently and set in amber. */
@Composable
private fun StatusSentence(state: AppUiState) {
    val aura = VisionNavTheme.aura
    val parts = buildList {
        add(state.gps.text.ifBlank { stringResource(R.string.status_location_unknown) })
        add(state.camera.text.ifBlank { stringResource(R.string.status_level_off) })
        if (state.camera.isMockDetector) add(stringResource(R.string.status_detector_mock))
        state.performanceText?.let { add(it) }
    }
    val problem = state.gps.level == StatusLevel.UNAVAILABLE || state.camera.level == StatusLevel.UNAVAILABLE || state.camera.isMockDetector
    Text(
        parts.joinToString(". ", postfix = "."),
        style = MaterialTheme.typography.bodyLarge,
        color = if (problem) aura.attention else aura.textSecondary,
    )
}

/** SOS needs a press and hold; a tap only explains how. Nothing is sent before confirmation. */
@Composable
private fun SosHold(onTrigger: () -> Unit) {
    var explain by rememberSaveable { mutableStateOf(false) }
    LongPressActionButton(
        text = stringResource(R.string.home_sos_hold),
        supportingText = stringResource(R.string.sos_button_hint),
        icon = Icons.Filled.Sos,
        onClick = { explain = true },
        onLongClick = {
            explain = false
            onTrigger()
        },
        onLongClickLabel = stringResource(R.string.sos_long_press_label),
    )
    if (explain) {
        Text(
            stringResource(R.string.sos_tap_hint),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Preview(name = "Home, night", showBackground = true, heightDp = 1500)
@Composable
private fun HomePreview() {
    VisionNavTheme {
        HomeScreen(PreviewSamples.idle, showDebug = true, onAction = {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(name = "Home, navigating, critical alert, 200% font", showBackground = true, heightDp = 2600, fontScale = 2f)
@Composable
private fun HomeNavigatingPreview() {
    VisionNavTheme {
        HomeScreen(PreviewSamples.navigatingWithCritical, showDebug = false, onAction = {}, {}, {}, {}, {}, {}, {}, {})
    }
}
