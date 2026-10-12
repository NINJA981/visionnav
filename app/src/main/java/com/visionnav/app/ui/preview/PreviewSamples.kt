package com.visionnav.app.ui.preview

import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.CrossingUi
import com.visionnav.app.ui.contract.FakeAppController
import com.visionnav.app.ui.contract.FakeSampleData
import com.visionnav.app.ui.contract.NavigationUi
import com.visionnav.app.ui.contract.RoutePreviewUi
import com.visionnav.app.ui.contract.SafetyAlertUi
import com.visionnav.app.ui.contract.SearchUi
import com.visionnav.app.ui.contract.SessionMode
import com.visionnav.app.ui.contract.StatusLevel
import com.visionnav.app.ui.contract.TorchUi
import com.visionnav.core.crossing.CrossingAssistState
import com.visionnav.core.crossing.CrossingStage
import com.visionnav.core.safety.SafetySeverity

/** Static states for Compose previews. */
object PreviewSamples {

    val idle: AppUiState = FakeAppController.initialState().copy(
        lastSpoken = "${FakeSampleData.USER_LOCATION_NAME}. Accuracy about 8 metres. Facing north-east.",
    )

    private val destination = FakeSampleData.places.first { it.name == "Government Museum" }

    val navigating: AppUiState = idle.copy(
        sessionMode = SessionMode.NAVIGATING,
        camera = idle.camera.copy(level = StatusLevel.GOOD, text = "Camera running"),
        torch = TorchUi(available = true, on = false, lowLight = false),
        navigation = NavigationUi(
            destinationName = destination.name,
            currentInstruction = "Turn left onto Greams Road.",
            distanceToNextText = "In 40 metres",
            nextInstruction = "Possible crossing at Greams Road junction. Stop and assess your surroundings.",
            progressFraction = 0.22f,
            remainingText = "1.2 kilometres left, about 20 minutes",
            upcomingCrossingText = "Possible crossing in 160 metres",
        ),
        lastSpoken = "Turn left onto Greams Road in 40 metres.",
    )

    val navigatingWithCritical: AppUiState = navigating.copy(
        safetyAlert = SafetyAlertUi(1, SafetySeverity.CRITICAL, FakeSampleData.CRITICAL_ALERT),
    )

    val crossing: AppUiState = navigating.copy(
        sessionMode = SessionMode.CROSSING_ASSIST,
        torch = TorchUi(available = true, on = false, lowLight = true),
        crossing = CrossingUi(
            state = CrossingAssistState.OBSERVING_TRAFFIC,
            stage = CrossingStage.SIDEWALK,
            stateText = "Observing traffic",
            contextText = FakeSampleData.CROSSING_CONTEXT_MAPPED,
            observations = FakeSampleData.observationSamples.take(4).reversed(),
            userConfirmedAtCrossing = true,
            refugeMapped = true,
        ),
    )

    val searchResults: AppUiState = idle.copy(
        search = SearchUi(query = "museum", results = FakeSampleData.places.take(4), hasSearched = true),
    )

    val routePreview: AppUiState = idle.copy(
        search = SearchUi(
            query = "museum",
            results = FakeSampleData.places.take(4),
            hasSearched = true,
            selectedPlace = destination,
            preview = RoutePreviewUi(
                destination = destination,
                lengthText = "1.9 kilometres",
                durationText = "About 32 minutes walking",
                firstInstruction = "Head north-east on Anna Salai, then turn left onto Greams Road.",
                crossingsText = "1 road crossing on this route",
            ),
        ),
    )
}
