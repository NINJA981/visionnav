package com.visionnav.app.engine

import com.visionnav.core.announce.Verbosity
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.crossing.CameraOrientationEstimate
import com.visionnav.core.crossing.CrossingAssistState
import com.visionnav.core.crossing.CrossingStage
import com.visionnav.core.navigation.DirectionStyle
import com.visionnav.core.navigation.Place
import com.visionnav.core.navigation.Route
import com.visionnav.core.navigation.RouteProgress
import com.visionnav.core.safety.SafetySeverity
import com.visionnav.core.sensing.LocationState
import com.visionnav.core.tracking.ApproachState
import com.visionnav.core.tracking.ImageMotion
import com.visionnav.core.vision.CameraState
import com.visionnav.core.vision.NormBox
import com.visionnav.core.vision.ObjectClass

/** What the engine is doing. Drives camera demand, the foreground service and the UI. */
enum class SessionMode { IDLE, NAVIGATING, CROSSING_ASSIST, EXPLORING }

/** Engine-facing settings. Mapped from the UI's persisted settings by the app layer. */
data class EngineSettings(
    val verbosity: Verbosity = Verbosity.STANDARD,
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val directionStyle: DirectionStyle = DirectionStyle.LEFT_RIGHT,
    val hapticsEnabled: Boolean = true,
    val hapticsOnly: Boolean = false,
    val autoTorch: Boolean = false,
    /** Run obstacle/vehicle safety detection while walking a route (uses the camera). */
    val obstacleDetectionWhileNavigating: Boolean = true,
    val emergencyContacts: List<String> = emptyList(),
)

data class EngineError(val id: Long, val text: String)

sealed interface SosStatus {
    data object Idle : SosStatus
    data class AwaitingConfirmation(val contactCount: Int) : SosStatus
    data object Sending : SosStatus
    data class Sent(val message: String) : SosStatus
    data class Failed(val message: String) : SosStatus
}

data class SafetyAlert(val severity: SafetySeverity, val text: String, val elapsedMs: Long)

/** One tracked object, for the debug overlay. */
data class DebugTrack(
    val id: Long,
    val objectClass: ObjectClass,
    val box: NormBox,
    val imageMotion: ImageMotion,
    val approach: ApproachState,
    val confidence: Float,
)

/** One-shot effects the UI must perform (it owns the Activity). */
sealed interface EngineEffect {
    /** Launch an intent the engine cannot launch itself (e.g. pre-filled SMS when SEND_SMS is denied). */
    data class LaunchIntent(val intent: android.content.Intent) : EngineEffect
    data class ShowMessage(val text: String) : EngineEffect
    data class AdjustSpeech(val rateDelta: Float = 0f, val volumeSteps: Int = 0) : EngineEffect
}

data class EngineState(
    val mode: SessionMode = SessionMode.IDLE,
    val destination: Place? = null,
    val route: Route? = null,
    val progress: RouteProgress? = null,
    val currentInstruction: String? = null,
    val guidancePaused: Boolean = false,
    val location: LocationState = LocationState.Unavailable(UncertaintyReason.GPS_UNAVAILABLE),
    val orientation: CameraOrientationEstimate? = null,
    val camera: CameraState = CameraState.Inactive,
    val crossingState: CrossingAssistState = CrossingAssistState.INACTIVE,
    val crossingStage: CrossingStage = CrossingStage.SIDEWALK,
    /** Most recent traffic/crossing observation phrases, newest last. */
    val observationLines: List<String> = emptyList(),
    val crossingContextText: String? = null,
    val userConfirmedAtCrossing: Boolean = false,
    val refugeMapped: Boolean = false,
    val safetyAlert: SafetyAlert? = null,
    val qualityIssues: List<UncertaintyReason> = emptyList(),
    val torchOn: Boolean = false,
    val performanceMode: String = "NORMAL",
    val isListening: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Place> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: String? = null,
    val routeLoading: Boolean = false,
    val sos: SosStatus = SosStatus.Idle,
    val previewPlace: Place? = null,
    val previewRoute: Route? = null,
    val previewLoading: Boolean = false,
    val previewError: String? = null,
    val voiceTranscript: String? = null,
    val errors: List<EngineError> = emptyList(),
    val debugTracks: List<DebugTrack> = emptyList(),
    val horizontalFovDeg: Float? = null,
)
