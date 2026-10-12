package com.visionnav.app.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import com.visionnav.app.ui.components.MinTouchTarget
import com.visionnav.app.ui.components.TorchControl
import com.visionnav.app.ui.components.VnDialog
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.UiAction
import com.visionnav.app.ui.preview.PreviewSamples
import com.visionnav.app.ui.theme.VisionNavTheme
import kotlin.math.roundToInt

/**
 * Turn-by-turn guidance. The current instruction is the largest element on screen and a
 * polite live region; safety alerts are pinned above the scroll area. Keeps the screen awake.
 */
@Composable
fun ActiveNavigationScreen(
    state: AppUiState,
    onAction: (UiAction) -> Unit,
    onOpenCrossing: () -> Unit,
    onBack: () -> Unit,
) {
    KeepScreenOn()
    val nav = state.navigation
    var confirmStop by rememberSaveable { mutableStateOf(false) }

    VnScreen(
        title = nav?.let { stringResource(R.string.nav_title, it.destinationName) } ?: stringResource(R.string.nav_title_generic),
        onBack = onBack,
        pinned = { AlertArea(state, { onAction(UiAction.DismissAlert(it)) }, { onAction(UiAction.DismissError(it)) }) },
    ) {
        if (nav == null) {
            BodyText(stringResource(R.string.nav_starting), polite = true)
            return@VnScreen
        }

        if (nav.isOffRoute || nav.isRerouting) {
            IconStatement(
                Icons.Filled.Warning,
                listOfNotNull(
                    stringResource(R.string.nav_off_route).takeIf { nav.isOffRoute },
                    stringResource(R.string.nav_rerouting).takeIf { nav.isRerouting },
                ).joinToString(" "),
                emphasized = true,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        // Giant instruction card.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
            border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.nav_current_heading),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                if (nav.hasArrived) {
                    Text(stringResource(R.string.nav_arrived), style = MaterialTheme.typography.displayMedium)
                }
                nav.distanceToNextText?.let {
                    Text(it, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(nav.currentInstruction, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
            }
        }

        nav.nextInstruction?.let {
            Text(stringResource(R.string.nav_then, it), style = MaterialTheme.typography.titleLarge)
        }
        nav.upcomingCrossingText?.let { IconStatement(Icons.Filled.Traffic, it, emphasized = true) }

        val percent = (nav.progressFraction.coerceIn(0f, 1f) * 100).roundToInt()
        val progressLabel = stringResource(R.string.nav_progress, percent)
        Column(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = listOfNotNull(progressLabel, nav.remainingText).joinToString(". ") },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LinearProgressIndicator(
                progress = { nav.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(14.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Text(progressLabel, style = MaterialTheme.typography.bodyLarge)
            nav.remainingText?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }

        if (nav.hasArrived) {
            BigActionButton(
                text = stringResource(R.string.nav_finish),
                onClick = { onAction(UiAction.StopNavigation) },
                icon = Icons.Filled.Flag,
                style = ActionStyle.PRIMARY,
            )
        } else {
            BigActionButton(
                text = stringResource(R.string.nav_repeat),
                onClick = { onAction(UiAction.RepeatLast) },
                icon = Icons.Filled.Replay,
                style = ActionStyle.PRIMARY,
            )
            BigActionButton(
                text = stringResource(R.string.home_traffic_check),
                supportingText = stringResource(R.string.home_traffic_hint),
                onClick = {
                    onAction(UiAction.StartTrafficObservation)
                    onOpenCrossing()
                },
                icon = Icons.Filled.Traffic,
            )
            TorchControl(state.torch, onToggle = { onAction(UiAction.ToggleTorch) })
            BigActionButton(
                text = stringResource(R.string.nav_stop),
                onClick = { confirmStop = true },
                icon = Icons.Filled.Stop,
                style = ActionStyle.DANGER,
                minHeight = MinTouchTarget,
            )
        }
    }

    if (confirmStop && nav != null) {
        VnDialog(
            title = stringResource(R.string.nav_stop_confirm_title),
            body = stringResource(R.string.nav_stop_confirm_body, nav.destinationName),
            onDismissRequest = { confirmStop = false },
        ) {
            BigActionButton(
                text = stringResource(R.string.nav_stop_confirm_yes),
                onClick = {
                    confirmStop = false
                    onAction(UiAction.StopNavigation)
                },
                style = ActionStyle.DANGER,
                minHeight = MinTouchTarget,
            )
            BigActionButton(
                text = stringResource(R.string.nav_stop_confirm_no),
                onClick = { confirmStop = false },
                style = ActionStyle.PRIMARY,
                minHeight = MinTouchTarget,
            )
        }
    }
}

@Preview(name = "Navigation", showBackground = true, heightDp = 1400)
@Composable
private fun NavigationPreview() {
    VisionNavTheme { ActiveNavigationScreen(PreviewSamples.navigating, {}, {}, {}) }
}

@Preview(name = "Navigation – critical alert", showBackground = true, heightDp = 1400)
@Composable
private fun NavigationCriticalPreview() {
    VisionNavTheme { ActiveNavigationScreen(PreviewSamples.navigatingWithCritical, {}, {}, {}) }
}
