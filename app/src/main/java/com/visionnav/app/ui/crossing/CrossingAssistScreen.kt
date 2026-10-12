package com.visionnav.app.ui.crossing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FrontHand
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.visionnav.app.R
import com.visionnav.app.ui.components.ActionStyle
import com.visionnav.app.ui.components.AlertArea
import com.visionnav.app.ui.components.BigActionButton
import com.visionnav.app.ui.components.BodyText
import com.visionnav.app.ui.components.IconStatement
import com.visionnav.app.ui.components.KeepScreenOn
import com.visionnav.app.ui.components.OutlinedPanel
import com.visionnav.app.ui.components.SectionHeading
import com.visionnav.app.ui.components.TorchControl
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.UiAction
import com.visionnav.app.ui.preview.PreviewSamples
import com.visionnav.app.ui.theme.VisionNavTheme
import com.visionnav.core.crossing.CrossingAssistState
import com.visionnav.core.crossing.CrossingStage

/**
 * Traffic observation at a crossing. Describes only what the camera observes; never advises
 * when to cross. The disclaimer is pinned so it is always visible and is read first.
 * Back ends the observation (same as "End observation").
 */
@Composable
fun CrossingAssistScreen(
    state: AppUiState,
    onAction: (UiAction) -> Unit,
    onBack: () -> Unit,
) {
    KeepScreenOn()
    val crossing = state.crossing
    val endObservation = {
        onAction(UiAction.StopTrafficObservation)
        onBack()
    }
    BackHandler(onBack = endObservation)

    VnScreen(
        title = stringResource(R.string.crossing_title),
        onBack = endObservation,
        pinned = {
            AlertArea(state, { onAction(UiAction.DismissAlert(it)) }, { onAction(UiAction.DismissError(it)) })
            Disclaimer()
        },
    ) {
        // State + stage: a polite live region so changes ("View unclear") are announced.
        val uncertain = crossing.state == CrossingAssistState.OBSERVATION_UNCERTAIN
        OutlinedPanel(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Text(
                stringResource(R.string.crossing_status_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (uncertain) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).padding(end = 8.dp),
                )
                Text(
                    crossing.stateText.ifBlank { stringResource(R.string.crossing_starting) },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Text(stringResource(R.string.crossing_stage, stageText(crossing.stage)), style = MaterialTheme.typography.titleMedium)
            if (crossing.userConfirmedAtCrossing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(28.dp).padding(end = 6.dp))
                    Text(stringResource(R.string.crossing_confirmed), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        IconStatement(Icons.Filled.Visibility, stringResource(R.string.crossing_coverage), emphasized = true)

        crossing.contextText?.let {
            SectionHeading(stringResource(R.string.crossing_context_heading))
            IconStatement(Icons.Filled.Map, it)
        }

        SectionHeading(stringResource(R.string.crossing_observations_heading))
        // Container is the polite live region; newest observation is first.
        Column(
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (crossing.observations.isEmpty()) {
                BodyText(stringResource(R.string.crossing_no_observations))
            } else {
                crossing.observations.forEachIndexed { index, line ->
                    ObservationRow(line, newest = index == 0)
                }
            }
        }

        if (!crossing.userConfirmedAtCrossing) {
            BigActionButton(
                text = stringResource(R.string.crossing_confirm),
                supportingText = stringResource(R.string.crossing_confirm_hint),
                onClick = { onAction(UiAction.ConfirmAtCrossing) },
                icon = Icons.Filled.FrontHand,
                style = ActionStyle.PRIMARY,
            )
        }
        BigActionButton(
            text = stringResource(R.string.crossing_next_segment),
            supportingText = stringResource(
                if (crossing.refugeMapped) R.string.crossing_next_segment_hint else R.string.crossing_next_segment_unmapped,
            ),
            onClick = { onAction(UiAction.NextSegment) },
            icon = Icons.Filled.SwapHoriz,
        )
        TorchControl(state.torch, onToggle = { onAction(UiAction.ToggleTorch) })
        BigActionButton(
            text = stringResource(R.string.crossing_end),
            onClick = endObservation,
            icon = Icons.Filled.Stop,
            style = ActionStyle.DANGER,
        )
    }
}

@Composable
private fun Disclaimer() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
    ) {
        Row(Modifier.padding(12.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Traffic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text(
                stringResource(R.string.crossing_disclaimer),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun ObservationRow(text: String, newest: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (newest) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = if (newest) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Text(
            text,
            style = if (newest) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = if (newest) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun stageText(stage: CrossingStage): String = stringResource(
    when (stage) {
        CrossingStage.SIDEWALK -> R.string.stage_sidewalk
        CrossingStage.ROAD_SEGMENT_1 -> R.string.stage_segment_1
        CrossingStage.REFUGE -> R.string.stage_refuge
        CrossingStage.ROAD_SEGMENT_2 -> R.string.stage_segment_2
        CrossingStage.OPPOSITE_SIDEWALK -> R.string.stage_opposite
        CrossingStage.UNKNOWN -> R.string.stage_unknown
    },
)

@Preview(name = "Crossing assist", showBackground = true, heightDp = 1800)
@Composable
private fun CrossingPreview() {
    VisionNavTheme { CrossingAssistScreen(PreviewSamples.crossing, {}, {}) }
}
