package com.visionnav.app.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.visionnav.app.R
import com.visionnav.app.ui.components.BodyText
import com.visionnav.app.ui.components.SectionHeading
import com.visionnav.app.ui.components.StatusStrip
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.UiAction
import com.visionnav.app.ui.kerb.bleed
import com.visionnav.app.ui.preview.PreviewSamples
import com.visionnav.app.ui.theme.VisionNavTheme

/**
 * Slot for the camera preview + detection boxes. The integrator passes the real overlay
 * (CameraX PreviewView + box Canvas) as [content]; the 3:4 frame matches portrait analysis.
 */
@Composable
fun DebugOverlay(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = { DebugOverlayPlaceholder() },
) {
    // A camera view is a window, not a held object: square, edge to edge, on a kerbstone.
    Box(
        modifier
            .bleed()
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clipToBounds()
            .background(VisionNavTheme.kerb.kerb),
        content = content,
    )
}

@Composable
fun BoxScope.DebugOverlayPlaceholder() {
    Text(
        stringResource(R.string.debug_overlay_placeholder),
        style = MaterialTheme.typography.bodyLarge,
        color = VisionNavTheme.kerb.onKerb,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(24.dp),
    )
}

/** Field-testing tools for sighted testers: overlay slot, status and the text-only session log. */
@Composable
fun DebugScreen(
    state: AppUiState,
    onAction: (UiAction) -> Unit,
    onBack: () -> Unit,
    overlayContent: @Composable BoxScope.() -> Unit = { DebugOverlayPlaceholder() },
) {
    DisposableEffect(Unit) {
        onAction(UiAction.OpenDebug)
        onDispose { onAction(UiAction.CloseDebug) }
    }
    VnScreen(title = stringResource(R.string.debug_title), onBack = onBack) {
        SectionHeading(stringResource(R.string.debug_overlay_heading))
        DebugOverlay(content = overlayContent)
        StatusStrip(state)

        SectionHeading(stringResource(R.string.debug_log_heading))
        if (state.debugLog.isEmpty()) {
            BodyText(stringResource(R.string.debug_log_empty))
        } else {
            BodyText(stringResource(R.string.debug_log_count, state.debugLog.size))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.debugLog.asReversed().take(100).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = VisionNavTheme.kerb.onGroundDim)
                }
            }
        }
    }
}

@Preview(name = "Debug", showBackground = true, heightDp = 1600)
@Composable
private fun DebugPreview() {
    VisionNavTheme {
        DebugScreen(PreviewSamples.navigating.copy(debugLog = listOf("#1 Action: StartNavigation", "#2 Spoke: Turn left onto Greams Road.")), {}, {})
    }
}
