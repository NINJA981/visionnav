package com.visionnav.app.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.visionnav.app.R
import com.visionnav.app.settings.EmergencyContact
import com.visionnav.app.ui.components.ActionStyle
import com.visionnav.app.ui.components.BigActionButton
import com.visionnav.app.ui.components.EmergencyContactsEditor
import com.visionnav.app.ui.components.KerbstoneGroup
import com.visionnav.app.ui.components.LargeIconButton
import com.visionnav.app.ui.components.MinTouchTarget
import com.visionnav.app.ui.kerb.LaneProgress
import com.visionnav.app.ui.kerb.ScreenGutter
import com.visionnav.app.ui.kerb.bleed
import com.visionnav.app.ui.theme.VisionNavTheme

private const val PAGE_COUNT = 5

/** A runtime permission with what it is for, in one line. */
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

/** Reading size for onboarding: large, regular weight, generous leading. */
@Composable
private fun leadStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 26.sp, lineHeight = 36.sp)

/**
 * First run, like arriving at a kerb: short pages, one idea each, huge type and one amber
 * action. Explicit buttons instead of a swipe pager (swiping conflicts with TalkBack). The page
 * title is the pane title, so TalkBack announces every page change; the dashed lane line shows
 * (and speaks) how far through setup you are.
 */
@Composable
fun OnboardingScreen(
    contacts: List<EmergencyContact>,
    onAddContact: (EmergencyContact) -> Unit,
    onRemoveContact: (EmergencyContact) -> Unit,
    onFinished: () -> Unit,
    startPage: Int = 0,
) {
    val k = VisionNavTheme.kerb
    var page by rememberSaveable { mutableIntStateOf(startPage) }
    BackHandler(enabled = page > 0) { page-- }
    val next = { page = (page + 1).coerceAtMost(PAGE_COUNT - 1) }

    val title = stringResource(
        when (page) {
            0 -> R.string.onboarding_welcome_title
            1 -> R.string.onboarding_safety_title
            2 -> R.string.onboarding_hold_title
            3 -> R.string.onboarding_permissions_title
            else -> R.string.onboarding_contacts_title
        },
    )
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }

    Surface(Modifier.fillMaxSize(), color = k.ground, contentColor = k.onGround) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .semantics { paneTitle = title }
                .verticalScroll(scroll)
                .padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (page > 0) {
                    LargeIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), { page-- })
                }
                LaneProgress(
                    fraction = (page + 1f) / PAGE_COUNT,
                    description = stringResource(R.string.onboarding_step, page + 1, PAGE_COUNT),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.semantics { heading() },
            )
            when (page) {
                0 -> WelcomePage(next)
                1 -> SafetyPage(next)
                2 -> HoldPhonePage(next)
                3 -> PermissionsPage(next)
                else -> ContactsPage(contacts, onAddContact, onRemoveContact, onFinished)
            }
        }
    }
}

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    val k = VisionNavTheme.kerb
    Text(stringResource(R.string.onboarding_welcome_body), style = leadStyle())
    Text(stringResource(R.string.onboarding_welcome_point_voice), style = MaterialTheme.typography.bodyLarge, color = k.onGroundDim)
    Spacer(Modifier.height(8.dp))
    BigActionButton(stringResource(R.string.action_continue), onNext, style = ActionStyle.PRIMARY)
}

/** The most important page in the app: what VisionNav does, and what it never does. */
@Composable
private fun SafetyPage(onNext: () -> Unit) {
    val k = VisionNavTheme.kerb
    Text(stringResource(R.string.onboarding_safety_describes), style = leadStyle())
    Text(
        stringResource(R.string.onboarding_safety_never),
        style = MaterialTheme.typography.displaySmall,
        color = k.amberInk,
    )
    Text(stringResource(R.string.onboarding_safety_miss), style = leadStyle())
    Text(stringResource(R.string.onboarding_safety_replace), style = leadStyle())
    Text(stringResource(R.string.safety_silence), style = MaterialTheme.typography.bodyLarge, color = k.onGroundDim)
    Spacer(Modifier.height(8.dp))
    BigActionButton(stringResource(R.string.onboarding_safety_ack), onNext, style = ActionStyle.PRIMARY)
}

