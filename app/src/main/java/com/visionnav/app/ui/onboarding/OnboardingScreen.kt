package com.visionnav.app.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.visionnav.app.R
import com.visionnav.app.settings.EmergencyContact
import com.visionnav.app.ui.components.ActionStyle
import com.visionnav.app.ui.components.BigActionButton
import com.visionnav.app.ui.components.BodyText
import com.visionnav.app.ui.components.EmergencyContactsEditor
import com.visionnav.app.ui.components.IconStatement
import com.visionnav.app.ui.components.MinTouchTarget
import com.visionnav.app.ui.components.OutlinedPanel
import com.visionnav.app.ui.components.StatusLine
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.theme.VisionNavTheme

private const val PAGE_COUNT = 5

/** A runtime permission with the user-facing rationale. */
private data class PermissionItem(
    val permissions: List<String>,
    val titleRes: Int,
    val bodyRes: Int,
)

private fun permissionItems(): List<PermissionItem> = buildList {
    add(PermissionItem(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), R.string.perm_location_title, R.string.perm_location_body))
    add(PermissionItem(listOf(Manifest.permission.CAMERA), R.string.perm_camera_title, R.string.perm_camera_body))
    add(PermissionItem(listOf(Manifest.permission.RECORD_AUDIO), R.string.perm_microphone_title, R.string.perm_microphone_body))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(PermissionItem(listOf(Manifest.permission.ACTIVITY_RECOGNITION), R.string.perm_activity_title, R.string.perm_activity_body))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(PermissionItem(listOf(Manifest.permission.POST_NOTIFICATIONS), R.string.perm_notifications_title, R.string.perm_notifications_body))
    }
    add(PermissionItem(listOf(Manifest.permission.SEND_SMS), R.string.perm_sms_title, R.string.perm_sms_body))
}

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * First-run introduction. One page at a time with explicit Next / Previous buttons (no swipe
 * pager: swiping conflicts with TalkBack gestures). Each page title is the pane title, so
 * TalkBack announces page changes.
 */
