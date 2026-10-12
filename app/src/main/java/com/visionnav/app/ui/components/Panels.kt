package com.visionnav.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.visionnav.app.R
import com.visionnav.app.settings.EmergencyContact
import com.visionnav.app.ui.contract.SosUi
import com.visionnav.app.ui.contract.TorchUi

/** Echo of the last utterance with a Repeat action. Not a live region: TTS already spoke it. */
@Composable
fun LastSpokenPanel(lastSpoken: String?, onRepeat: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedPanel(modifier) {
        Text(
            stringResource(R.string.last_spoken_heading),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Text(lastSpoken ?: stringResource(R.string.last_spoken_none), style = MaterialTheme.typography.bodyLarge)
        BigActionButton(
            text = stringResource(R.string.action_repeat),
            onClick = onRepeat,
            icon = Icons.Filled.Replay,
            minHeight = MinTouchTarget,
            enabled = lastSpoken != null,
        )
    }
}

/** Torch control; announces low light. Hidden when the camera has no torch. */
@Composable
fun TorchControl(torch: TorchUi, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    if (!torch.available) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (torch.lowLight && !torch.on) {
            Text(
                stringResource(R.string.torch_low_light),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        BigActionButton(
            text = stringResource(if (torch.on) R.string.torch_turn_off else R.string.torch_turn_on),
            onClick = onToggle,
            icon = if (torch.on) Icons.Filled.FlashlightOff else Icons.Filled.FlashlightOn,
            minHeight = MinTouchTarget,
            supportingText = "${stringResource(R.string.torch_label)}: ${stringResource(if (torch.on) R.string.state_on else R.string.state_off)}",
            stateDescription = stringResource(if (torch.on) R.string.state_on else R.string.state_off),
        )
    }
}

/**
 * SOS: long-press starts it (TalkBack: double-tap and hold, or the "Start SOS" action);
 * a single tap only explains how. A confirmation dialog follows; nothing is sent before it.
 */
@Composable
fun SosButton(onTrigger: () -> Unit, modifier: Modifier = Modifier) {
    var showHint by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LongPressActionButton(
            text = stringResource(R.string.sos_button),
            supportingText = stringResource(R.string.sos_button_hint),
            icon = Icons.Filled.Sos,
            onClick = { showHint = true },
            onLongClick = {
                showHint = false
                onTrigger()
            },
            onLongClickLabel = stringResource(R.string.sos_long_press_label),
        )
        if (showHint) {
            Text(
                stringResource(R.string.sos_tap_hint),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Shows the SOS confirmation / progress / result dialog for the current [SosUi]. */
@Composable
fun SosDialog(sos: SosUi, onConfirm: () -> Unit, onCancel: () -> Unit, onDismissResult: () -> Unit) {
    when (sos) {
        SosUi.Idle -> Unit
        is SosUi.AwaitingConfirmation -> VnDialog(
            title = stringResource(R.string.sos_confirm_title),
            body = sos.message,
            onDismissRequest = onCancel,
        ) {
            BigActionButton(
                text = stringResource(R.string.sos_confirm_send),
                onClick = onConfirm,
                icon = Icons.AutoMirrored.Filled.Send,
                style = ActionStyle.DANGER,
                minHeight = MinTouchTarget,
            )
            BigActionButton(
                text = stringResource(R.string.sos_confirm_cancel),
                onClick = onCancel,
                icon = Icons.Filled.Cancel,
                style = ActionStyle.PRIMARY,
                minHeight = MinTouchTarget,
            )
        }
        SosUi.Sending -> VnDialog(
            title = stringResource(R.string.sos_result_title),
            body = stringResource(R.string.sos_sending),
            onDismissRequest = {},
        ) {}
        is SosUi.Sent -> VnDialog(stringResource(R.string.sos_result_title), sos.message, onDismissResult) {
            BigActionButton(stringResource(R.string.action_ok), onDismissResult, style = ActionStyle.PRIMARY, minHeight = MinTouchTarget)
        }
        is SosUi.Failed -> VnDialog(stringResource(R.string.sos_failed_title), sos.message, onDismissResult) {
            BigActionButton(stringResource(R.string.action_ok), onDismissResult, style = ActionStyle.PRIMARY, minHeight = MinTouchTarget)
        }
    }
}

/** Dialog with a heading title, polite body and full-width large buttons stacked vertically. */
@Composable
fun VnDialog(
    title: String,
    body: String?,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit = {},
    buttons: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (body != null) {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                content()
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
            }
        },
        confirmButton = {},
    )
}

/** Text-input dialog (place names). Validates non-blank input with an announced error. */
@Composable
fun NameInputDialog(
    title: String,
    initial: String,
    label: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    var showError by rememberSaveable { mutableStateOf(false) }
    val errorText = stringResource(R.string.places_name_error)
    VnDialog(
        title = title,
        body = null,
        onDismissRequest = onDismiss,
        content = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; showError = false },
                label = { Text(label) },
                singleLine = true,
                isError = showError,
                supportingText = if (showError) ({ Text(errorText) }) else null,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { if (showError) error(errorText) },
            )
        },
    ) {
        BigActionButton(
            text = confirmText,
            onClick = { if (value.isBlank()) showError = true else onConfirm(value.trim()) },
            style = ActionStyle.PRIMARY,
            minHeight = MinTouchTarget,
        )
        BigActionButton(stringResource(R.string.action_cancel), onDismiss, minHeight = MinTouchTarget)
    }
}

