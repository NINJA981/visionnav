package com.visionnav.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visionnav.app.R
import com.visionnav.app.ui.components.ActionStyle
import com.visionnav.app.ui.components.AlertArea
import com.visionnav.app.ui.components.BigActionButton
import com.visionnav.app.ui.components.MinTouchTarget
import com.visionnav.app.ui.components.NameInputDialog
import com.visionnav.app.ui.components.VnDialog
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.SavedPlaceUi
import com.visionnav.app.ui.contract.UiAction
import com.visionnav.app.ui.kerb.ScreenGutter
import com.visionnav.app.ui.kerb.bleed
import com.visionnav.app.ui.preview.PreviewSamples
import com.visionnav.app.ui.theme.VisionNavTheme

/**
 * Saved places as zebra bars (tap to plan a route), each with quiet Rename / Delete stones
 * under it. With nothing saved, the screen is an invitation: one amber action to save the spot
 * you are standing in.
 */
@Composable
fun SavedPlacesScreen(
    state: AppUiState,
    onAction: (UiAction) -> Unit,
    onNavigateTo: (SavedPlaceUi) -> Unit,
    onBack: () -> Unit,
) {
    val k = VisionNavTheme.kerb
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val lead = MaterialTheme.typography.bodyLarge.copy(fontSize = 26.sp, lineHeight = 36.sp)

    VnScreen(
        title = stringResource(R.string.places_title),
        onBack = onBack,
        pinned = { AlertArea(state, { onAction(UiAction.DismissAlert(it)) }, { onAction(UiAction.DismissError(it)) }) },
    ) {
        if (state.savedPlaces.isEmpty()) {
            Text(stringResource(R.string.places_empty), style = lead)
            BigActionButton(
                text = stringResource(R.string.places_save_current),
                onClick = { showSaveDialog = true },
                onClickLabel = stringResource(R.string.places_save_current_hint),
                style = ActionStyle.PRIMARY,
            )
        } else {
            state.savedPlaces.forEach { saved ->
                SavedPlaceItem(
                    saved = saved,
                    onOpen = { onNavigateTo(saved) },
                    onRename = { renameId = saved.id },
                    onDelete = { deleteId = saved.id },
                )
            }
            Text(stringResource(R.string.places_save_more), style = MaterialTheme.typography.bodyLarge, color = k.onGroundDim)
            BigActionButton(
                text = stringResource(R.string.places_save_current),
                onClick = { showSaveDialog = true },
                onClickLabel = stringResource(R.string.places_save_current_hint),
                style = ActionStyle.QUIET,
                minHeight = MinTouchTarget,
            )
        }
    }

    if (showSaveDialog) {
        NameInputDialog(
            title = stringResource(R.string.places_save_title),
            initial = "",
            label = stringResource(R.string.places_name_label),
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                showSaveDialog = false
                onAction(UiAction.SavePlace(it))
            },
            onDismiss = { showSaveDialog = false },
        )
    }
    state.savedPlaces.firstOrNull { it.id == renameId }?.let { target ->
        NameInputDialog(
            title = stringResource(R.string.places_rename_title, target.name),
            initial = target.name,
            label = stringResource(R.string.places_name_label),
            confirmText = stringResource(R.string.action_save),
            onConfirm = {
                renameId = null
                onAction(UiAction.RenameSavedPlace(target.id, it))
            },
            onDismiss = { renameId = null },
        )
    }
    state.savedPlaces.firstOrNull { it.id == deleteId }?.let { target ->
        VnDialog(
            title = stringResource(R.string.places_delete_title, target.name),
            body = stringResource(R.string.places_delete_body),
            onDismissRequest = { deleteId = null },
        ) {
            BigActionButton(
                text = stringResource(R.string.action_delete),
                onClick = {
                    deleteId = null
                    onAction(UiAction.DeleteSavedPlace(target.id))
                },
                style = ActionStyle.DANGER,
                minHeight = MinTouchTarget,
            )
            BigActionButton(stringResource(R.string.action_cancel), { deleteId = null }, style = ActionStyle.QUIET, minHeight = MinTouchTarget)
        }
    }
}

/**
 * One place: a zebra bar, and under it two quiet half-width stones. TalkBack users also get
 * Rename and Delete as custom actions on the bar, so they need not swipe past them.
 */
@Composable
private fun SavedPlaceItem(saved: SavedPlaceUi, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val renameLabel = stringResource(R.string.places_rename_item, saved.name)
    val deleteLabel = stringResource(R.string.places_delete_item, saved.name)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BigActionButton(
            text = saved.name,
            supportingText = saved.place.address,
            onClick = onOpen,
            onClickLabel = stringResource(R.string.places_navigate_to, saved.name),
            modifier = Modifier.semantics {
                customActions = listOf(
                    CustomAccessibilityAction(renameLabel) { onRename(); true },
                    CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                )
            },
        )
        Row(
            Modifier
                .bleed()
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            QuietHalf(stringResource(R.string.action_rename), renameLabel, onRename, Modifier.weight(1f))
            QuietHalf(stringResource(R.string.action_delete), deleteLabel, onDelete, Modifier.weight(1f))
        }
    }
}

/** Half-width kerbstone action. The spoken label names the place, so it is clear out of context. */
@Composable
private fun QuietHalf(text: String, spoken: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val k = VisionNavTheme.kerb
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .fillMaxHeight()
            .heightIn(min = MinTouchTarget)
            .background(if (pressed) k.amber else k.kerb)
            .then(if (focused) Modifier.border(6.dp, k.amberInk) else Modifier)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(horizontal = ScreenGutter + 8.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (pressed) k.onAmber else k.onKerb)
    }
}

@Preview(name = "Saved places", showBackground = true, heightDp = 1400)
@Composable
private fun SavedPlacesPreview() {
    VisionNavTheme { SavedPlacesScreen(PreviewSamples.idle, {}, {}, {}) }
}

@Preview(name = "Saved places, empty", showBackground = true, heightDp = 900)
@Composable
private fun SavedPlacesEmptyPreview() {
    VisionNavTheme { SavedPlacesScreen(PreviewSamples.idle.copy(savedPlaces = emptyList()), {}, {}, {}) }
}
