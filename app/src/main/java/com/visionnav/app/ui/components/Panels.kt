package com.visionnav.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.visionnav.app.R
import com.visionnav.app.settings.EmergencyContact
import com.visionnav.app.ui.contract.SosUi
import com.visionnav.app.ui.contract.TorchUi
import com.visionnav.app.ui.aura.ScreenGutter
import com.visionnav.app.ui.theme.AuraSurface
import com.visionnav.app.ui.theme.AuraText
import com.visionnav.app.ui.theme.VisionNavTheme

/** Echo of the last utterance as a quiet kerbstone that repeats it. Not a live region: TTS already spoke it. */
@Composable
fun LastSpokenPanel(lastSpoken: String?, onRepeat: () -> Unit, modifier: Modifier = Modifier) {
    BigActionButton(
        text = stringResource(R.string.action_repeat),
        supportingText = lastSpoken ?: stringResource(R.string.last_spoken_none),
        onClick = onRepeat,
        modifier = modifier,
        style = ActionStyle.QUIET,
        minHeight = MinTouchTarget,
        enabled = lastSpoken != null,
    )
}

/** Torch as a quiet kerbstone whose second line states what the torch is doing now. Hidden without a torch. */
@Composable
fun TorchControl(torch: TorchUi, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    if (!torch.available) return
    val aura = VisionNavTheme.aura
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (torch.lowLight && !torch.on) {
            Text(
                stringResource(R.string.torch_low_light),
                style = MaterialTheme.typography.bodyLarge,
                color = aura.attention,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        BigActionButton(
            text = stringResource(if (torch.on) R.string.torch_turn_off else R.string.torch_turn_on),
            supportingText = stringResource(if (torch.on) R.string.torch_is_on else R.string.torch_is_off),
            onClick = onToggle,
            style = ActionStyle.QUIET,
            minHeight = MinTouchTarget,
            stateDescription = stringResource(if (torch.on) R.string.state_on else R.string.state_off),
        )
    }
}

/**
 * SOS: long-press starts it (TalkBack: double-tap and hold, or the "Start SOS" action);
 * a single tap only explains how. A confirmation follows; nothing is sent before it.
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

/**
 * The SOS confirmation / progress / result for the current [SosUi], as a full-screen red
 * takeover: nothing else on screen competes with it, and TalkBack treats it as a modal pane.
 * Back cancels the confirmation; nothing is sent before "Send" is pressed.
 */
@Composable
fun SosDialog(sos: SosUi, onConfirm: () -> Unit, onCancel: () -> Unit, onDismissResult: () -> Unit) {
    if (sos == SosUi.Idle) return
    val dismiss: () -> Unit = when (sos) {
        is SosUi.AwaitingConfirmation -> onCancel
        SosUi.Sending -> ({})
        else -> onDismissResult
    }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        SosTakeover(sos, onConfirm, onCancel, onDismissResult)
    }
}