@Composable
private fun HoldPhonePage(onNext: () -> Unit) {
    val k = VisionNavTheme.kerb
    Text(stringResource(R.string.onboarding_hold_body), style = leadStyle())
    listOf(R.string.hold_point_mount, R.string.hold_point_steady, R.string.hold_point_lens, R.string.hold_point_audio).forEach {
        Text(stringResource(it), style = MaterialTheme.typography.bodyLarge, color = k.onGroundDim)
    }
    Spacer(Modifier.height(8.dp))
    BigActionButton(stringResource(R.string.action_continue), onNext, style = ActionStyle.PRIMARY)
}

@Composable
private fun PermissionsPage(onNext: () -> Unit) {
    val k = VisionNavTheme.kerb
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    val items = remember { permissionItems() }
    val results = remember { mutableStateMapOf<String, Boolean>() }
    var requested by rememberSaveable { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        granted.forEach { (perm, ok) -> results[perm] = ok }
        requested++
    }
    val grantedFlags = items.map { item -> !inPreview && item.permissions.any { results[it] ?: context.isGranted(it) } }
    val allGranted = grantedFlags.all { it }
    val ask = { launcher.launch(items.flatMap { it.permissions }.toTypedArray()) }

    Text(stringResource(R.string.onboarding_permissions_body), style = leadStyle())
    KerbstoneGroup {
        items.forEachIndexed { i, item ->
            val granted = grantedFlags[i]
            // Status is shown once it is known; "Not allowed" is a different word and amber.
            val status = when {
                granted -> stringResource(R.string.perm_status_granted)
                requested > 0 -> stringResource(R.string.perm_status_denied)
                else -> null
            }
            PermissionStone(
                title = stringResource(item.titleRes),
                body = stringResource(item.bodyRes),
                status = status,
                problem = !granted && requested > 0,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    when {
        allGranted -> BigActionButton(stringResource(R.string.action_continue), onNext, style = ActionStyle.PRIMARY)
        requested == 0 -> {
            BigActionButton(stringResource(R.string.onboarding_grant), ask, style = ActionStyle.PRIMARY)
            BigActionButton(stringResource(R.string.action_not_now), onNext, style = ActionStyle.QUIET, minHeight = MinTouchTarget)
        }
        else -> {
            BigActionButton(stringResource(R.string.action_continue), onNext, style = ActionStyle.PRIMARY)
            BigActionButton(stringResource(R.string.onboarding_grant_again), ask, style = ActionStyle.QUIET, minHeight = MinTouchTarget)
        }
    }
    if (requested > 0 && !allGranted) {
        Text(stringResource(R.string.onboarding_permissions_later), style = MaterialTheme.typography.bodyLarge, color = k.onGroundDim)
    }
}

/** One permission on a kerbstone: name, what it is for, and its state as a word. */
@Composable
private fun PermissionStone(title: String, body: String, status: String?, problem: Boolean) {
    val k = VisionNavTheme.kerb
    Row(
        Modifier
            .bleed()
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .background(k.kerb)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = ScreenGutter + 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = k.onKerb)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = k.onKerb)
        }
        if (status != null) {
            Text(status, style = MaterialTheme.typography.labelLarge, color = if (problem) k.amberInk else k.onKerb)
        }
    }
}

@Composable
private fun ContactsPage(
    contacts: List<EmergencyContact>,
    onAddContact: (EmergencyContact) -> Unit,
    onRemoveContact: (EmergencyContact) -> Unit,
    onFinished: () -> Unit,
) {
    Text(stringResource(R.string.onboarding_contacts_body), style = leadStyle())
    EmergencyContactsEditor(contacts, onAddContact, onRemoveContact)
    Spacer(Modifier.height(8.dp))
    BigActionButton(stringResource(R.string.onboarding_finish), onFinished, style = ActionStyle.PRIMARY)
    if (contacts.isEmpty()) {
        BigActionButton(stringResource(R.string.onboarding_skip_contacts), onFinished, style = ActionStyle.QUIET, minHeight = MinTouchTarget)
    }
}

@Preview(name = "Onboarding, safety", showBackground = true, heightDp = 1400)
@Composable
private fun OnboardingSafetyPreview() {
    VisionNavTheme { OnboardingScreen(emptyList(), {}, {}, {}, startPage = 1) }
}

@Preview(name = "Onboarding, permissions, 200% font", showBackground = true, heightDp = 2800, fontScale = 2f)
@Composable
private fun OnboardingPermissionsPreview() {
    VisionNavTheme { OnboardingScreen(emptyList(), {}, {}, {}, startPage = 3) }
}
