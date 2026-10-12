package com.visionnav.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.visionnav.app.R
import com.visionnav.app.ui.components.ActionStyle
import com.visionnav.app.ui.components.AlertArea
import com.visionnav.app.ui.components.BigActionButton
import com.visionnav.app.ui.components.LastSpokenPanel
import com.visionnav.app.ui.components.MinTouchTarget
import com.visionnav.app.ui.components.SectionHeading
import com.visionnav.app.ui.components.SosButton
import com.visionnav.app.ui.components.StatusStrip
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.SessionMode
import com.visionnav.app.ui.contract.UiAction
import com.visionnav.app.ui.preview.PreviewSamples
import com.visionnav.app.ui.theme.VisionNavTheme

/**
 * Home. Traversal order is deliberate: primary voice action first, then the five core actions,
 * last utterance, status, SOS, and finally secondary navigation.
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
) {
    VnScreen(
        title = stringResource(R.string.app_name),
        onBack = null,
        pinned = {
            AlertArea(state, { onAction(UiAction.DismissAlert(it)) }, { onAction(UiAction.DismissError(it)) })
        },
    ) {
        Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyLarge)

        if (state.isListening) {
            BigActionButton(
                text = stringResource(R.string.home_listening),
                onClick = { onAction(UiAction.StopVoiceCommand) },
                icon = Icons.Filled.MicOff,
                style = ActionStyle.PRIMARY,
                minHeight = 120.dp,
                stateDescription = stringResource(R.string.home_listening_state),
            )
        } else {
            BigActionButton(
                text = stringResource(R.string.home_speak),
                supportingText = stringResource(R.string.home_speak_hint),
                onClick = { onAction(UiAction.StartVoiceCommand) },
                icon = Icons.Filled.Mic,
                style = ActionStyle.PRIMARY,
                minHeight = 120.dp,
            )
        }
        state.voiceTranscript?.let {
            Text(
                stringResource(R.string.home_heard, it),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        val navigation = state.navigation
        if (navigation != null && state.sessionMode == SessionMode.NAVIGATING) {
            BigActionButton(
                text = stringResource(R.string.home_resume_navigation),
                supportingText = stringResource(R.string.home_resume_hint, navigation.destinationName),
                onClick = onOpenNavigation,
                icon = Icons.Filled.Navigation,
                style = ActionStyle.PRIMARY,
            )
        }
        if (state.sessionMode == SessionMode.CROSSING_ASSIST) {
            BigActionButton(
                text = stringResource(R.string.crossing_title),
                supportingText = state.crossing.stateText.ifBlank { null },
                onClick = onOpenCrossing,
                icon = Icons.Filled.Traffic,
                style = ActionStyle.PRIMARY,
            )
        }

        BigActionButton(
            text = stringResource(R.string.home_where_am_i),
            supportingText = stringResource(R.string.home_where_am_i_hint),
            onClick = { onAction(UiAction.WhereAmI) },
            icon = Icons.Filled.MyLocation,
        )
        BigActionButton(
            text = stringResource(R.string.home_whats_around),
            supportingText = stringResource(R.string.home_whats_around_hint),
            onClick = { onAction(UiAction.WhatsAround) },
            icon = Icons.Filled.Visibility,
        )
        if (state.sessionMode != SessionMode.NAVIGATING) {
            BigActionButton(
                text = stringResource(R.string.home_navigate),
                supportingText = stringResource(R.string.home_navigate_hint),
                onClick = onOpenSearch,
                icon = Icons.Filled.Navigation,
            )
        }
        BigActionButton(
            text = stringResource(R.string.home_traffic_check),
            supportingText = stringResource(R.string.home_traffic_hint),
            onClick = {
                if (state.sessionMode != SessionMode.CROSSING_ASSIST) onAction(UiAction.StartTrafficObservation)
                onOpenCrossing()
            },
            icon = Icons.Filled.Traffic,
        )
        BigActionButton(
            text = stringResource(R.string.home_saved_places),
            supportingText = stringResource(R.string.home_saved_places_hint),
            onClick = onOpenSavedPlaces,
            icon = Icons.Filled.Bookmarks,
        )
        when (state.sessionMode) {
            SessionMode.IDLE -> BigActionButton(
                text = stringResource(R.string.home_explore_start),
                supportingText = stringResource(R.string.home_explore_hint),
                onClick = { onAction(UiAction.StartExploring) },
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                minHeight = MinTouchTarget,
            )
            SessionMode.EXPLORING -> BigActionButton(
                text = stringResource(R.string.home_explore_stop),
                onClick = { onAction(UiAction.StopExploring) },
                icon = Icons.Filled.Stop,
                style = ActionStyle.PRIMARY,
            )
            else -> Unit
        }

        LastSpokenPanel(state.lastSpoken, onRepeat = { onAction(UiAction.RepeatLast) })

        SectionHeading(stringResource(R.string.home_status_heading))
        StatusStrip(state)

        SosButton(onTrigger = { onAction(UiAction.TriggerSos) })

        SectionHeading(stringResource(R.string.home_more_heading))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BigActionButton(stringResource(R.string.home_settings), onOpenSettings, icon = Icons.Filled.Settings, minHeight = MinTouchTarget, style = ActionStyle.QUIET)
            BigActionButton(stringResource(R.string.home_about), onOpenAbout, icon = Icons.Filled.Info, minHeight = MinTouchTarget, style = ActionStyle.QUIET)
            if (showDebug) {
                BigActionButton(stringResource(R.string.home_debug), onOpenDebug, icon = Icons.Filled.BugReport, minHeight = MinTouchTarget, style = ActionStyle.QUIET)
            }
        }
    }
}

@Preview(name = "Home – dark", showBackground = true, heightDp = 1600)
@Composable
private fun HomePreview() {
    VisionNavTheme {
        HomeScreen(PreviewSamples.idle, showDebug = true, onAction = {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(name = "Home – navigating, critical alert, 200% font", showBackground = true, heightDp = 2400, fontScale = 2f)
@Composable
private fun HomeNavigatingPreview() {
    VisionNavTheme {
        HomeScreen(PreviewSamples.navigatingWithCritical, showDebug = false, onAction = {}, {}, {}, {}, {}, {}, {}, {})
    }
}