/** The red SOS surface itself (separate from the window so it can be previewed). */
@Composable
fun SosTakeover(sos: SosUi, onConfirm: () -> Unit, onCancel: () -> Unit, onDismissResult: () -> Unit) {
    val aura = VisionNavTheme.aura
    val pane = when (sos) {
        is SosUi.AwaitingConfirmation -> stringResource(R.string.sos_confirm_title)
        SosUi.Sending -> stringResource(R.string.sos_sending_label)
        is SosUi.Failed -> stringResource(R.string.sos_failed_title)
        else -> stringResource(R.string.sos_result_title)
    }
    Surface(Modifier.fillMaxSize(), color = aura.risk, contentColor = aura.onRisk) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .semantics { paneTitle = pane }
                .verticalScroll(rememberScrollState())
                .padding(start = ScreenGutter, end = ScreenGutter, top = 32.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (sos) {
                SosUi.Idle -> Unit
                is SosUi.AwaitingConfirmation -> {
                    Text(
                        stringResource(R.string.sos_confirm_title),
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        if (sos.contactCount > 0) {
                            pluralStringResource(R.plurals.sos_confirm_recipients, sos.contactCount, sos.contactCount)
                        } else {
                            stringResource(R.string.sos_confirm_no_contacts)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    SosAction(
                        text = stringResource(R.string.sos_confirm_send),
                        onClick = onConfirm,
                        onClickLabel = stringResource(R.string.sos_send_hint),
                        fill = AuraSurface,
                        ink = AuraText,
                        height = 128.dp,
                    )
                    SosAction(
                        text = stringResource(R.string.sos_confirm_cancel),
                        onClick = onCancel,
                        fill = Color.Transparent,
                        ink = AuraSurface,
                        height = PrimaryTouchTarget,
                    )
                }
                SosUi.Sending -> {
                    Text(
                        stringResource(R.string.sos_sending_label),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.sos_sending),
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                is SosUi.Sent -> SosResult(stringResource(R.string.sos_result_title), sos.message, onDismissResult)
                is SosUi.Failed -> SosResult(stringResource(R.string.sos_failed_title), sos.message, onDismissResult)
            }
        }
    }
}

@Composable
private fun SosResult(label: String, message: String, onClose: () -> Unit) {
    Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Text(
        message,
        style = MaterialTheme.typography.displaySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    Spacer(Modifier.height(12.dp))
    SosAction(
        text = stringResource(R.string.action_close),
        onClick = onClose,
        fill = Color.Transparent,
        ink = AuraSurface,
        height = PrimaryTouchTarget,
    )
}

/**
 * One SOS action on the risk surface. Colours are fixed (not themed) because the red surface is the
 * same in both themes: a solid white action for "Send", an outlined one for "Cancel" (AURA §28).
 */
@Composable
private fun SosAction(
    text: String,
    onClick: () -> Unit,
    fill: Color,
    ink: Color,
    height: Dp,
    onClickLabel: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val outlined = fill == Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(shape)
            .background(if (pressed) (if (outlined) AuraSurface.copy(alpha = 0.18f) else fill.copy(alpha = 0.85f)) else fill)
            .then(if (outlined) Modifier.border(2.dp, AuraSurface, shape) else Modifier)
            .then(if (focused) Modifier.border(4.dp, AuraSurface, shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.headlineLarge, color = ink)
    }
}

/**
 * Dialog as a held object: a rounded slab of ground with a thin edge, heading title, polite
 * body and large actions stacked below. Scrolls, so 200 % text never clips.
 */
@Composable
fun VnDialog(
    title: String,
    body: String?,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit = {},
    buttons: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val aura = VisionNavTheme.aura
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 24.dp)
                .clip(shape)
                .background(aura.background)
                .border(2.dp, aura.textSecondary, shape)
                .semantics { paneTitle = title }
                .verticalScroll(rememberScrollState())
                .padding(start = ScreenGutter, end = ScreenGutter, top = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = aura.text,
                modifier = Modifier.semantics { heading() },
            )
            if (body != null) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = aura.text,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            content()
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
        }
    }
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
    val confirm: () -> Unit = {
        if (value.isBlank()) {
            showError = true
        } else {
            onConfirm(value.trim())
        }
    }
    VnDialog(
        title = title,
        body = null,
        onDismissRequest = onDismiss,
        content = {
            AuraTextField(
                value = value,
                onValueChange = {
                    value = it
                    showError = false
                },
                label = label,
                errorText = if (showError) errorText else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
            )
        },
    ) {
        BigActionButton(text = confirmText, onClick = confirm, style = ActionStyle.PRIMARY)
        BigActionButton(stringResource(R.string.action_cancel), onDismiss, style = ActionStyle.QUIET, minHeight = MinTouchTarget)
    }
}

/** Contacts as kerbstones with a "Remove" notch, then the add form. Used in onboarding and settings. */
@Composable
fun EmergencyContactsEditor(
    contacts: List<EmergencyContact>,
    onAdd: (EmergencyContact) -> Unit,
    onRemove: (EmergencyContact) -> Unit,
    modifier: Modifier = Modifier,
) {
    val aura = VisionNavTheme.aura
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var nameError by rememberSaveable { mutableStateOf(false) }
    var phoneError by rememberSaveable { mutableStateOf(false) }
    var lastAdded by rememberSaveable { mutableStateOf<String?>(null) }
    val add: () -> Unit = {
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
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (contacts.isEmpty()) {
            Text(stringResource(R.string.contacts_empty), style = MaterialTheme.typography.bodyLarge, color = aura.textSecondary)
        } else {
            ListGroup {
                contacts.forEach { contact ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .heightIn(min = MinTouchTarget)
                            .background(aura.surface)
                            .padding(start = ScreenGutter + 8.dp, end = ScreenGutter, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                            Text(contact.name, style = MaterialTheme.typography.titleMedium, color = aura.text)
                            Text(contact.phone, style = MaterialTheme.typography.bodyLarge, color = aura.text)
                        }
                        InsetAction(
                            text = stringResource(R.string.action_remove),
                            contentDescription = stringResource(R.string.contact_remove, contact.name),
                            onClick = { onRemove(contact) },
                        )
                    }
                }
            }
        }
        AuraTextField(
            value = name,
            onValueChange = {
                name = it
                nameError = false
            },
            label = stringResource(R.string.contact_name_label),
            errorText = if (nameError) stringResource(R.string.contact_error_name) else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        )
        AuraTextField(
            value = phone,
            onValueChange = {
                phone = it
                phoneError = false
            },
            label = stringResource(R.string.contact_phone_label),
            errorText = if (phoneError) stringResource(R.string.contact_error_phone) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
        )
        BigActionButton(text = stringResource(R.string.contact_add), onClick = add)
        lastAdded?.let {
            Text(
                stringResource(R.string.contact_added, it),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Preview(name = "SOS confirm, 200% font", showBackground = true, heightDp = 1400, fontScale = 2f)
@Composable
private fun SosConfirmPreview() {
    VisionNavTheme {
        SosTakeover(SosUi.AwaitingConfirmation(2, "Send?"), {}, {}, {})
    }
}

@Preview(name = "SOS failed", showBackground = true, heightDp = 900)
@Composable
private fun SosFailedPreview() {
    VisionNavTheme {
        SosTakeover(SosUi.Failed("Emergency message failed to send."), {}, {}, {})
    }
}
