package com.visionnav.app.ui.about

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visionnav.app.R
import com.visionnav.app.ui.components.SectionHeading
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.theme.VisionNavTheme

/**
 * A page for reading: plain paragraphs under headings, no icons or panels. Each limitation is
 * its own sentence, so TalkBack users can move through them one at a time.
 */
@Composable
fun AboutScreen(versionName: String, onBack: () -> Unit) {
    val k = VisionNavTheme.kerb
    val lead = MaterialTheme.typography.bodyLarge.copy(fontSize = 26.sp, lineHeight = 36.sp)
    VnScreen(title = stringResource(R.string.about_title), onBack = onBack) {
        Text(stringResource(R.string.about_what_body), style = lead)
        Text(stringResource(R.string.onboarding_safety_body), style = MaterialTheme.typography.bodyLarge)

        Section(R.string.about_safety_heading)
        Text(stringResource(R.string.safety_never_decides), style = MaterialTheme.typography.headlineSmall, color = k.amberInk)
        Paragraphs(R.string.safety_may_miss, R.string.safety_silence)

        Section(R.string.about_limits_heading)
        Paragraphs(
            R.string.safety_ahead_only,
            R.string.limit_hidden,
            R.string.limit_light,
            R.string.limit_not_detected,
            R.string.limit_signals,
            R.string.limit_distance,
            R.string.limit_gps,
        )

        Section(R.string.about_privacy_heading)
        Paragraphs(R.string.privacy_camera, R.string.privacy_network, R.string.privacy_log)

        Section(R.string.about_credits_heading)
        Paragraphs(
            R.string.credit_osm,
            R.string.credit_valhalla,
            R.string.credit_photon,
            R.string.credit_yolo,
            R.string.credit_font,
            R.string.credit_android,
        )

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.about_version, versionName), style = MaterialTheme.typography.bodyLarge, color = k.onGroundDim)
    }
}

@Composable
private fun Section(titleRes: Int) {
    Spacer(Modifier.height(12.dp))
    SectionHeading(stringResource(titleRes))
}

@Composable
private fun Paragraphs(vararg res: Int) {
    res.forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge) }
}

@Preview(name = "About", showBackground = true, heightDp = 3200)
@Composable
private fun AboutPreview() {
    VisionNavTheme { AboutScreen("1.0.0", {}) }
}
