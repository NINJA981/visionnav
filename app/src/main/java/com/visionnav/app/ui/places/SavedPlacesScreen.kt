package com.visionnav.app.ui.places

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.visionnav.app.R
import com.visionnav.app.ui.components.ActionStyle
import com.visionnav.app.ui.components.AlertArea
import com.visionnav.app.ui.components.BigActionButton
import com.visionnav.app.ui.components.BodyText
import com.visionnav.app.ui.components.MinTouchTarget
import com.visionnav.app.ui.components.NameInputDialog
import com.visionnav.app.ui.components.OutlinedPanel
import com.visionnav.app.ui.components.VnDialog
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.SavedPlaceUi
import com.visionnav.app.ui.contract.UiAction
import com.visionnav.app.ui.preview.PreviewSamples
import com.visionnav.app.ui.theme.VisionNavTheme

@Composable
fun SavedPlacesScreen(
    state: AppUiState,
    onAction: (UiAction) -> Unit,
    onNavigateTo: (SavedPlaceUi) -> Unit,
    onBack: () -> Unit,
) {
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }

    VnScreen(
        title = stringResource(R.string.places_title),
        onBack = onBack,
        pinned = { AlertArea(state, { onAction(UiAction.DismissAlert(it)) }, { onAction(UiAction.DismissError(it)) }) },
    ) {
        BigActionButton(
            text = stringResource(R.string.places_save_current),
            supportingText = stringResource(R.string.places_save_current_hint),
            onClick = { showSaveDialog = true },
            icon = Icons.Filled.AddLocationAlt,
            style = ActionStyle.PRIMARY,
        )
        if (state.savedPlaces.isEmpty()) {
            BodyText(stringResource(R.string.places_empty))
        }
        state.savedPlaces.forEach { saved ->
            OutlinedPanel {
                Text(
                    saved.name,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                saved.place.address?.let { BodyText(it) }
                BigActionButton(
                    text = stringResource(R.string.places_navigate),
                    onClick = { onNavigateTo(saved) },
                    icon = Icons.Filled.Navigation,
                    minHeight = MinTouchTarget,
                    onClickLabel = stringResource(R.string.places_navigate_to, saved.name),
                )
                // Labels include the place name so each button is unambiguous out of context.
                BigActionButton(
                    text = stringResource(R.string.places_rename_item, saved.name),
                    onClick = { renameId = saved.id },
                    icon = Icons.Filled.Edit,
                    minHeight = MinTouchTarget,
                    style = ActionStyle.QUIET,
                )
                BigActionButton(
                    text = stringResource(R.string.places_delete_item, saved.name),
                    onClick = { deleteId = saved.id },
                    icon = Icons.Filled.Delete,
                    minHeight = MinTouchTarget,
                    style = ActionStyle.QUIET,
                )
            }
        }
    }

    if (showSaveDialog) {
        NameInputDialog(
            title = stringResource(R.string.places_save_current),
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
                icon = Icons.Filled.Delete,
                style = ActionStyle.DANGER,
                minHeight = MinTouchTarget,
            )
            BigActionButton(stringResource(R.string.action_cancel), { deleteId = null }, style = ActionStyle.PRIMARY, minHeight = MinTouchTarget)
        }
    }
}

@Preview(name = "Saved places", showBackground = true, heightDp = 1400)
@Composable
private fun SavedPlacesPreview() {
    VisionNavTheme { SavedPlacesScreen(PreviewSamples.idle, {}, {}, {}) }
}
