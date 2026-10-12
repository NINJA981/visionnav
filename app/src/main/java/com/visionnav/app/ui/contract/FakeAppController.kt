package com.visionnav.app.ui.contract

import com.visionnav.core.crossing.CrossingAssistState
import com.visionnav.core.crossing.CrossingStage
import com.visionnav.core.navigation.Place
import com.visionnav.core.safety.SafetySeverity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Demo implementation of [AppController] so the UI is fully usable before the engine is wired.
 * It simulates search, route preview, turn-by-turn progress, traffic observation, SOS and voice
 * with realistic timing. It never touches sensors, network, SMS or speech.
 *
 * Everything it says is labelled honestly: the detector is reported as a demo detector and
 * SOS states that no message was sent.
 */
@Singleton
class FakeAppController(
    private val scope: CoroutineScope,
    /** Duration of one simulation tick. */
    private val tickMs: Long = DEFAULT_TICK_MS,
) : AppController {

    @Inject
    constructor() : this(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val _state = MutableStateFlow(initialState())
    override val state: StateFlow<AppUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private var navJob: Job? = null
    private var observeJob: Job? = null
    private var voiceJob: Job? = null
    private var sosJob: Job? = null
    private var alertJob: Job? = null

    private var idCounter = 0L
    private var logCounter = 0
    private var modeBeforeCrossing = SessionMode.IDLE

    /** The active simulated route; null when not navigating. */
    private var route: SimRoute? = null

    override fun dispatch(action: UiAction) {
        log("Action: ${action::class.simpleName}")
        when (action) {
            UiAction.StartVoiceCommand -> startVoice()
            UiAction.StopVoiceCommand -> {
                voiceJob?.cancel()
                _state.update { it.copy(isListening = false) }
            }
            is UiAction.Search -> search(action.query)
            UiAction.ClearSearch -> {
                searchJob?.cancel(); previewJob?.cancel()
                _state.update { it.copy(search = SearchUi()) }
            }
            is UiAction.SelectPlace -> selectPlace(action.place)
            UiAction.StartNavigation -> startNavigation()
            UiAction.StopNavigation -> stopNavigation()
            UiAction.StartExploring -> {
                if (_state.value.sessionMode == SessionMode.IDLE) {
                    setMode(SessionMode.EXPLORING)
                    speak("Explore mode on. Obstacle alerts active. Camera sees ahead only.")
                }
            }
            UiAction.StopExploring -> {
                if (_state.value.sessionMode == SessionMode.EXPLORING) {
                    setMode(SessionMode.IDLE)
                    speak("Explore mode off.")
                }
            }
            UiAction.StartTrafficObservation -> startObservation()
            UiAction.StopTrafficObservation -> stopObservation()
            UiAction.ConfirmAtCrossing -> confirmAtCrossing()
            UiAction.NextSegment -> nextSegment()
            UiAction.WhereAmI -> speak(
                "${FakeSampleData.USER_LOCATION_NAME}. Accuracy about ${FakeSampleData.USER_ACCURACY_M.toInt()} metres. Facing north-east.",
            )
            UiAction.WhatsAround -> speak(FakeSampleData.WHATS_AROUND)
            UiAction.RepeatLast -> {
                val last = _state.value.lastSpoken ?: "Nothing to repeat yet."
                // Re-emit as a fresh utterance; the engine would re-queue it for TTS.
                _state.update { it.copy(lastSpoken = null) }
                speak(last)
            }
            UiAction.ToggleTorch -> toggleTorch()
            is UiAction.SavePlace -> savePlace(action.name)
            is UiAction.RenameSavedPlace -> _state.update { s ->
                s.copy(savedPlaces = s.savedPlaces.map { if (it.id == action.id) it.copy(name = action.name.trim()) else it })
            }
            is UiAction.DeleteSavedPlace -> _state.update { s ->
                s.copy(savedPlaces = s.savedPlaces.filterNot { it.id == action.id })
            }
            UiAction.TriggerSos -> _state.update {
                it.copy(
                    sos = SosUi.AwaitingConfirmation(
                        contactCount = 2,
                        message = "Send an emergency text with your location to 2 contacts?",
                    ),
                )
            }
            UiAction.ConfirmSos -> confirmSos()
            UiAction.CancelSos -> {
                sosJob?.cancel()
                _state.update { it.copy(sos = SosUi.Idle) }
                speak("Emergency message cancelled.")
            }
            UiAction.DismissSosResult -> _state.update { it.copy(sos = SosUi.Idle) }
            is UiAction.DismissAlert -> _state.update { s ->
                if (s.safetyAlert?.id == action.id) s.copy(safetyAlert = null) else s
            }
            is UiAction.DismissError -> _state.update { s -> s.copy(errors = s.errors.filterNot { it.id == action.id }) }
            UiAction.OpenDebug, UiAction.CloseDebug -> Unit
        }
    }

    // ---------------------------------------------------------------- voice

    private fun startVoice() {
        voiceJob?.cancel()
        _state.update { it.copy(isListening = true, voiceTranscript = null) }
        voiceJob = scope.launch {
            delay(tickMs * 2)
            _state.update { it.copy(isListening = false, voiceTranscript = "where am I") }
            dispatch(UiAction.WhereAmI)
        }
    }

    // ---------------------------------------------------------------- search & preview

    private fun search(rawQuery: String) {
        val query = rawQuery.trim()
        searchJob?.cancel()
        previewJob?.cancel()
        if (query.isEmpty()) {
            _state.update { it.copy(search = SearchUi(error = "Type or say a destination.")) }
            return
        }
        _state.update { it.copy(search = SearchUi(query = query, isLoading = true)) }
        searchJob = scope.launch {
            delay(tickMs / 2)
            if (query.contains("offline", ignoreCase = true)) {
                _state.update {
                    it.copy(
                        search = it.search.copy(
                            isLoading = false,
                            hasSearched = true,
                            error = "Search unavailable. Check your internet connection.",
                        ),
                    )
                }
                return@launch
            }
            val tokens = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
            val results = FakeSampleData.places.filter { p ->
                val hay = "${p.name} ${p.address.orEmpty()} ${p.category.orEmpty()}".lowercase()
                tokens.any { hay.contains(it) }
            }
            _state.update { it.copy(search = it.search.copy(isLoading = false, hasSearched = true, results = results)) }
            speak(
                when (results.size) {
                    0 -> "No places found for $query."
                    1 -> "1 result."
                    else -> "${results.size} results."
                },
            )
        }
    }

    private fun selectPlace(place: Place) {
        previewJob?.cancel()
        _state.update {
            it.copy(search = it.search.copy(selectedPlace = place, preview = null, previewError = null, isPreviewLoading = true))
        }
        previewJob = scope.launch {
            delay(tickMs / 2)
            val sim = SimRoute.forPlace(place)
            route = null
            _state.update {
                it.copy(
                    search = it.search.copy(
                        isPreviewLoading = false,
                        preview = RoutePreviewUi(
                            destination = place,
                            lengthText = FakeSampleData.distanceText(sim.totalM),
                            durationText = "${FakeSampleData.minutesText(sim.totalM).replaceFirstChar { c -> c.uppercase() }} walking",
                            firstInstruction = sim.startInstruction,
                            crossingsText = "1 road crossing on this route",
                        ),
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------------- navigation

    private fun startNavigation() {
        val place = _state.value.search.selectedPlace ?: return
        navJob?.cancel()
        val sim = SimRoute.forPlace(place)
        route = sim
        _state.update { it.withMode(SessionMode.NAVIGATING).copy(navigation = sim.toUi()) }
        speak("Starting route to ${place.name}. ${sim.startInstruction}")
        navJob = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(tickMs)
                // Walking pauses while the user observes traffic.
                if (_state.value.sessionMode != SessionMode.NAVIGATING) continue
                ticks++
                val r = route ?: break
                val spokenManeuver = r.advance()
                _state.update { it.copy(navigation = r.toUi()) }
                if (spokenManeuver != null) speak(spokenManeuver)
                when (ticks) {
                    ALERT_WARNING_TICK -> raiseAlert(SafetySeverity.WARNING, FakeSampleData.WARNING_ALERT)
                    ALERT_CRITICAL_TICK -> raiseAlert(SafetySeverity.CRITICAL, FakeSampleData.CRITICAL_ALERT)
                }
                if (r.arrived) break
            }
        }
    }

    private fun stopNavigation() {
        navJob?.cancel()
        observeJob?.cancel()
        val wasActive = route != null
        route = null
        _state.update { it.withMode(SessionMode.IDLE).copy(navigation = null, crossing = CrossingUi(), safetyAlert = null) }
        if (wasActive) speak("Navigation stopped.")
    }

    private fun raiseAlert(severity: SafetySeverity, text: String) {
        val id = ++idCounter
        alertJob?.cancel()
        _state.update { it.copy(safetyAlert = SafetyAlertUi(id, severity, text)) }
        speak(text)
        alertJob = scope.launch {
            delay(tickMs * 3)
            _state.update { s -> if (s.safetyAlert?.id == id) s.copy(safetyAlert = null) else s }
        }
    }

    // ---------------------------------------------------------------- crossing assist

    private fun startObservation() {
        val current = _state.value.sessionMode
        if (current == SessionMode.CROSSING_ASSIST) return
        modeBeforeCrossing = current
        observeJob?.cancel()
        val fromRoute = current == SessionMode.NAVIGATING
        _state.update {
            it.withMode(SessionMode.CROSSING_ASSIST).copy(
                crossing = CrossingUi(
                    state = CrossingAssistState.USER_STOPPED_FOR_OBSERVATION,
                    stage = CrossingStage.UNKNOWN,
                    stateText = "Starting traffic observation",
                    contextText = if (fromRoute) FakeSampleData.CROSSING_CONTEXT_MAPPED else FakeSampleData.CROSSING_CONTEXT_UNKNOWN,
                    refugeMapped = fromRoute,
                ),
            )
        }
        speak("Traffic observation starting. Hold the phone upright, camera facing the road.")
        observeJob = scope.launch { observeLoop(startIndex = 0) }
    }

    private suspend fun observeLoop(startIndex: Int) {
        delay(tickMs)
        _state.update {
            it.copy(crossing = it.crossing.copy(state = CrossingAssistState.OBSERVING_TRAFFIC, stateText = "Observing traffic"))
        }
        speak("Observing traffic. Camera sees ahead only.")
        var i = startIndex
        while (currentCoroutineContext().isActive) {
            delay(tickMs)
            val line = FakeSampleData.observationSamples[i % FakeSampleData.observationSamples.size]
            i++
            val lowLight = line == FakeSampleData.LOW_LIGHT_OBSERVATION
            _state.update { s ->
                s.copy(
                    crossing = s.crossing.copy(
                        state = if (lowLight) CrossingAssistState.OBSERVATION_UNCERTAIN else CrossingAssistState.OBSERVING_TRAFFIC,
                        stateText = if (lowLight) "View unclear" else "Observing traffic",
                        observations = (listOf(line) + s.crossing.observations).take(MAX_OBSERVATIONS),
                    ),
                    torch = s.torch.copy(lowLight = lowLight),
                    camera = s.camera.copy(
                        level = if (lowLight) StatusLevel.DEGRADED else StatusLevel.GOOD,
                        text = if (lowLight) "Camera view dark" else "Camera running",
                    ),
                )
            }
            speak(line)
        }
    }

    private fun confirmAtCrossing() {
        if (_state.value.sessionMode != SessionMode.CROSSING_ASSIST) return
        _state.update {
            it.copy(crossing = it.crossing.copy(userConfirmedAtCrossing = true, stage = CrossingStage.SIDEWALK))
        }
        speak("Position confirmed at the crossing. Keep listening to the traffic.")
    }

    private fun nextSegment() {
        val s = _state.value
        if (s.sessionMode != SessionMode.CROSSING_ASSIST) return
        observeJob?.cancel()
        _state.update {
            it.copy(
                crossing = it.crossing.copy(
                    state = CrossingAssistState.SEGMENT_TRANSITION,
                    stage = CrossingStage.REFUGE,
                    stateText = "Starting next segment",
                    observations = emptyList(),
                ),
            )
        }
        speak("At the island. Observation restarting for the next part of the road.")
        observeJob = scope.launch { observeLoop(startIndex = 2) }
    }

    private fun stopObservation() {
        if (_state.value.sessionMode != SessionMode.CROSSING_ASSIST) return
        observeJob?.cancel()
        // The engine passes through EXITED; the fake goes straight back to INACTIVE in one update.
        val back = if (modeBeforeCrossing == SessionMode.NAVIGATING && route != null) SessionMode.NAVIGATING else modeBeforeCrossing
        _state.update { it.withMode(back).copy(crossing = CrossingUi()) }
        speak("Traffic observation ended.")
    }

    // ---------------------------------------------------------------- misc

    private fun toggleTorch() {
        val s = _state.value
        if (!s.torch.available) {
            speak("Torch is available only while the camera is on.")
            return
        }
        val on = !s.torch.on
        _state.update { it.copy(torch = it.torch.copy(on = on)) }
        speak(if (on) "Torch on." else "Torch off.")
    }

    private fun savePlace(rawName: String) {
        val name = rawName.trim().ifEmpty { "Saved place" }
        val id = "saved-${++idCounter}"
        val place = Place(
            name = name,
            point = FakeSampleData.userLocation,
            address = "Anna Salai, Thousand Lights, Chennai",
            category = "saved",
            distanceM = 0.0,
        )
        _state.update { it.copy(savedPlaces = it.savedPlaces + SavedPlaceUi(id, name, place)) }
        speak("Saved current location as $name.")
    }

    private fun confirmSos() {
        if (_state.value.sos !is SosUi.AwaitingConfirmation) return
        _state.update { it.copy(sos = SosUi.Sending) }
        sosJob = scope.launch {
            delay(tickMs)
            val msg = "Demo mode: no message was sent. In the full app your contacts receive a text with your location."
            _state.update { it.copy(sos = SosUi.Sent(msg)) }
            speak(msg)
        }
    }

    private fun setMode(mode: SessionMode) {
        _state.update { it.withMode(mode) }
    }

    /** Mode change plus the camera/torch state that goes with it, as one atomic transition. */
    private fun AppUiState.withMode(mode: SessionMode): AppUiState {
        val cameraOn = mode != SessionMode.IDLE
        return copy(
            sessionMode = mode,
            camera = if (cameraOn) RUNNING_CAMERA else IDLE_CAMERA,
            torch = if (cameraOn) torch.copy(available = true, lowLight = false) else TorchUi(),
        )
    }

    private fun speak(text: String) {
        _state.update { it.copy(lastSpoken = text) }
        log("Spoke: $text")
    }

    private fun log(line: String) {
        val n = ++logCounter
        _state.update { it.copy(debugLog = (it.debugLog + "#$n $line").takeLast(MAX_LOG)) }
    }

    /** Simulated route walked at a fixed number of metres per tick. */
    private class SimRoute(val destination: Place, val totalM: Double) {
        private val legs: List<Leg> = LEG_TEMPLATE.map { (fraction, text, crossing) ->
            Leg(totalM * fraction, text.replace("{dest}", destination.name), crossing)
        }
        private var legIndex = 0
        private var legRemainingM = legs.first().lengthM
        private val stepM = (totalM / TICKS_PER_ROUTE).coerceAtLeast(5.0)
        var arrived = false
            private set

        val startInstruction = "Head north-east on Anna Salai, then turn left onto Greams Road."

        private val traveledM: Double
            get() = legs.take(legIndex).sumOf { it.lengthM } + (legs.getOrNull(legIndex)?.lengthM ?: 0.0) - legRemainingM

        /** Walks one tick. Returns the maneuver text reached this tick, if any. */
        fun advance(): String? {
            if (arrived) return null
            legRemainingM -= stepM
            if (legRemainingM > 0) return null
            val reached = legs[legIndex].instruction
            if (legIndex == legs.lastIndex) {
                arrived = true
                legRemainingM = 0.0
                return "You have arrived. ${destination.name} is on your right."
            }
            legIndex++
            legRemainingM = legs[legIndex].lengthM
            return reached
        }

        fun toUi(): NavigationUi {
            val leg = legs[legIndex]
            val remaining = (totalM - traveledM).coerceAtLeast(0.0)
            return NavigationUi(
                destinationName = destination.name,
                currentInstruction = if (arrived) "You have arrived at ${destination.name}." else leg.instruction,
                distanceToNextText = if (arrived) null else "In ${FakeSampleData.distanceText(legRemainingM)}",
                nextInstruction = if (arrived) null else legs.getOrNull(legIndex + 1)?.instruction,
                progressFraction = if (arrived) 1f else (traveledM / totalM).toFloat().coerceIn(0f, 1f),
                remainingText = if (arrived) null else
                    "${FakeSampleData.distanceText(remaining)} left, ${FakeSampleData.minutesText(remaining)}",
                hasArrived = arrived,
                upcomingCrossingText = if (!arrived && legIndex <= 1) {
                    val toCrossing = legs.take(2).sumOf { it.lengthM } - traveledM
                    "Possible crossing in ${FakeSampleData.distanceText(toCrossing)}"
                } else {
                    null
                },
            )
        }

        private data class Leg(val lengthM: Double, val instruction: String, val crossing: Boolean)

        companion object {
            const val TICKS_PER_ROUTE = 40.0

            /** (fraction of route, maneuver at the END of the leg, is crossing). */
            val LEG_TEMPLATE = listOf(
                Triple(0.30, "Turn left onto Greams Road.", false),
                Triple(0.20, "Possible crossing at Greams Road junction. Stop and assess your surroundings.", true),
                Triple(0.30, "Turn right onto Haddows Road.", false),
                Triple(0.20, "{dest} is ahead on your right.", false),
            )

            fun forPlace(place: Place): SimRoute {
                // Walking routes are typically ~30 % longer than the straight line.
                val straight = place.distanceM ?: 800.0
                return SimRoute(place, (straight * 1.3).coerceAtLeast(120.0))
            }
        }
    }

    companion object {
        const val DEFAULT_TICK_MS = 1_500L
        const val MAX_OBSERVATIONS = 6
        const val MAX_LOG = 200
        const val ALERT_WARNING_TICK = 6
        const val ALERT_CRITICAL_TICK = 18

        private val IDLE_CAMERA = CameraStatusUi(
            level = StatusLevel.OFF,
            text = "Camera off",
            detectorModelName = "Demo detector",
            isMockDetector = true,
            supportsVehicles = true,
        )
        private val RUNNING_CAMERA = IDLE_CAMERA.copy(level = StatusLevel.GOOD, text = "Camera running")

        fun initialState(): AppUiState = AppUiState(
            gps = GpsStatusUi(StatusLevel.GOOD, "GPS good", FakeSampleData.USER_ACCURACY_M),
            heading = HeadingUi(text = "Facing north-east", reliable = true),
            camera = IDLE_CAMERA,
            savedPlaces = FakeSampleData.savedPlaces,
        )
    }
}
