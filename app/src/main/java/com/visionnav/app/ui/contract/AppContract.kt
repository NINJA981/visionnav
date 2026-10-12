package com.visionnav.app.ui.contract

import com.visionnav.core.crossing.CrossingAssistState
import com.visionnav.core.crossing.CrossingStage
import com.visionnav.core.navigation.Place
import com.visionnav.core.safety.SafetySeverity
import kotlinx.coroutines.flow.StateFlow

/*
 * UI <-> engine integration contract.
 *
 * The UI is a pure function of [AppUiState] and only talks back through [UiAction]s sent to
 * [AppController.dispatch]. All user-facing text that the engine generates (instructions,
 * observations, alerts, spoken utterances) arrives here already formatted for the user's
 * verbosity / units / direction-style settings and MUST already have passed
 * `SafetyPhraseGuard`. Static UI chrome text lives in `res/values/strings.xml`.
 *
 * The UI never infers safety from the absence of data: null / empty fields are rendered as
 * "unknown" or hidden, never as reassurance.
 */

/** What the app is currently doing. Drives which screen is in front (see VisionNavAppRoot). */
enum class SessionMode {
    /** No active session; camera may be off. */
    IDLE,

    /** Turn-by-turn route guidance is running. */
    NAVIGATING,

    /** Assisted road-crossing traffic observation is running (may be entered from NAVIGATING). */
    CROSSING_ASSIST,

    /** Walking without a route: obstacle alerts and "what's around" only. */
    EXPLORING,
}

/** Coarse health of a subsystem. Always rendered with an icon AND text, never colour alone. */
enum class StatusLevel { GOOD, DEGRADED, UNAVAILABLE, OFF }

/** Battery / thermal governor state (Phase 8). */
enum class PerformanceMode { FULL, BATTERY_SAVER, THERMAL_LIMITED }

data class GpsStatusUi(
    val level: StatusLevel = StatusLevel.UNAVAILABLE,
    /** e.g. "GPS good", "GPS accuracy low", "Location unavailable". */
    val text: String = "",
    /** Horizontal accuracy in metres; null when unknown. */
    val accuracyM: Float? = null,
)

data class HeadingUi(
    /** e.g. "Facing north-east". Null when heading is unavailable. */
    val text: String? = null,
    val reliable: Boolean = false,
    /** True when the compass needs a figure-eight calibration. */
    val needsCalibration: Boolean = false,
)

data class CameraStatusUi(
    val level: StatusLevel = StatusLevel.OFF,
    /** e.g. "Camera off", "Camera running", "Camera view obstructed". */
    val text: String = "",
    /** From DetectorCapabilities.modelName; null when no detector is loaded. */
    val detectorModelName: String? = null,
    /** From DetectorCapabilities.isMock. When true the UI shows "Demo detector — not real detection". */
    val isMockDetector: Boolean = false,
    /** From DetectorCapabilities.supportsVehicles(). */
    val supportsVehicles: Boolean = false,
)

data class TorchUi(
    val available: Boolean = false,
    val on: Boolean = false,
    /** Frame-quality analyzer reports low light. */
    val lowLight: Boolean = false,
)

data class NavigationUi(
    val destinationName: String,
    /** Current maneuver, as spoken. Shown at >= 34 sp. */
    val currentInstruction: String,
    /** e.g. "In 40 metres". Null when unknown (e.g. GPS lost). */
    val distanceToNextText: String?,
    /** The maneuver after the current one, if any. */
    val nextInstruction: String?,
    /** 0f..1f along the route. */
    val progressFraction: Float,
    /** e.g. "650 metres left, about 11 minutes". */
    val remainingText: String?,
    val isOffRoute: Boolean = false,
    val isRerouting: Boolean = false,
    val hasArrived: Boolean = false,
    /** e.g. "Mapped crossing with signals in 60 metres". */
    val upcomingCrossingText: String? = null,
    /** Shape of the painted arrow for the next maneuver. Visual only; the instruction text carries meaning. */
    val arrow: com.visionnav.app.ui.kerb.ArrowKind = com.visionnav.app.ui.kerb.ArrowKind.STRAIGHT,
)

data class CrossingUi(
    val state: CrossingAssistState = CrossingAssistState.INACTIVE,
    val stage: CrossingStage = CrossingStage.UNKNOWN,
    /** Short state text, e.g. "Observing traffic", "View unclear". */
    val stateText: String = "",
    /** Map context, e.g. "Mapped crossing with signals ahead.", "Road direction unavailable." */
    val contextText: String? = null,
    /** Latest traffic observation lines, NEWEST FIRST. Already phrase-guarded. */
    val observations: List<String> = emptyList(),
    /** User pressed "I'm at the crossing". GPS alone never sets this. */
    val userConfirmedAtCrossing: Boolean = false,
    /** True when a refuge island is mapped, so "Next segment" is meaningful. */
    val refugeMapped: Boolean = false,
)

