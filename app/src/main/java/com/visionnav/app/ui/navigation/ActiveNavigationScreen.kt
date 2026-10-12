package com.visionnav.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.visionnav.app.R
import com.visionnav.app.ui.components.ActionStyle
import com.visionnav.app.ui.components.AlertArea
import com.visionnav.app.ui.components.BigActionButton
import com.visionnav.app.ui.components.KeepScreenOn
import com.visionnav.app.ui.components.MinTouchTarget
import com.visionnav.app.ui.components.TorchControl
import com.visionnav.app.ui.components.VnDialog
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.NavigationUi
import com.visionnav.app.ui.contract.UiAction
import com.visionnav.app.ui.kerb.LaneProgress
import com.visionnav.app.ui.kerb.RoadArrow
import com.visionnav.app.ui.kerb.bleed
import com.visionnav.app.ui.preview.PreviewSamples
import com.visionnav.app.ui.theme.VisionNavTheme
import kotlin.math.roundToInt

/**
 * Turn-by-turn guidance, laid out like a direction sign: the painted arrow and the distance side by
 * side, then the instruction in display type. Everything a glance needs is above the fold; the
 * instruction block is a polite live region (TTS has already spoken it). Keeps the screen awake.
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
    val k = VisionNavTheme.kerb
    var confirmStop by rememberSaveable { mutableStateOf(false) }

    VnScreen(
        title = nav?.destinationName ?: stringResource(R.string.nav_title_generic),
        onBack = onBack,
        pinned = { AlertArea(state, { onAction(UiAction.DismissAlert(it)) }, { onAction(UiAction.DismissError(it)) }) },
    ) {
        if (nav == null) {
            Text(
                stringResource(R.string.nav_starting),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@VnScreen
        }

        if (nav.isOffRoute) OffRouteBand(nav)

        DirectionSign(nav)

        nav.nextInstruction?.let {
            Text(stringResource(R.string.nav_then, it), style = MaterialTheme.typography.titleLarge, color = k.onGroundDim)
        }
        nav.upcomingCrossingText?.let {
            Text(it, style = MaterialTheme.typography.titleLarge, color = k.amberInk)
        }

        val percent = (nav.progressFraction.coerceIn(0f, 1f) * 100).roundToInt()
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LaneProgress(
                nav.progressFraction,
                description = listOfNotNull(stringResource(R.string.nav_progress, percent), nav.remainingText).joinToString(". "),
            )
            nav.remainingText?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = k.onGroundDim) }
        }
        Spacer(Modifier.height(4.dp))

        if (nav.hasArrived) {
            BigActionButton(
                text = stringResource(R.string.nav_finish),
                onClick = { onAction(UiAction.StopNavigation) },
                style = ActionStyle.PRIMARY,
            )
        } else {
            BigActionButton(
                text = stringResource(R.string.nav_repeat),
                onClick = { onAction(UiAction.RepeatLast) },
            )
            BigActionButton(
                text = stringResource(R.string.home_check_traffic),
                onClick = {
                    onAction(UiAction.StartTrafficObservation)
                    onOpenCrossing()
                },
                onClickLabel = stringResource(R.string.home_traffic_hint),
            )
            Spacer(Modifier.height(6.dp))
            TorchControl(state.torch, onToggle = { onAction(UiAction.ToggleTorch) })
            BigActionButton(
                text = stringResource(R.string.nav_stop),
                onClick = { confirmStop = true },
                style = ActionStyle.QUIET,
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

/** Arrow + distance on one line, instruction below: read like a finger-post sign. */
@Composable
private fun DirectionSign(nav: NavigationUi) {
    val k = VisionNavTheme.kerb
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RoadArrow(nav.arrow, Modifier.size(width = 104.dp, height = 132.dp))
            val distance = if (nav.hasArrived) stringResource(R.string.nav_arrived) else nav.distanceToNextText
            distance?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.displayMedium,
                    color = k.amberInk,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        Text(
            nav.currentInstruction,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** Off-route is a state change, not an emergency: an amber band, worded plainly. */
@Composable
private fun OffRouteBand(nav: NavigationUi) {
    val k = VisionNavTheme.kerb
    Row(
        Modifier
            .bleed()
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(k.amber)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(10.dp).fillMaxHeight().background(k.onAmber))
        Text(
            listOfNotNull(
                stringResource(R.string.nav_off_route),
                stringResource(R.string.nav_rerouting).takeIf { nav.isRerouting },
            ).joinToString(" "),
            style = MaterialTheme.typography.titleLarge,
            color = k.onAmber,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

@Preview(name = "Navigation", showBackground = true, heightDp = 1400)
@Composable
private fun NavigationPreview() {
    VisionNavTheme { ActiveNavigationScreen(PreviewSamples.navigating, {}, {}, {}) }
}

@Preview(name = "Navigation, critical alert", showBackground = true, heightDp = 1400)
@Composable
private fun NavigationCriticalPreview() {
    VisionNavTheme { ActiveNavigationScreen(PreviewSamples.navigatingWithCritical, {}, {}, {}) }
}
