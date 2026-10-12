package com.visionnav.app.integration

import android.content.Context
import android.media.AudioManager
import com.visionnav.app.engine.EngineEffect
import com.visionnav.app.engine.EngineSettings
import com.visionnav.app.engine.EngineState
import com.visionnav.app.engine.GatedHapticPlayer
import com.visionnav.app.engine.SavedPlace
import com.visionnav.app.engine.SavedPlacesStore
import com.visionnav.app.engine.SessionLog
import com.visionnav.app.engine.SessionMode as EngineMode
import com.visionnav.app.engine.SosStatus
import com.visionnav.app.engine.VisionNavEngine
import com.visionnav.app.service.SessionServiceController
import com.visionnav.app.settings.AppSettings
import com.visionnav.app.settings.SettingsRepository
import com.visionnav.app.ui.contract.AppController
import com.visionnav.app.ui.contract.AppUiState
import com.visionnav.app.ui.contract.CameraStatusUi
import com.visionnav.app.ui.contract.CrossingUi
import com.visionnav.app.ui.contract.ErrorBannerUi
import com.visionnav.app.ui.contract.GpsStatusUi
import com.visionnav.app.ui.contract.HeadingUi
import com.visionnav.app.ui.contract.NavigationUi
import com.visionnav.app.ui.contract.PerformanceMode
import com.visionnav.app.ui.contract.RoutePreviewUi
import com.visionnav.app.ui.contract.SafetyAlertUi
import com.visionnav.app.ui.contract.SavedPlaceUi
import com.visionnav.app.ui.contract.SearchUi
import com.visionnav.app.ui.contract.SessionMode
import com.visionnav.app.ui.contract.SosUi
import com.visionnav.app.ui.contract.StatusLevel
import com.visionnav.app.ui.contract.TorchUi
import com.visionnav.app.ui.contract.UiAction
import com.visionnav.core.announce.AudioQueueManager
import com.visionnav.core.announce.SafetyPhraseGuard
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.crossing.CrossingAssistState
import com.visionnav.app.ui.aura.ArrowKind
import com.visionnav.core.navigation.DirectionStyle
import com.visionnav.core.navigation.ManeuverType
import com.visionnav.core.navigation.NavigationPhrases
import com.visionnav.core.navigation.Route
import com.visionnav.core.sensing.LocationState
import com.visionnav.core.vision.CameraState
import com.visionnav.perception.camera.CameraSession
import com.visionnav.platform.audio.AndroidTtsSpeaker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * The real [AppController]: adapts [VisionNavEngine] state to the UI contract, routes UI actions to
 * the engine, pushes persisted settings into the engine/TTS/haptics, and keeps the foreground
 * service in step with the session mode.
 *
 * Every engine-generated string shown in the UI has already been spoken through the audio queue
 * (and therefore checked by SafetyPhraseGuard). Strings composed here (route preview, status lines)
 * are re-checked with [guard] before display.
 */
