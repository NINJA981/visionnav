package com.visionnav.app.ui.crossing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.visionnav.app.R
import com.visionnav.app.ui.components.ActionStyle
import com.visionnav.app.ui.components.AlertArea
import com.visionnav.app.ui.components.BigActionButton
import com.visionnav.app.ui.components.KeepScreenOn
import com.visionnav.app.ui.components.MinTouchTarget
import com.visionnav.app.ui.components.TorchControl
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.UiAction
import com.visionnav.app.ui.aura.StatusDot
import com.visionnav.app.ui.preview.PreviewSamples
import com.visionnav.app.ui.theme.VisionNavTheme
import com.visionnav.core.crossing.CrossingAssistState
import com.visionnav.core.crossing.CrossingStage

/**
 * Traffic check. The screen describes only what the camera observes and never advises when to
 * cross. Reading order: what VisionNav is doing, what it can't do, what it has seen (newest first),
 * then actions. Back ends the observation (same as the end action).
 */
@Composable
fun CrossingAssistScreen(
    state: AppUiState,
    onAction: (UiAction) -> Unit,
    onBack: () -> Unit,
) {
    KeepScreenOn()
    val aura = VisionNavTheme.aura
    val crossing = state.crossing
    val endObservation = {
        onAction(UiAction.StopTrafficObservation)
        onBack()
    }
    BackHandler(onBack = endObservation)
    val uncertain = crossing.state == CrossingAssistState.OBSERVATION_UNCERTAIN

    VnScreen(
        title = stringResource(R.string.crossing_title),
        onBack = endObservation,
        pinned = { AlertArea(state, { onAction(UiAction.DismissAlert(it)) }, { onAction(UiAction.DismissError(it)) }) },
    ) {

        Column(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(
                crossing.stateText.ifBlank { stringResource(R.string.crossing_starting) },
                if (uncertain) aura.attention else aura.info,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.crossing_coverage), style = MaterialTheme.typography.titleLarge, color = aura.textSecondary)
            // Only once a multi-part crossing has progressed; "first part of the road" at the start
            // could be heard as "you are on the road".
            if (crossing.stage in SHOWN_STAGES) {
                Text(stringResource(R.string.crossing_stage, stageText(crossing.stage)), style = MaterialTheme.typography.titleMedium, color = aura.textSecondary)
            }
            if (crossing.userConfirmedAtCrossing) {
                Text(stringResource(R.string.crossing_confirmed), style = MaterialTheme.typography.titleMedium, color = aura.textSecondary)
            }
        }

        crossing.contextText?.let {
            Text(it, style = MaterialTheme.typography.headlineSmall, color = aura.text)
        }

        // Observation log: newest is largest; older lines step down in size and weight, not into boxes.
        Column(
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (crossing.observations.isEmpty()) {
                Text(stringResource(R.string.crossing_no_observations), style = MaterialTheme.typography.bodyLarge, color = aura.textSecondary)
            } else {
                crossing.observations.take(6).forEachIndexed { i, line ->
                    Text(line, style = observationStyle(i), color = if (i == 0) aura.text else aura.textSecondary)
                }
            }
        }

        Text(stringResource(R.string.crossing_disclaimer), style = MaterialTheme.typography.titleMedium, color = aura.text)
        Spacer(Modifier.height(4.dp))

        if (!crossing.userConfirmedAtCrossing) {
            BigActionButton(
                text = stringResource(R.string.crossing_confirm),
                onClick = { onAction(UiAction.ConfirmAtCrossing) },
                onClickLabel = stringResource(R.string.crossing_confirm_hint),
                style = ActionStyle.PRIMARY,
            )
        }
        BigActionButton(
            text = stringResource(R.string.crossing_next_segment),
            supportingText = if (crossing.refugeMapped) null else stringResource(R.string.crossing_next_segment_unmapped),
            onClick = { onAction(UiAction.NextSegment) },
            onClickLabel = stringResource(R.string.crossing_next_segment_hint),
        )
        Spacer(Modifier.height(6.dp))
        TorchControl(state.torch, onToggle = { onAction(UiAction.ToggleTorch) })
        BigActionButton(
            text = stringResource(R.string.crossing_end),
            onClick = endObservation,
            style = ActionStyle.QUIET,
            minHeight = MinTouchTarget,
        )
    }
}

private val SHOWN_STAGES = setOf(CrossingStage.REFUGE, CrossingStage.ROAD_SEGMENT_2, CrossingStage.UNKNOWN)

@Composable
private fun observationStyle(index: Int): TextStyle = when (index) {
    0 -> MaterialTheme.typography.headlineMedium
    1 -> MaterialTheme.typography.titleLarge
    else -> MaterialTheme.typography.bodyLarge
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

@Preview(name = "Traffic check", showBackground = true, heightDp = 1800)
@Composable
private fun CrossingPreview() {
    VisionNavTheme { CrossingAssistScreen(PreviewSamples.crossing, {}, {}) }
}
