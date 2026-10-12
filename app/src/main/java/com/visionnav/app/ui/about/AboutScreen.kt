package com.visionnav.app.ui.about

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Copyright
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.visionnav.app.R
import com.visionnav.app.ui.components.BodyText
import com.visionnav.app.ui.components.IconStatement
import com.visionnav.app.ui.components.OutlinedPanel
import com.visionnav.app.ui.components.SectionHeading
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.theme.VisionNavTheme

@Composable
fun AboutScreen(versionName: String, onBack: () -> Unit) {
    VnScreen(title = stringResource(R.string.about_title), onBack = onBack) {
        BodyText(stringResource(R.string.about_version, versionName))

        SectionHeading(stringResource(R.string.about_what_heading))
        BodyText(stringResource(R.string.about_what_body))
        BodyText(stringResource(R.string.onboarding_safety_body))

        SectionHeading(stringResource(R.string.about_safety_heading))
        OutlinedPanel {
            IconStatement(Icons.Filled.Traffic, stringResource(R.string.safety_never_decides), emphasized = true)
            IconStatement(Icons.Filled.Visibility, stringResource(R.string.safety_ahead_only))
            IconStatement(Icons.Filled.VisibilityOff, stringResource(R.string.safety_may_miss))
            IconStatement(Icons.Filled.Hearing, stringResource(R.string.safety_silence))
        }

        SectionHeading(stringResource(R.string.about_limits_heading))
        IconStatement(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.limit_not_detected))
        IconStatement(Icons.Filled.Straighten, stringResource(R.string.limit_distance))
        IconStatement(Icons.Filled.Traffic, stringResource(R.string.limit_signals))
        IconStatement(Icons.Filled.GpsNotFixed, stringResource(R.string.limit_gps))

        SectionHeading(stringResource(R.string.about_privacy_heading))
        IconStatement(Icons.Filled.Lock, stringResource(R.string.privacy_camera))
        IconStatement(Icons.Filled.CloudOff, stringResource(R.string.privacy_network))
        IconStatement(Icons.Filled.Description, stringResource(R.string.privacy_log))

        SectionHeading(stringResource(R.string.about_credits_heading))
        IconStatement(Icons.Filled.Map, stringResource(R.string.credit_osm))
        IconStatement(Icons.Filled.Copyright, stringResource(R.string.credit_valhalla))
        IconStatement(Icons.Filled.Copyright, stringResource(R.string.credit_photon))
        IconStatement(Icons.Filled.Copyright, stringResource(R.string.credit_yolo))
        IconStatement(Icons.Filled.Copyright, stringResource(R.string.credit_android))
    }
}

@Preview(name = "About", showBackground = true, heightDp = 2600)
@Composable
private fun AboutPreview() {
    VisionNavTheme { AboutScreen("1.0.0", {}) }
}