/** List + add form for emergency contacts. Used in onboarding and settings. */
@Composable
fun EmergencyContactsEditor(
    contacts: List<EmergencyContact>,
    onAdd: (EmergencyContact) -> Unit,
    onRemove: (EmergencyContact) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf(false) }
    var phoneError by rememberSaveable { mutableStateOf(false) }
    var lastAdded by rememberSaveable { mutableStateOf<String?>(null) }
    val nameErrorText = stringResource(R.string.contact_error_name)
    val phoneErrorText = stringResource(R.string.contact_error_phone)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (contacts.isEmpty()) {
            BodyText(stringResource(R.string.contacts_empty))
        }
        contacts.forEach { contact ->
            OutlinedPanel {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                    Column(Modifier.weight(1f)) {
                        Text(contact.name, style = MaterialTheme.typography.titleMedium)
                        Text(contact.phone, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                BigActionButton(
                    text = stringResource(R.string.contact_remove, contact.name),
                    onClick = { onRemove(contact) },
                    icon = Icons.Filled.Cancel,
                    minHeight = MinTouchTarget,
                    style = ActionStyle.QUIET,
                )
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; nameError = false },
            label = { Text(stringResource(R.string.contact_name_label)) },
            singleLine = true,
            isError = nameError,
            supportingText = if (nameError) ({ Text(nameErrorText) }) else null,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { if (nameError) error(nameErrorText) },
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it; phoneError = false },
            label = { Text(stringResource(R.string.contact_phone_label)) },
            singleLine = true,
            isError = phoneError,
            supportingText = if (phoneError) ({ Text(phoneErrorText) }) else null,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { if (phoneError) error(phoneErrorText) },
        )
        BigActionButton(
            text = stringResource(R.string.contact_add),
            onClick = {
                val n = name.trim()
                val p = phone.trim()
                nameError = n.isEmpty()
                phoneError = p.count { it.isDigit() } < 3
                if (!nameError && !phoneError) {
                    onAdd(EmergencyContact(n, p))
                    lastAdded = n
                    name = ""
                    phone = ""
                }
            },
            icon = Icons.Filled.PersonAdd,
            minHeight = MinTouchTarget,
        )
        lastAdded?.let {
            Text(
                stringResource(R.string.contact_added, it),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