@Composable
fun OnboardingScreen(
    contacts: List<EmergencyContact>,
    onAddContact: (EmergencyContact) -> Unit,
    onRemoveContact: (EmergencyContact) -> Unit,
    onFinished: () -> Unit,
    startPage: Int = 0,
) {
    var page by rememberSaveable { mutableIntStateOf(startPage) }
    BackHandler(enabled = page > 0) { page-- }

    val title = stringResource(
        when (page) {
            0 -> R.string.onboarding_welcome_title
            1 -> R.string.onboarding_safety_title
            2 -> R.string.onboarding_hold_title
            3 -> R.string.onboarding_permissions_title
            else -> R.string.onboarding_contacts_title
        },
    )
    VnScreen(title = title, onBack = if (page > 0) ({ page-- }) else null) {
        Text(stringResource(R.string.onboarding_step, page + 1, PAGE_COUNT), style = MaterialTheme.typography.titleMedium)
        when (page) {
            0 -> WelcomePage()
            1 -> SafetyPage()
            2 -> HoldPhonePage()
            3 -> PermissionsPage()
            else -> {
                BodyText(stringResource(R.string.onboarding_contacts_body))
                EmergencyContactsEditor(contacts, onAddContact, onRemoveContact)
            }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                1 -> BigActionButton(stringResource(R.string.onboarding_safety_ack), { page++ }, icon = Icons.Filled.Done, style = ActionStyle.PRIMARY)
                PAGE_COUNT - 1 -> {
                    BigActionButton(stringResource(R.string.onboarding_finish), onFinished, icon = Icons.Filled.Done, style = ActionStyle.PRIMARY)
                    if (contacts.isEmpty()) {
                        BigActionButton(stringResource(R.string.onboarding_skip_contacts), onFinished, minHeight = MinTouchTarget, style = ActionStyle.QUIET)
                    }
                }
                else -> BigActionButton(stringResource(R.string.action_next), { page++ }, icon = Icons.AutoMirrored.Filled.ArrowForward, style = ActionStyle.PRIMARY)
            }
            if (page > 0) {
                BigActionButton(stringResource(R.string.action_previous), { page-- }, icon = Icons.AutoMirrored.Filled.ArrowBack, minHeight = MinTouchTarget, style = ActionStyle.QUIET)
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    BodyText(stringResource(R.string.onboarding_welcome_body))
    IconStatement(Icons.Filled.Mic, stringResource(R.string.onboarding_welcome_point_voice))
    IconStatement(Icons.Filled.Vibration, stringResource(R.string.onboarding_welcome_point_haptics))
    IconStatement(Icons.Filled.Lock, stringResource(R.string.onboarding_welcome_point_device))
}

@Composable
private fun SafetyPage() {
    Text(stringResource(R.string.onboarding_safety_body), style = MaterialTheme.typography.titleLarge)
    OutlinedPanel {
        IconStatement(Icons.Filled.Traffic, stringResource(R.string.safety_never_decides), emphasized = true)
        IconStatement(Icons.Filled.Visibility, stringResource(R.string.safety_ahead_only))
        IconStatement(Icons.Filled.VisibilityOff, stringResource(R.string.safety_may_miss))
        IconStatement(Icons.Filled.Hearing, stringResource(R.string.safety_silence))
        IconStatement(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.limit_not_detected))
    }
}

@Composable
private fun HoldPhonePage() {
    BodyText(stringResource(R.string.onboarding_hold_body))
    IconStatement(Icons.Filled.PhoneAndroid, stringResource(R.string.hold_point_mount))
    IconStatement(Icons.AutoMirrored.Filled.DirectionsWalk, stringResource(R.string.hold_point_steady))
    IconStatement(Icons.Filled.Visibility, stringResource(R.string.hold_point_lens))
    IconStatement(Icons.Filled.Hearing, stringResource(R.string.hold_point_audio))
}

@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val items = remember { permissionItems() }
    // null = never requested in this session; recomputed from the system after each request.
    val results = remember { mutableStateMapOf<String, Boolean>() }
    var requested by rememberSaveable { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        granted.forEach { (perm, ok) -> results[perm] = ok }
        requested++
    }

    BodyText(stringResource(R.string.onboarding_permissions_body))
    items.forEach { item ->
        val granted = !inPreview && item.permissions.any { results[it] ?: context.isGranted(it) }
        val status = when {
            granted -> stringResource(R.string.perm_status_granted)
            requested > 0 -> stringResource(R.string.perm_status_denied)
            else -> stringResource(R.string.perm_status_unknown)
        }
        OutlinedPanel {
            StatusLine(
                icon = if (granted) Icons.Filled.CheckCircle else if (requested > 0) Icons.Filled.Cancel else Icons.Filled.Security,
                text = "${stringResource(item.titleRes)}: $status",
                level = null,
                detail = stringResource(item.bodyRes),
            )
        }
    }
    BigActionButton(
        text = stringResource(if (requested > 0) R.string.onboarding_grant_again else R.string.onboarding_grant),
        onClick = { launcher.launch(items.flatMap { it.permissions }.toTypedArray()) },
        icon = Icons.Filled.Security,
        style = ActionStyle.SECONDARY,
    )
}

@Preview(name = "Onboarding – safety", showBackground = true, heightDp = 1400)
@Composable
private fun OnboardingSafetyPreview() {
    VisionNavTheme { OnboardingScreen(emptyList(), {}, {}, {}, startPage = 1) }
}

@Preview(name = "Onboarding – permissions", showBackground = true, heightDp = 1800)
@Composable
private fun OnboardingPermissionsPreview() {
    VisionNavTheme { OnboardingScreen(emptyList(), {}, {}, {}, startPage = 3) }
}
