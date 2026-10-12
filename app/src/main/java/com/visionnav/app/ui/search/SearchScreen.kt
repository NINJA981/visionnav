package com.visionnav.app.ui.search

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.visionnav.app.R
import com.visionnav.app.settings.DistanceUnits
import com.visionnav.app.ui.components.distanceText
import com.visionnav.app.ui.components.ActionStyle
import com.visionnav.app.ui.components.AlertArea
import com.visionnav.app.ui.components.BigActionButton
import com.visionnav.app.ui.components.BodyText
import com.visionnav.app.ui.components.MinTouchTarget
import com.visionnav.app.ui.components.SectionHeading
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.UiAction
import com.visionnav.app.ui.preview.PreviewSamples
import com.visionnav.app.ui.theme.VisionNavTheme
import com.visionnav.core.navigation.Place

@Composable
fun SearchScreen(
    state: AppUiState,
    onAction: (UiAction) -> Unit,
    onPlaceChosen: (Place) -> Unit,
    onBack: () -> Unit,
    units: DistanceUnits = DistanceUnits.METRIC,
) {
    val search = state.search
    var query by rememberSaveable { mutableStateOf(search.query) }
    val focusManager = LocalFocusManager.current
    val submit = {
        focusManager.clearFocus()
        onAction(UiAction.Search(query))
    }
    // A destination recognised by voice (engine-side search) fills the field.
    LaunchedEffect(search.query) {
        if (search.query.isNotBlank()) query = search.query
    }

    VnScreen(
        title = stringResource(R.string.search_title),
        onBack = onBack,
        pinned = { AlertArea(state, { onAction(UiAction.DismissAlert(it)) }, { onAction(UiAction.DismissError(it)) }) },
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_field_label)) },
            placeholder = { Text(stringResource(R.string.search_field_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(32.dp)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget),
        )
        BigActionButton(
            text = stringResource(R.string.search_action),
            onClick = submit,
            icon = Icons.Filled.Search,
            style = ActionStyle.PRIMARY,
            minHeight = MinTouchTarget,
        )
        BigActionButton(
            text = if (state.isListening) stringResource(R.string.home_listening) else stringResource(R.string.search_voice),
            onClick = { onAction(if (state.isListening) UiAction.StopVoiceCommand else UiAction.StartVoiceCommand) },
            icon = Icons.Filled.Mic,
            minHeight = MinTouchTarget,
            stateDescription = if (state.isListening) stringResource(R.string.home_listening_state) else null,
        )
        if (query.isNotEmpty() || search.hasSearched) {
            BigActionButton(
                text = stringResource(R.string.search_clear),
                onClick = {
                    query = ""
                    onAction(UiAction.ClearSearch)
                },
                icon = Icons.Filled.Clear,
                minHeight = MinTouchTarget,
                style = ActionStyle.QUIET,
            )
        }

        // Result summary is a polite live region so TalkBack users hear the outcome.
        val summary: String? = when {
            search.isLoading -> stringResource(R.string.search_loading)
            search.error != null -> search.error
            search.hasSearched && search.results.isEmpty() -> stringResource(R.string.search_no_results, search.query)
            search.hasSearched -> pluralStringResource(R.plurals.search_results_count, search.results.size, search.results.size)
            else -> null
        }
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.titleMedium,
                color = if (search.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (search.results.isNotEmpty()) {
            SectionHeading(stringResource(R.string.search_results_heading))
            search.results.forEach { place ->
                PlaceRow(place, units = units, onClick = {
                    onAction(UiAction.SelectPlace(place))
                    onPlaceChosen(place)
                })
            }
        }

        if (state.savedPlaces.isNotEmpty()) {
            SectionHeading(stringResource(R.string.home_saved_places))
            state.savedPlaces.forEach { saved ->
                PlaceRow(
                    place = saved.place.copy(name = saved.name),
                    units = units,
                    icon = Icons.Filled.Bookmark,
                    onClick = {
                        onAction(UiAction.SelectPlace(saved.place))
                        onPlaceChosen(saved.place)
                    },
                )
            }
        }
    }
}

/** One result: name, address, distance; a single Role.Button node that opens the route preview. */
@Composable
fun PlaceRow(
    place: Place,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    units: DistanceUnits = DistanceUnits.METRIC,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Place,
) {
    val shape = MaterialTheme.shapes.large
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(shape)
            .border(2.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.search_result_action), onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(place.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            place.address?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            place.distanceM?.let {
                Text(
                    stringResource(R.string.search_result_distance, distanceText(it, units)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(32.dp))
    }
}

/** Route summary before starting. Start is the most prominent action. */
@Composable
fun RoutePreviewScreen(
    state: AppUiState,
    onAction: (UiAction) -> Unit,
    onBack: () -> Unit,
) {
    val search = state.search
    VnScreen(
        title = stringResource(R.string.preview_title),
        onBack = onBack,
        pinned = { AlertArea(state, { onAction(UiAction.DismissAlert(it)) }, { onAction(UiAction.DismissError(it)) }) },
    ) {
        val place = search.selectedPlace
        if (place == null) {
            BodyText(stringResource(R.string.preview_no_selection))
            BigActionButton(stringResource(R.string.preview_choose_again), onBack, minHeight = MinTouchTarget)
            return@VnScreen
        }
        Text(
            place.name,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { heading() },
        )
        place.address?.let { BodyText(it) }

        val preview = search.preview
        when {
            search.isPreviewLoading -> BodyText(stringResource(R.string.preview_loading), polite = true)
            search.previewError != null -> Text(
                search.previewError,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            preview != null -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.preview_length, preview.lengthText), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.preview_duration, preview.durationText), style = MaterialTheme.typography.titleLarge)
                    preview.crossingsText?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                }
                SectionHeading(stringResource(R.string.preview_first))
                Text(preview.firstInstruction, style = MaterialTheme.typography.headlineSmall)
                BodyText(stringResource(R.string.preview_crossing_note))
                BigActionButton(
                    text = stringResource(R.string.preview_start),
                    onClick = { onAction(UiAction.StartNavigation) },
                    icon = Icons.Filled.Place,
                    style = ActionStyle.PRIMARY,
                    minHeight = 120.dp,
                )
            }
        }
        BigActionButton(
            text = stringResource(R.string.preview_choose_again),
            onClick = onBack,
            minHeight = MinTouchTarget,
            style = ActionStyle.QUIET,
        )
    }
}

@Preview(name = "Search results", showBackground = true, heightDp = 1600)
@Composable
private fun SearchPreview() {
    VisionNavTheme { SearchScreen(PreviewSamples.searchResults, {}, {}, {}) }
}

@Preview(name = "Route preview", showBackground = true, heightDp = 1200)
@Composable
private fun RoutePreviewPreview() {
    VisionNavTheme { RoutePreviewScreen(PreviewSamples.routePreview, {}, {}) }
}
