package com.visionnav.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visionnav.app.R
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.ErrorBannerUi
import com.visionnav.app.ui.contract.PerformanceMode
import com.visionnav.app.ui.contract.SafetyAlertUi
import com.visionnav.app.ui.contract.StatusLevel
import com.visionnav.app.ui.theme.VisionNavTheme
import com.visionnav.core.safety.SafetySeverity
import kotlin.math.roundToInt

@Composable
private fun levelIcon(level: StatusLevel): ImageVector = when (level) {
    StatusLevel.GOOD -> Icons.Filled.CheckCircle
    StatusLevel.DEGRADED -> Icons.Filled.Warning
    StatusLevel.UNAVAILABLE -> Icons.Filled.Cancel
    StatusLevel.OFF -> Icons.Filled.PowerSettingsNew
}

@Composable
private fun levelColor(level: StatusLevel): Color = when (level) {
    StatusLevel.GOOD -> VisionNavTheme.status.good
    StatusLevel.DEGRADED -> VisionNavTheme.status.degraded
    StatusLevel.UNAVAILABLE -> VisionNavTheme.status.bad
    StatusLevel.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun levelText(level: StatusLevel): String = stringResource(
    when (level) {
        StatusLevel.GOOD -> R.string.status_level_good
        StatusLevel.DEGRADED -> R.string.status_level_degraded
        StatusLevel.UNAVAILABLE -> R.string.status_level_unavailable
        StatusLevel.OFF -> R.string.status_level_off
    },
)

/** One status line: subsystem icon, text, and a level badge with icon + word. Read as one node. */
@Composable
fun StatusLine(
    icon: ImageVector,
    text: String,
    level: StatusLevel?,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
        if (level != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(levelIcon(level), contentDescription = null, tint = levelColor(level), modifier = Modifier.size(28.dp))
                Text(levelText(level), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** GPS / heading / camera / detector summary. The mock detector is always called out. */
@Composable
fun StatusStrip(state: AppUiState, modifier: Modifier = Modifier) {
    OutlinedPanel(modifier) {
        val gps = state.gps
        val gpsBase = gps.text.ifBlank { stringResource(R.string.status_location_unknown) }
        val gpsText = if (gps.accuracyM != null) {
            stringResource(R.string.status_location_accuracy, gpsBase, gps.accuracyM.roundToInt())
        } else {
            gpsBase
        }
        StatusLine(Icons.Filled.GpsFixed, stringResource(R.string.status_location, gpsText), gps.level)

        val heading = state.heading
        val headingText = when {
            heading.text == null -> stringResource(R.string.status_heading_unknown)
            !heading.reliable -> stringResource(R.string.status_heading, stringResource(R.string.status_heading_unreliable, heading.text))
            else -> stringResource(R.string.status_heading, heading.text)
        }
        StatusLine(
            icon = Icons.Filled.Explore,
            text = headingText,
            level = when {
                heading.text == null -> StatusLevel.UNAVAILABLE
                !heading.reliable || heading.needsCalibration -> StatusLevel.DEGRADED
                else -> StatusLevel.GOOD
            },
            detail = if (heading.needsCalibration) stringResource(R.string.status_heading_calibrate) else null,
        )

        val cam = state.camera
        StatusLine(
            Icons.Filled.PhotoCamera,
            stringResource(R.string.status_camera, cam.text.ifBlank { levelText(cam.level) }),
            cam.level,
        )

        when {
            cam.detectorModelName == null -> StatusLine(
                Icons.Filled.Memory,
                stringResource(R.string.status_detector, stringResource(R.string.status_detector_none)),
                StatusLevel.UNAVAILABLE,
            )
            cam.isMockDetector -> StatusLine(
                Icons.Filled.Science,
                stringResource(R.string.status_detector_mock),
                StatusLevel.DEGRADED,
                detail = cam.detectorModelName,
            )
            else -> StatusLine(
                Icons.Filled.Memory,
                stringResource(R.string.status_detector, cam.detectorModelName),
                if (cam.supportsVehicles) StatusLevel.GOOD else StatusLevel.DEGRADED,
                detail = if (!cam.supportsVehicles) stringResource(R.string.status_detector_no_vehicles) else null,
            )
        }

        if (state.performanceMode != PerformanceMode.FULL) {
            val modeText = stringResource(
                when (state.performanceMode) {
                    PerformanceMode.FULL -> R.string.perf_full
                    PerformanceMode.BATTERY_SAVER -> R.string.perf_battery_saver
                    PerformanceMode.THERMAL_LIMITED -> R.string.perf_thermal
                },
            )
            StatusLine(
                Icons.Filled.Speed,
                stringResource(R.string.status_performance, modeText),
                StatusLevel.DEGRADED,
                detail = state.performanceText,
            )
        }
    }
}

/**
 * High-contrast safety banner. CRITICAL alerts are an assertive live region (interrupt
 * TalkBack); all others are polite. Severity is conveyed by icon + label text, not colour only.
 */
@Composable
fun SafetyAlertBanner(alert: SafetyAlertUi, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val status = VisionNavTheme.status
    val (bg, fg) = when (alert.severity) {
        SafetySeverity.CRITICAL -> status.critical to status.onCritical
        SafetySeverity.WARNING, SafetySeverity.CAUTION -> status.warning to status.onWarning
        SafetySeverity.INFO -> status.info to status.onInfo
    }
    val icon = when (alert.severity) {
        SafetySeverity.CRITICAL -> Icons.Filled.Report
        SafetySeverity.WARNING, SafetySeverity.CAUTION -> Icons.Filled.Warning
        SafetySeverity.INFO -> Icons.Filled.Info
    }
    val label = stringResource(
        when (alert.severity) {
            SafetySeverity.CRITICAL -> R.string.alert_critical
            SafetySeverity.WARNING -> R.string.alert_warning
            SafetySeverity.CAUTION -> R.string.alert_caution
            SafetySeverity.INFO -> R.string.alert_info
        },
    )
    val live = if (alert.severity == SafetySeverity.CRITICAL) LiveRegionMode.Assertive else LiveRegionMode.Polite
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.onBackground),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics(mergeDescendants = true) { liveRegion = live },
            ) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
                Text(alert.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            LargeIconButton(Icons.Filled.Close, stringResource(R.string.alert_dismiss), onDismiss, tint = fg)
        }
    }
}

/** Non-safety problems (network, permissions...). Polite live region. */
@Composable
fun ErrorBanners(errors: List<ErrorBannerUi>, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (errors.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val label = stringResource(R.string.error_label)
        errors.forEach { error ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(3.dp, MaterialTheme.colorScheme.error),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        Text(error.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    LargeIconButton(Icons.Filled.Close, stringResource(R.string.error_dismiss), { onDismiss(error.id) })
                }
            }
        }
    }
}

/** Pinned alert area used by every main screen. */
@Composable
fun AlertArea(state: AppUiState, onDismissAlert: (Long) -> Unit, onDismissError: (Long) -> Unit) {
    state.safetyAlert?.let { SafetyAlertBanner(it, onDismiss = { onDismissAlert(it.id) }) }
    ErrorBanners(state.errors, onDismissError)
}
