package com.visionnav.app.ui.search

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.visionnav.app.R
import com.visionnav.app.settings.DistanceUnits
import com.visionnav.app.ui.components.ActionStyle
import com.visionnav.app.ui.components.AlertArea
import com.visionnav.app.ui.components.BigActionButton
import com.visionnav.app.ui.components.AuraTextField
import com.visionnav.app.ui.components.ListGroup
import com.visionnav.app.ui.components.MinTouchTarget
import com.visionnav.app.ui.components.SectionHeading
import com.visionnav.app.ui.components.VnScreen
import com.visionnav.app.ui.components.distanceText
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.UiAction
import com.visionnav.app.ui.preview.PreviewSamples
import com.visionnav.app.ui.theme.VisionNavTheme
import com.visionnav.core.navigation.Place

/**
 * Where to: the field is a held object, Search is the one amber action, results are zebra bars
 * (name large, address and distance below). Saved places follow as quiet kerbstones.
 */
@Composable
fun SearchScreen(
    state: AppUiState,
    onAction: (UiAction) -> Unit,
    onPlaceChosen: (Place) -> Unit,
    onBack: () -> Unit,
    units: DistanceUnits = DistanceUnits.METRIC,
) {
    val aura = VisionNavTheme.aura
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
        AuraTextField(
            value = query,
            onValueChange = { query = it },
            label = stringResource(R.string.search_field_label),
            placeholder = stringResource(R.string.search_field_hint),
            large = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
        )
        BigActionButton(text = stringResource(R.string.search_action), onClick = submit, style = ActionStyle.PRIMARY)
        ListGroup {
            BigActionButton(
                text = stringResource(if (state.isListening) R.string.home_listening else R.string.search_voice),
                onClick = { onAction(if (state.isListening) UiAction.StopVoiceCommand else UiAction.StartVoiceCommand) },
                style = ActionStyle.QUIET,
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
                    style = ActionStyle.QUIET,
                    minHeight = MinTouchTarget,
                )
            }
        }

        // The outcome is a polite live region; with results it also heads the list.
        val summary: String? = when {
            search.isLoading -> stringResource(R.string.search_loading)
            search.error != null -> search.error
            search.hasSearched && search.results.isEmpty() -> stringResource(R.string.search_no_results, search.query)
            search.hasSearched -> pluralStringResource(R.plurals.search_results_count, search.results.size, search.results.size)
            else -> null
        }
        if (summary != null) {
            val problem = search.error != null || (search.hasSearched && search.results.isEmpty() && !search.isLoading)
            Text(
                summary,
                style = MaterialTheme.typography.headlineSmall,
                color = if (problem) aura.attention else aura.text,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    if (search.results.isNotEmpty()) heading()
                },
            )
        }

        search.results.forEach { place ->
            PlaceRow(place, units = units, onClick = {
                onAction(UiAction.SelectPlace(place))
                onPlaceChosen(place)
            })
        }

        if (state.savedPlaces.isNotEmpty()) {
            SectionHeading(stringResource(R.string.home_saved_places))
            ListGroup {
                state.savedPlaces.forEach { saved ->
                    BigActionButton(
                        text = saved.name,
                        supportingText = saved.place.address,
                        onClick = {
                            onAction(UiAction.SelectPlace(saved.place))
                            onPlaceChosen(saved.place)
                        },
                        onClickLabel = stringResource(R.string.search_result_action),
                        style = ActionStyle.QUIET,
                        minHeight = MinTouchTarget,
                    )
                }
            }
        }
    }
}

/** One result as a zebra bar: the name large, address and distance on the line below. One TalkBack node. */
@Composable
fun PlaceRow(
    place: Place,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    units: DistanceUnits = DistanceUnits.METRIC,
) {
    val distance = place.distanceM?.let { stringResource(R.string.search_result_distance, distanceText(it, units)) }
    BigActionButton(
        text = place.name,
        supportingText = listOfNotNull(place.address, distance).joinToString("\n").ifBlank { null },
        onClick = onClick,
        modifier = modifier,
        onClickLabel = stringResource(R.string.search_result_action),
    )
}

/** Ends a phrase from the engine with a full stop if it has none, so two phrases read as sentences. */
private fun sentence(text: String): String = text.trim().let { if (it.endsWith('.') || it.endsWith('?') || it.endsWith('!')) it else "$it." }

/** True when the engine's crossing text would only say there are none; we never say "no crossings". */
private fun isNoneText(text: String): Boolean = text.trim().lowercase().let { it.startsWith("0 ") || it.startsWith("no ") }

/**
 * The route before starting: destination in display type, length and time as one sentence, the
 * first instruction, mapped crossings only when there are some, then one amber "Start walking".
 */
@Composable
fun RoutePreviewScreen(
    state: AppUiState,
    onAction: (UiAction) -> Unit,
    onBack: () -> Unit,
) {
    val aura = VisionNavTheme.aura
    val search = state.search
    val lead: TextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 26.sp, lineHeight = 36.sp)
    VnScreen(
        title = stringResource(R.string.preview_title),
        onBack = onBack,
        pinned = { AlertArea(state, { onAction(UiAction.DismissAlert(it)) }, { onAction(UiAction.DismissError(it)) }) },
    ) {
        val place = search.selectedPlace
        if (place == null) {
            Text(stringResource(R.string.preview_no_selection), style = lead)
            BigActionButton(stringResource(R.string.preview_choose_again), onBack, style = ActionStyle.QUIET, minHeight = MinTouchTarget)
            return@VnScreen
        }
        Text(
            place.name,
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.semantics { heading() },
        )
        place.address?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = aura.textSecondary) }

        val preview = search.preview
        when {
            search.isPreviewLoading -> Text(
                stringResource(R.string.preview_loading),
                style = lead,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            search.previewError != null -> Text(
                search.previewError,
                style = MaterialTheme.typography.headlineSmall,
                color = aura.attention,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            preview != null -> {
                Text(
                    stringResource(R.string.preview_summary, preview.lengthText, sentence(preview.durationText)),
                    style = lead,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(sentence(preview.firstInstruction), style = MaterialTheme.typography.headlineSmall)
                preview.crossingsText?.takeUnless { isNoneText(it) }?.let {
                    Text(sentence(it), style = MaterialTheme.typography.titleLarge)
                }
                Text(stringResource(R.string.preview_crossing_note), style = MaterialTheme.typography.bodyLarge, color = aura.textSecondary)
                BigActionButton(
                    text = stringResource(R.string.preview_start),
                    onClick = { onAction(UiAction.StartNavigation) },
                    onClickLabel = stringResource(R.string.preview_start_hint),
                    style = ActionStyle.PRIMARY,
                )
            }
        }
        BigActionButton(
            text = stringResource(R.string.preview_choose_again),
            onClick = onBack,
            style = ActionStyle.QUIET,
            minHeight = MinTouchTarget,
        )
    }
}

@Preview(name = "Search results", showBackground = true, heightDp = 1800)
@Composable
private fun SearchPreview() {
    VisionNavTheme { SearchScreen(PreviewSamples.searchResults, {}, {}, {}) }
}

@Preview(name = "Route preview, 200% font", showBackground = true, heightDp = 2400, fontScale = 2f)
@Composable
private fun RoutePreviewPreview() {
    VisionNavTheme { RoutePreviewScreen(PreviewSamples.routePreview, {}, {}) }
}