data class SafetyAlertUi(
    /** Unique per alert instance; used for dismissal and to re-announce. */
    val id: Long,
    val severity: SafetySeverity,
    val text: String,
)

data class RoutePreviewUi(
    val destination: Place,
    /** e.g. "1.2 kilometres". */
    val lengthText: String,
    /** e.g. "About 20 minutes walking". */
    val durationText: String,
    val firstInstruction: String,
    /** e.g. "2 road crossings on this route". Null when unknown. */
    val crossingsText: String? = null,
)

data class SearchUi(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val isLoading: Boolean = false,
    /** User-facing error, e.g. "Search unavailable. Check your internet connection." */
    val error: String? = null,
    /** True after at least one search completed (distinguishes "no results" from "not searched"). */
    val hasSearched: Boolean = false,
    val selectedPlace: Place? = null,
    val preview: RoutePreviewUi? = null,
    val isPreviewLoading: Boolean = false,
    val previewError: String? = null,
)

data class SavedPlaceUi(
    val id: String,
    /** User label, e.g. "Home". */
    val name: String,
    val place: Place,
)

sealed interface SosUi {
    data object Idle : SosUi

    /** Waiting for the user's explicit confirmation. Nothing is sent before ConfirmSos. */
    data class AwaitingConfirmation(val contactCount: Int, val message: String) : SosUi
    data object Sending : SosUi
    data class Sent(val message: String) : SosUi
    data class Failed(val message: String) : SosUi
}

data class ErrorBannerUi(val id: Long, val text: String)

data class AppUiState(
    val sessionMode: SessionMode = SessionMode.IDLE,
    /** Non-null while a route is active (NAVIGATING, or CROSSING_ASSIST entered from navigation). */
    val navigation: NavigationUi? = null,
    val gps: GpsStatusUi = GpsStatusUi(),
    val heading: HeadingUi = HeadingUi(),
    val camera: CameraStatusUi = CameraStatusUi(),
    val torch: TorchUi = TorchUi(),
    val performanceMode: PerformanceMode = PerformanceMode.FULL,
    /** e.g. "Reduced detection rate to save battery". Null in FULL mode. */
    val performanceText: String? = null,
    val crossing: CrossingUi = CrossingUi(),
    /** Most recent utterance spoken by the audio engine (for "Repeat" and on-screen echo). */
    val lastSpoken: String? = null,
    /** High-contrast banner. CRITICAL is announced assertively; others politely. */
    val safetyAlert: SafetyAlertUi? = null,
    /** Speech recogniser is listening. */
    val isListening: Boolean = false,
    /** Last recognised command text, e.g. "navigate to Marina Beach". */
    val voiceTranscript: String? = null,
    val search: SearchUi = SearchUi(),
    val savedPlaces: List<SavedPlaceUi> = emptyList(),
    val sos: SosUi = SosUi.Idle,
    val errors: List<ErrorBannerUi> = emptyList(),
    /** Text-only session log lines for the debug screen, newest last. No images. */
    val debugLog: List<String> = emptyList(),
) {
    val destinationName: String? get() = navigation?.destinationName
}

/** Every user intent the UI can express. */
sealed interface UiAction {
    data object StartVoiceCommand : UiAction
    data object StopVoiceCommand : UiAction

    data class Search(val query: String) : UiAction
    data object ClearSearch : UiAction

    /** Selects a search result / saved place and requests a route preview. */
    data class SelectPlace(val place: Place) : UiAction
    data object StartNavigation : UiAction
    data object StopNavigation : UiAction

    data object StartExploring : UiAction
    data object StopExploring : UiAction

    data object StartTrafficObservation : UiAction
    data object StopTrafficObservation : UiAction

    /** User confirms they are at the kerb of the crossing. */
    data object ConfirmAtCrossing : UiAction

    /** User confirms they reached the refuge island; restart observation for the next segment. */
    data object NextSegment : UiAction

    data object WhereAmI : UiAction
    data object WhatsAround : UiAction
    data object RepeatLast : UiAction

    data object ToggleTorch : UiAction

    /** Save the current location under [name]. */
    data class SavePlace(val name: String) : UiAction
    data class RenameSavedPlace(val id: String, val name: String) : UiAction
    data class DeleteSavedPlace(val id: String) : UiAction

    /** Opens the SOS confirmation. Never sends anything by itself. */
    data object TriggerSos : UiAction
    data object ConfirmSos : UiAction
    data object CancelSos : UiAction

    /** Acknowledges a Sent / Failed SOS result and returns to [SosUi.Idle]. */
    data object DismissSosResult : UiAction

    data class DismissAlert(val id: Long) : UiAction
    data class DismissError(val id: Long) : UiAction

    /** Debug screen opened / closed (engine may start camera preview frames). */
    data object OpenDebug : UiAction
    data object CloseDebug : UiAction
}

/** Implemented by the engine orchestrator; [FakeAppController] until integration. */
interface AppController {
    val state: StateFlow<AppUiState>
    fun dispatch(action: UiAction)
}