@Singleton
class EngineAppController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VisionNavEngine,
    private val settingsRepository: SettingsRepository,
    private val savedPlaces: SavedPlacesStore,
    private val audio: AudioQueueManager,
    private val speaker: AndroidTtsSpeaker,
    private val haptics: GatedHapticPlayer,
    private val cameraSession: CameraSession,
    private val powerGovernor: com.visionnav.platform.power.PowerGovernor,
    private val sessionService: SessionServiceController,
    private val log: SessionLog,
) : AppController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val guard = SafetyPhraseGuard { log.log("DEFECT", "Blocked UI text: $it") }
    private val phrases = NavigationPhrases()
    @Volatile private var directionStyle = DirectionStyle.LEFT_RIGHT

    val effects: SharedFlow<EngineEffect> get() = engine.effects

    private val lastSpoken: StateFlow<String?> = audio.speaking
        .map { it?.text }
        .let { flow -> kotlinx.coroutines.flow.flow { var last: String? = null; flow.collect { t -> if (t != null) last = t; emit(last) } } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val state: StateFlow<AppUiState> = combine(
        engine.state, savedPlaces.places, lastSpoken, log.lines,
    ) { s, places, spoken, lines -> map(s, places, spoken, lines) }
        .stateIn(scope, SharingStarted.Eagerly, AppUiState())

    init {
        scope.launch {
            settingsRepository.settings.collect { s ->
                directionStyle = s.directionStyle.toCore()
                phrases.directionStyle = directionStyle
                engine.applySettings(s.toEngine())
                speaker.setSpeechRate(s.speechRate)
                speaker.setPitch(s.speechPitch)
                haptics.enabled = s.hapticsEnabled
            }
        }
        scope.launch { speaker.ready.collect { ready -> if (ready) audio.onSpeakerReady() } }
        scope.launch { powerGovernor.mode.collect { m -> cameraSession.setThrottleFactor(powerGovernor.inferenceThrottleFactor) } }
        scope.launch {
            engine.state.map { it.mode }.distinctUntilChanged().collect { mode ->
                if (mode == EngineMode.IDLE) sessionService.stop() else sessionService.start(mode)
            }
        }
        scope.launch {
            engine.effects.collect { e ->
                if (e is EngineEffect.AdjustSpeech) adjustSpeech(e)
            }
        }
    }

    override fun dispatch(action: UiAction) {
        log.log("UI", action.toString().take(120))
        when (action) {
            UiAction.StartVoiceCommand -> engine.listenForCommand()
            UiAction.StopVoiceCommand -> Unit // recogniser stops on its own end-of-speech detection
            is UiAction.Search -> engine.search(action.query)
            UiAction.ClearSearch -> engine.clearSearch()
            is UiAction.SelectPlace -> engine.previewRoute(action.place)
            UiAction.StartNavigation -> engine.startNavigation()
            UiAction.StopNavigation -> engine.stopNavigation()
            UiAction.StartExploring -> engine.startExploring()
            UiAction.StopExploring -> engine.stopExploring()
            UiAction.StartTrafficObservation -> engine.startTrafficObservation()
            UiAction.StopTrafficObservation -> engine.stopTrafficObservation()
            UiAction.ConfirmAtCrossing -> engine.confirmAtCrossing()
            UiAction.NextSegment -> engine.nextSegment()
            UiAction.WhereAmI -> engine.whereAmI()
            UiAction.WhatsAround -> engine.whatsAround()
            UiAction.RepeatLast -> engine.repeatLast()
            UiAction.ToggleTorch -> engine.setTorch(!engine.state.value.torchOn)
            is UiAction.SavePlace -> engine.savePlace(action.name)
            is UiAction.RenameSavedPlace -> engine.renamePlace(action.id, action.name)
            is UiAction.DeleteSavedPlace -> engine.deletePlace(action.id)
            UiAction.TriggerSos -> engine.requestSos()
            UiAction.ConfirmSos -> engine.confirmSos()
            UiAction.CancelSos -> engine.cancelSos()
            UiAction.DismissSosResult -> engine.dismissSosResult()
            is UiAction.DismissAlert -> engine.dismissAlert()
            is UiAction.DismissError -> engine.dismissError(action.id)
            UiAction.OpenDebug -> debugOpen = true
            UiAction.CloseDebug -> {
                debugOpen = false
                cameraSession.detachPreview()
            }
        }
    }

    @Volatile var debugOpen: Boolean = false
        private set

    // ------------------------------------------------------------------ mapping

    private fun map(s: EngineState, places: List<SavedPlace>, spoken: String?, lines: List<String>): AppUiState = AppUiState(
        sessionMode = when (s.mode) {
            EngineMode.IDLE -> SessionMode.IDLE
            EngineMode.NAVIGATING -> if (s.crossingState in ACTIVE_OBSERVATION) SessionMode.CROSSING_ASSIST else SessionMode.NAVIGATING
            EngineMode.CROSSING_ASSIST -> SessionMode.CROSSING_ASSIST
            EngineMode.EXPLORING -> SessionMode.EXPLORING
        },
        navigation = navigationUi(s),
        gps = gpsUi(s),
        heading = headingUi(s),
        camera = cameraUi(s),
        torch = TorchUi(
            available = (s.camera as? CameraState.Running)?.hasTorch ?: false,
            on = s.torchOn,
            lowLight = UncertaintyReason.LOW_LIGHT in s.qualityIssues,
        ),
        performanceMode = when (s.performanceMode) {
            "REDUCED" -> PerformanceMode.BATTERY_SAVER
            "MINIMAL" -> PerformanceMode.THERMAL_LIMITED
            else -> PerformanceMode.FULL
        },
        performanceText = when (s.performanceMode) {
            "REDUCED" -> "Detection rate reduced to save power."
            "MINIMAL" -> "Phone is hot or battery low. Detection rate greatly reduced."
            else -> null
        },
        crossing = CrossingUi(
            state = s.crossingState,
            stage = s.crossingStage,
            stateText = crossingStateText(s.crossingState),
            contextText = s.crossingContextText,
            observations = s.observationLines.asReversed(),
            userConfirmedAtCrossing = s.userConfirmedAtCrossing,
            refugeMapped = s.refugeMapped,
        ),
        lastSpoken = spoken,
        safetyAlert = s.safetyAlert?.let { SafetyAlertUi(it.elapsedMs, it.severity, it.text) },
        isListening = s.isListening,
        voiceTranscript = s.voiceTranscript,
        search = SearchUi(
            query = s.searchQuery,
            results = s.searchResults,
            isLoading = s.searchLoading,
            error = s.searchError,
            hasSearched = s.searchQuery.isNotEmpty() && !s.searchLoading,
            selectedPlace = s.previewPlace,
            preview = s.previewPlace?.let { p -> s.previewRoute?.let { r -> previewUi(p, r) } },
            isPreviewLoading = s.previewLoading,
            previewError = s.previewError,
        ),
        savedPlaces = places.map { SavedPlaceUi(it.id, it.name, it.place) },
        sos = when (val sos = s.sos) {
            SosStatus.Idle -> SosUi.Idle
            is SosStatus.AwaitingConfirmation -> SosUi.AwaitingConfirmation(sos.contactCount, com.visionnav.app.engine.EnginePhrases.SOS_CONFIRM)
            SosStatus.Sending -> SosUi.Sending
            is SosStatus.Sent -> SosUi.Sent(sos.message)
            is SosStatus.Failed -> SosUi.Failed(sos.message)
        },
        errors = s.errors.map { ErrorBannerUi(it.id, it.text) },
        debugLog = lines.takeLast(300),
    )

    private fun navigationUi(s: EngineState): NavigationUi? {
        val route = s.route ?: return null
        val dest = s.destination ?: return null
        val p = s.progress
        val maneuvers = route.maneuvers
        val idx = p?.currentManeuverIndex ?: 0
        val next = p?.nextManeuver ?: maneuvers.getOrNull(1)
        val after = next?.let { n -> maneuvers.getOrNull(maneuvers.indexOf(n) + 1) }
        val current = s.currentInstruction ?: maneuvers.getOrNull(idx)?.engineInstruction?.let(::safe) ?: "Follow the route."
        val distanceText = p?.distanceToNextManeuverM?.let { d -> safe(phrases.distancePhrase(d, p.gpsAccuracyM).replaceFirstChar(Char::uppercase)) }
        return NavigationUi(
            destinationName = dest.name,
            currentInstruction = current,
            distanceToNextText = distanceText,
            nextInstruction = after?.engineInstruction?.let(::safe),
            progressFraction = if (route.lengthM > 0 && p != null) (p.alongM / route.lengthM).toFloat().coerceIn(0f, 1f) else 0f,
            remainingText = p?.let { remainingText(it.remainingM) },
            isOffRoute = p?.isOffRoute ?: false,
            isRerouting = p?.isOffRoute ?: false,
            hasArrived = p?.arrived ?: false,
            upcomingCrossingText = s.crossingContextText.takeIf { s.crossingState == CrossingAssistState.APPROACH_CONTEXT_AVAILABLE },
            arrow = if (p?.arrived == true) ArrowKind.ARRIVE else arrowFor(next?.type),
        )
    }

    private fun arrowFor(type: ManeuverType?): ArrowKind = when (type) {
        ManeuverType.SLIGHT_LEFT -> ArrowKind.SLIGHT_LEFT
        ManeuverType.LEFT -> ArrowKind.LEFT
        ManeuverType.SHARP_LEFT -> ArrowKind.SHARP_LEFT
        ManeuverType.SLIGHT_RIGHT -> ArrowKind.SLIGHT_RIGHT
        ManeuverType.RIGHT -> ArrowKind.RIGHT
        ManeuverType.SHARP_RIGHT -> ArrowKind.SHARP_RIGHT
        ManeuverType.U_TURN -> ArrowKind.U_TURN
        ManeuverType.CROSS_ROAD -> ArrowKind.CROSSING
        ManeuverType.ARRIVE, ManeuverType.ARRIVE_LEFT, ManeuverType.ARRIVE_RIGHT -> ArrowKind.ARRIVE
        else -> ArrowKind.STRAIGHT
    }

    private fun previewUi(place: com.visionnav.core.navigation.Place, route: Route): RoutePreviewUi {
        val crossings = route.maneuvers.count { it.isCrossing }
        return RoutePreviewUi(
            destination = place,
            lengthText = lengthText(route.lengthM),
            durationText = "About ${(route.durationS / 60).roundToInt().coerceAtLeast(1)} minutes walking",
            firstInstruction = route.maneuvers.firstOrNull()?.engineInstruction?.let(::safe) ?: "Follow the route.",
            // Valhalla only flags mapped crosswalk ways; absence is "unknown", not "none".
            crossingsText = if (crossings > 0) "$crossings mapped crosswalk${if (crossings == 1) "" else "s"} on this route" else null,
        )
    }

    private fun gpsUi(s: EngineState): GpsStatusUi = when (val l = s.location) {
        is LocationState.Available -> {
            val acc = l.fix.accuracyM
            when {
                acc <= 15f -> GpsStatusUi(StatusLevel.GOOD, "GPS good, about ${acc.roundToInt()} metres", acc)
                acc <= 40f -> GpsStatusUi(StatusLevel.DEGRADED, "GPS accuracy low, about ${acc.roundToInt()} metres", acc)
                else -> GpsStatusUi(StatusLevel.DEGRADED, "GPS accuracy poor", acc)
            }
        }
        is LocationState.Unavailable ->
            if (s.mode == EngineMode.IDLE) GpsStatusUi(StatusLevel.OFF, "Location off until needed", null)
            else GpsStatusUi(StatusLevel.UNAVAILABLE, "Location unavailable", null)
    }

    private fun headingUi(s: EngineState): HeadingUi {
        val o = s.orientation ?: return HeadingUi()
        val bearing = o.cameraBearingDeg
        return HeadingUi(
            text = if (o.isReliable && bearing != null) "Phone facing ${NavigationPhrases.compass(bearing)}" else null,
            reliable = o.isReliable,
            needsCalibration = UncertaintyReason.HEADING_UNRELIABLE in o.reasons,
        )
    }

    private fun cameraUi(s: EngineState): CameraStatusUi = when (val c = s.camera) {
        CameraState.Inactive -> CameraStatusUi(StatusLevel.OFF, "Camera off")
        CameraState.Starting -> CameraStatusUi(StatusLevel.DEGRADED, "Camera starting")
        is CameraState.Running -> {
            val caps = c.capabilities
            val issue = s.qualityIssues.firstOrNull()
            CameraStatusUi(
                level = if (issue == null && caps?.isMock != true) StatusLevel.GOOD else StatusLevel.DEGRADED,
                text = when (issue) {
                    UncertaintyReason.CAMERA_OBSTRUCTED -> "Camera view blocked"
                    UncertaintyReason.LOW_LIGHT -> "Camera running, low light"
                    UncertaintyReason.GLARE -> "Camera running, glare"
                    UncertaintyReason.BLUR -> "Camera running, image blurred"
                    else -> "Camera running"
                },
                detectorModelName = caps?.let { "${it.modelName} (${it.accelerator})" },
                isMockDetector = caps?.isMock ?: false,
                supportsVehicles = caps?.supportsVehicles() ?: false,
            )
        }
        is CameraState.Unavailable -> CameraStatusUi(
            StatusLevel.UNAVAILABLE,
            if (c.reason == UncertaintyReason.DETECTOR_UNAVAILABLE) "Detector unavailable" else "Camera unavailable",
        )
    }

    private fun crossingStateText(st: CrossingAssistState): String = when (st) {
        CrossingAssistState.INACTIVE -> "Not observing"
        CrossingAssistState.APPROACH_CONTEXT_AVAILABLE -> "Crossing information available"
        CrossingAssistState.USER_STOPPED_FOR_OBSERVATION -> "Getting ready to observe"
        CrossingAssistState.OBSERVING_TRAFFIC -> "Observing traffic"
        CrossingAssistState.OBSERVATION_UNCERTAIN -> "View unclear"
        CrossingAssistState.OBSERVATION_COMPLETE -> "Observation ended"
        CrossingAssistState.SEGMENT_TRANSITION -> "Starting next segment"
        CrossingAssistState.EXITED -> "Crossing assist ended"
    }

    private fun lengthText(m: Double): String =
        if (m < 1_000) "${(m / 10).roundToInt() * 10} metres" else "%.1f kilometres".format(m / 1_000)

    private fun remainingText(m: Double): String {
        val minutes = (m / WALK_SPEED_MPS / 60).roundToInt().coerceAtLeast(1)
        return "${lengthText(m)} left, about $minutes minute${if (minutes == 1) "" else "s"}"
    }

    private fun safe(text: String): String? = text.takeIf { guard.isAllowed(it) }

    private fun adjustSpeech(e: EngineEffect.AdjustSpeech) {
        if (e.rateDelta != 0f) scope.launch {
            settingsRepository.update { it.copy(speechRate = SettingsRepository.clampSpeech(it.speechRate + e.rateDelta)) }
        }
        if (e.volumeSteps != 0) {
            val am = context.getSystemService(AudioManager::class.java)
            val dir = if (e.volumeSteps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun AppSettings.toEngine() = EngineSettings(
        verbosity = verbosity,
        speechRate = speechRate,
        speechPitch = speechPitch,
        directionStyle = directionStyle.toCore(),
        hapticsEnabled = hapticsEnabled,
        hapticsOnly = hapticsOnly,
        autoTorch = autoTorchInLowLight,
        emergencyContacts = emergencyContacts.map { it.phone },
    )

    private fun com.visionnav.app.settings.DirectionStyle.toCore() = when (this) {
        com.visionnav.app.settings.DirectionStyle.LEFT_RIGHT -> DirectionStyle.LEFT_RIGHT
        com.visionnav.app.settings.DirectionStyle.CLOCK_FACE -> DirectionStyle.CLOCK_FACE
    }

    private companion object {
        const val WALK_SPEED_MPS = 1.2
        val ACTIVE_OBSERVATION = setOf(
            CrossingAssistState.USER_STOPPED_FOR_OBSERVATION,
            CrossingAssistState.OBSERVING_TRAFFIC,
            CrossingAssistState.OBSERVATION_UNCERTAIN,
            CrossingAssistState.SEGMENT_TRANSITION,
        )
    }
}
