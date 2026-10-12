package com.visionnav.app.engine

import com.visionnav.core.announce.Announcement
import com.visionnav.core.announce.AnnouncementPriority
import com.visionnav.core.announce.AudioQueueManager
import com.visionnav.core.command.VoiceCommand
import com.visionnav.core.command.VoiceCommandParser
import com.visionnav.core.common.Outcome
import com.visionnav.core.common.ServiceError
import com.visionnav.core.common.TimeSource
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.crossing.CameraOrientationEstimate
import com.visionnav.core.crossing.CameraOrientationEstimator
import com.visionnav.core.crossing.CrossingAssistEvent
import com.visionnav.core.crossing.CrossingAssistState
import com.visionnav.core.crossing.CrossingAssistStateMachine
import com.visionnav.core.crossing.CrossingPhrases
import com.visionnav.core.crossing.RoadContextInterpreter
import com.visionnav.core.crossing.RoadCrossingContext
import com.visionnav.core.crossing.RoadMetadataService
import com.visionnav.core.crossing.TrafficObservationReporter
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.navigation.GeocodingService
import com.visionnav.core.navigation.NavigationEvent
import com.visionnav.core.navigation.NavigationPhrases
import com.visionnav.core.navigation.Place
import com.visionnav.core.navigation.Route
import com.visionnav.core.navigation.RouteProgressTracker
import com.visionnav.core.navigation.RoutingService
import com.visionnav.core.motion.MotionEstimator
import com.visionnav.core.safety.SafetyAnnouncer
import com.visionnav.core.safety.SafetyPhrases
import com.visionnav.core.safety.SafetyRiskEngine
import com.visionnav.core.safety.SafetySeverity
import com.visionnav.core.scene.CrosswalkPersistenceGate
import com.visionnav.core.scene.PathAnalyzer
import com.visionnav.core.scene.SceneDescriber
import com.visionnav.core.sensing.ActivitySource
import com.visionnav.core.sensing.LocationFix
import com.visionnav.core.sensing.LocationSource
import com.visionnav.core.sensing.LocationState
import com.visionnav.core.sensing.OrientationSource
import com.visionnav.core.tracking.DetectionFilter
import com.visionnav.core.tracking.MotionEstimate
import com.visionnav.core.tracking.ObjectTracker
import com.visionnav.core.tracking.Track
import com.visionnav.core.vision.AnalysisDemand
import com.visionnav.core.vision.CameraState
import com.visionnav.core.vision.FrameFeed
import com.visionnav.core.vision.FrameObservation
import com.visionnav.platform.power.PowerGovernor
import com.visionnav.platform.sos.SosMessenger
import com.visionnav.platform.sos.SosResult
import com.visionnav.platform.speech.SpeechInput
import com.visionnav.platform.speech.SpeechResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The orchestrator: one instance per process, owning every pipeline and all per-session state.
 *
 * Threading: every mutation happens on [engineDispatcher], a single-threaded view of
 * Dispatchers.Default. The crossing state machine, reporter, tracker and route tracker are not
 * thread-safe and are only touched from there. Public methods just post work to it.
 *
 * Safety architecture:
 * - The vision safety pipeline (tracker → motion → risk engine → announcer) runs on **every** frame
 *   in every mode; crossing observation only adds descriptions on top of it.
 * - All speech goes through the single [AudioQueueManager] (priority preemption + SafetyPhraseGuard).
 * - The walking/navigation heading comes from GPS course only. Camera orientation is used solely
 *   for wording ("from your left") and never written back into navigation.
 * - One camera session ([FrameFeed]); this class only registers demand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class VisionNavEngine @Inject constructor(
    private val time: TimeSource,
    private val audio: AudioQueueManager,
    private val frameFeed: FrameFeed,
    private val locationSource: LocationSource,
    private val orientationSource: OrientationSource,
    private val activitySource: ActivitySource,
    private val routing: RoutingService,
    private val geocoding: GeocodingService,
    private val roadMetadata: RoadMetadataService,
    private val speechInput: SpeechInput,
    private val sosMessenger: SosMessenger,
    private val powerGovernor: PowerGovernor,
    private val savedPlaces: SavedPlacesStore,
    private val log: SessionLog,
) {
    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<EngineEffect>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effects: SharedFlow<EngineEffect> = _effects.asSharedFlow()

    private var settings = EngineSettings()

    // ---- perception & safety (Phase 5 / 6) ----
    private val detectionFilter = DetectionFilter()
    private val tracker = ObjectTracker()
    private val motionEstimator = MotionEstimator()
    private val riskEngine = SafetyRiskEngine()
    private val safetyAnnouncer = SafetyAnnouncer()
    private val sceneDescriber = SceneDescriber()
    private val pathAnalyzer = PathAnalyzer()
    private val crosswalkGate = CrosswalkPersistenceGate()
    private var latestTracks: List<Track> = emptyList()
    private var latestEstimates: Map<Long, MotionEstimate> = emptyMap()
    private var lastFrameMs: Long? = null
    /** Pending "What's around" requests counting analysed frames (engine thread only). */
    private val sceneGates = mutableListOf<SceneWarmupGate>()
    private var lastPathHintMs = Long.MIN_VALUE / 2
    private var lowLightSinceMs: Long? = null
    private var lowLightHandled = false

    // ---- crossing (Phase 7) ----
    private val orientationEstimator = CameraOrientationEstimator()
    private val interpreter = RoadContextInterpreter()
    private val reporter = TrafficObservationReporter(onDefect = { log.log("DEFECT", "Blocked crossing phrase: $it") })
    private val crossing = CrossingAssistStateMachine(
        frameFeed = frameFeed,
        // Shared tracker: a reset between crossing segments also restarts safety tracks for ~2 frames,
        // which is acceptable while the user is standing at a refuge and keeps a single tracker.
        resetObservationHistory = { tracker.reset(); latestTracks = emptyList(); latestEstimates = emptyMap() },
        timeSource = time,
    )
    private var crossingContext: RoadCrossingContext? = null
    private var lastContextFetchPoint: GeoPoint? = null
    private var lastContextFetchMs = Long.MIN_VALUE / 2
    private var contextJob: Job? = null

    // ---- navigation (Phase 4) ----
    private val navPhrases = NavigationPhrases()
    private var routeTracker: RouteProgressTracker? = null
    private var rerouteJob: Job? = null

    private var sessionJobs: List<Job> = emptyList()
    private var sensorsActive = false
    private var voiceParser = VoiceCommandParser()

    init {
        scope.launch { audio.speaking.collect { a -> if (a != null) log.log("SAY", "${a.priority}: ${a.text}") } }
        scope.launch { frameFeed.cameraState.collect(::onCameraState) }
        scope.launch { frameFeed.frames.collect(::onFrame) }
        scope.launch { crossing.state.collect { s -> _state.update { it.copy(crossingState = s) } } }
        scope.launch { crossing.stage.collect { s -> _state.update { it.copy(crossingStage = s) } } }
        scope.launch {
            powerGovernor.mode.collect { m ->
                _state.update { it.copy(performanceMode = m.name) }
                log.log("POWER", m.name)
            }
        }
        scope.launch { savedPlaces.places.collect { list -> voiceParser = VoiceCommandParser(list.map { it.name }) } }
    }

    // =====================================================================================
    // Settings
    // =====================================================================================

    fun applySettings(s: EngineSettings) = post {
        settings = s
        audio.setVerbosity(s.verbosity)
        audio.setMuted(s.hapticsOnly)
        navPhrases.directionStyle = s.directionStyle
        refreshCameraDemand()
    }

    // =====================================================================================
    // Session control
    // =====================================================================================

    private fun startSensors() {
        if (sensorsActive) return
        sensorsActive = true
        locationSource.start()
        orientationSource.start()
        activitySource.start()
        powerGovernor.start()
        sessionJobs = listOf(
            scope.launch { locationSource.state.collect(::onLocation) },
            scope.launch {
                orientationSource.state.collect { o ->
                    val est = orientationEstimator.estimate(o)
                    _state.update { it.copy(orientation = est) }
                }
            },
            scope.launch { activitySource.activity.collect { a -> crossing.onActivity(a).speak() } },
            scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    onTick()
                }
            },
        )
        log.log("SESSION", "sensors started")
    }

    private fun stopSensorsIfIdle() {
        val s = _state.value
        if (s.mode != SessionMode.IDLE || !sensorsActive) return
        sessionJobs.forEach { it.cancel() }
        sessionJobs = emptyList()
        locationSource.stop()
        orientationSource.stop()
        activitySource.stop()
        powerGovernor.stop()
        orientationEstimator.reset()
        sensorsActive = false
        log.log("SESSION", "sensors stopped")
    }

    private fun setMode(mode: SessionMode) {
        _state.update { it.copy(mode = mode) }
        log.log("MODE", mode.name)
        refreshCameraDemand()
        if (mode == SessionMode.IDLE) stopSensorsIfIdle() else startSensors()
    }

    /** Camera demand from navigation/exploring. Crossing observation registers its own demand. */
    private fun refreshCameraDemand() {
        val s = _state.value
        val demand = when (s.mode) {
            SessionMode.NAVIGATING -> if (settings.obstacleDetectionWhileNavigating) AnalysisDemand.WALKING else AnalysisDemand.NONE
            SessionMode.EXPLORING -> AnalysisDemand.WALKING
            SessionMode.CROSSING_ASSIST -> AnalysisDemand.LOW
            SessionMode.IDLE -> AnalysisDemand.NONE
        }
        frameFeed.setDemand(CLIENT_SESSION, demand)
    }

    // =====================================================================================
    // Perception
    // =====================================================================================

    private fun onCameraState(cs: CameraState) {
        _state.update {
            it.copy(camera = cs, torchOn = (cs as? CameraState.Running)?.torchOn ?: false)
        }
        crossing.onCameraState(cs).speak()
        if (cs is CameraState.Unavailable) log.log("CAMERA", "unavailable: ${cs.reason}")
    }

    private fun onFrame(obs: FrameObservation) {
        val now = obs.elapsedMs
        lastFrameMs = now
        if (sceneGates.isNotEmpty()) {
            val ready = SceneWarmupGate.isDetectorReady(_state.value.camera)
            sceneGates.forEach { it.onFrame(now, ready) }
        }
        val orientationState = orientationSource.state.value
        val yaw = (orientationState as? com.visionnav.core.sensing.OrientationState.Available)?.orientation?.yawRateRadPerSec

        // Phase 5: safety pipeline, always on.
        val detections = detectionFilter.filter(obs.detections)
        val tracks = tracker.update(detections, now, yaw)
        val estimates = motionEstimator.estimateAll(tracks, now, obs.horizontalFovDeg)
        latestTracks = tracks
        latestEstimates = estimates
        val events = riskEngine.evaluate(tracks, estimates, now)
        safetyAnnouncer.announcementsFor(events).forEach(::say)
        val top = events.firstOrNull { it.severity >= SafetySeverity.WARNING }

        // Phase 5B: visibility.
        val issues = obs.quality?.issues().orEmpty()
        handleLowLight(issues, now)

        // Phase 6B: walking-path hint while navigating.
        if (_state.value.mode == SessionMode.NAVIGATING && now - lastPathHintMs > PATH_HINT_INTERVAL_MS) {
            pathAnalyzer.analyze(tracks, now).announcement?.let {
                lastPathHintMs = now
                say(it)
            }
        }

        // Phase 6C: visual crosswalk evidence (announced once per appearance).
        val wasActive = crosswalkGate.isActive
        val visual = crosswalkGate.update(obs.crosswalk, now)
        if (visual != null && !wasActive && _state.value.mode != SessionMode.IDLE) {
            say(Announcement(CrossingPhrases.VISUAL_CROSSWALK, AnnouncementPriority.OBSERVATION, "crosswalk:visual", ttlMs = 3_000))
        }

        // Phase 7: crossing observation.
        crossing.onFrameQualityIssues(issues.toSet()).speak()
        val orientation = _state.value.orientation ?: orientationEstimator.estimate(orientationState)
        reporter.onMotion(estimates.values.toList(), orientation, crossingContext, obs.horizontalFovDeg, now).forEach(::sayObservation)

        _state.update { st ->
            st.copy(
                qualityIssues = issues,
                horizontalFovDeg = obs.horizontalFovDeg,
                safetyAlert = top?.let { SafetyAlert(it.severity, SafetyPhrases.text(it), now) }
                    ?: st.safetyAlert?.takeIf { now - it.elapsedMs < ALERT_HOLD_MS },
                debugTracks = tracks.map { t ->
                    val e = estimates[t.id]
                    DebugTrack(
                        t.id, t.objectClass, t.latest.box,
                        e?.imageMotion ?: com.visionnav.core.tracking.ImageMotion.UNKNOWN,
                        e?.approach ?: com.visionnav.core.tracking.ApproachState.UNKNOWN,
                        t.latest.confidence,
                    )
                },
            )
        }
    }

    private fun handleLowLight(issues: List<UncertaintyReason>, now: Long) {
        if (UncertaintyReason.LOW_LIGHT !in issues) {
            lowLightSinceMs = null
            lowLightHandled = false
            return
        }
        val since = lowLightSinceMs ?: now.also { lowLightSinceMs = it }
        if (lowLightHandled || now - since < LOW_LIGHT_PERSIST_MS) return
        lowLightHandled = true
        val cam = _state.value.camera as? CameraState.Running ?: return
        if (cam.torchOn) return
        if (settings.autoTorch && cam.hasTorch) {
            frameFeed.setTorch(true)
            say(EnginePhrases.info(EnginePhrases.LOW_LIGHT_AUTO, "lowlight"))
        } else if (cam.hasTorch) {
            say(EnginePhrases.info(EnginePhrases.LOW_LIGHT_SUGGEST, "lowlight"))
        }
    }

    // =====================================================================================
    // Location, ticks, crossing context
    // =====================================================================================

    private fun onLocation(ls: LocationState) {
        _state.update { it.copy(location = ls) }
        val fix = (ls as? LocationState.Available)?.fix ?: return
        routeTracker?.let { rt ->
            val update = rt.update(fix)
            _state.update { it.copy(progress = update.progress) }
            handleNavigationEvents(update.events, fix)
        }
        maybeRefreshCrossingContext(fix)
    }

    private fun onTick() {
        val now = time.elapsedMs()
        audio.tick()
        crossing.tick(now).speak()
        reporter.tick(now).forEach(::sayObservation)
        routeTracker?.let { handleNavigationEvents(it.tick(now), currentFix()) }
        if (lastFrameMs != null && now - lastFrameMs!! > STALE_FRAME_MS) {
            latestTracks = emptyList()
            latestEstimates = emptyMap()
            _state.update { it.copy(debugTracks = emptyList()) }
        }
        if (crossingContext == null && _state.value.mode != SessionMode.IDLE && currentFix() == null) {
            // No GPS: still let the state machine know it has no location (never fabricated).
            val ctx = interpreter.interpret(null, null, _state.value.route, now)
            crossingContext = ctx
            crossing.onContext(ctx).speak()
        }
    }

    private fun maybeRefreshCrossingContext(fix: LocationFix) {
        if (_state.value.mode == SessionMode.IDLE) return
        val now = time.elapsedMs()
        val moved = lastContextFetchPoint?.let { GeoMath.distanceM(it, fix.point) } ?: Double.MAX_VALUE
        if (contextJob?.isActive == true) return
        if (moved < CONTEXT_REFETCH_M && now - lastContextFetchMs < CONTEXT_REFETCH_MS) {
            // Re-interpret cached metadata with the new fix so distances/userPosition stay current.
            crossingContext?.let { _ -> refreshContextFromCache(fix, now) }
            return
        }
        lastContextFetchPoint = fix.point
        lastContextFetchMs = now
        contextJob = scope.launch {
            val meta = when (val r = roadMetadata.roadsNear(fix.point, CONTEXT_RADIUS_M)) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> {
                    log.log("MAP", "road metadata failed: ${r.error}")
                    null
                }
            }
            cachedMetadata = meta
            val ctx = interpreter.interpret(meta, currentFix() ?: fix, _state.value.route, time.elapsedMs())
            crossingContext = ctx
            crossing.onContext(ctx).speak()
        }
    }

    private var cachedMetadata: com.visionnav.core.crossing.RoadMetadata? = null

    private fun refreshContextFromCache(fix: LocationFix, now: Long) {
        val ctx = interpreter.interpret(cachedMetadata, fix, _state.value.route, now)
        crossingContext = ctx
        crossing.onContext(ctx).speak()
    }

    private fun currentFix(): LocationFix? = (locationSource.state.value as? LocationState.Available)?.fix

    // =====================================================================================
    // Navigation
    // =====================================================================================

    fun search(query: String) = post {
        val q = query.trim()
        if (q.isEmpty()) return@post
        _state.update { it.copy(searchQuery = q, searchLoading = true, searchError = null, searchResults = emptyList()) }
        startSensors()
        val near = currentFix()?.point
        when (val r = geocoding.search(q, near, limit = 6)) {
            is Outcome.Success -> {
                _state.update { it.copy(searchLoading = false, searchResults = r.value) }
                if (r.value.isEmpty()) say(EnginePhrases.info(EnginePhrases.NO_RESULTS, "search"))
                else say(EnginePhrases.info(EnginePhrases.results(r.value.size, r.value.first().name), "search"))
            }
            is Outcome.Failure -> {
                _state.update { it.copy(searchLoading = false, searchError = EnginePhrases.SEARCH_FAILED) }
                say(EnginePhrases.error(EnginePhrases.SEARCH_FAILED, "search"))
            }
        }
        stopSensorsIfIdle()
    }

    /** Computes a route to [place] for preview without starting guidance. */
    fun previewRoute(place: Place) = post {
        _state.update { it.copy(previewPlace = place, previewRoute = null, previewLoading = true, previewError = null) }
        when (val r = computeRoute(place)) {
            is Outcome.Success -> {
                previewComputedAtMs = time.elapsedMs()
                _state.update { it.copy(previewRoute = r.value, previewLoading = false) }
            }
            is Outcome.Failure -> _state.update { it.copy(previewLoading = false, previewError = routeErrorText(r.error)) }
        }
        stopSensorsIfIdle()
    }

    /** Starts guidance to the previewed place, reusing a fresh preview route when available. */
    fun startNavigation() = post {
        val place = _state.value.previewPlace ?: return@post
        val fresh = _state.value.previewRoute?.takeIf { time.elapsedMs() - previewComputedAtMs < PREVIEW_REUSE_MS }
        beginNavigation(place, fresh)
    }

    /** Routes straight to [place] (voice commands, saved places). */
    fun startNavigation(place: Place) = post { beginNavigation(place, null) }

    private var previewComputedAtMs = Long.MIN_VALUE / 2

    private suspend fun computeRoute(place: Place): Outcome<Route> {
        startSensors()
        val fix = awaitFix() ?: return Outcome.Failure(ServiceError.Unknown(NO_LOCATION))
        // Heading hint from GPS course only (walking direction), never from the camera.
        return routing.walkingRoute(fix.point, place.point, fix.courseDeg?.toDouble())
    }

    private fun routeErrorText(e: ServiceError): String = when (e) {
        is ServiceError.Unknown -> if (e.message == NO_LOCATION) EnginePhrases.LOCATION_UNAVAILABLE else EnginePhrases.ROUTE_FAILED
        ServiceError.Offline, ServiceError.Timeout, ServiceError.RateLimited -> EnginePhrases.ROUTE_OFFLINE
        else -> EnginePhrases.ROUTE_FAILED
    }

    private suspend fun beginNavigation(place: Place, precomputed: Route?) {
        _state.update { it.copy(destination = place, routeLoading = true) }
        when (val result = precomputed?.let { Outcome.Success(it) } ?: computeRoute(place)) {
            is Outcome.Success -> {
                val route = result.value
                if (_state.value.crossingState != CrossingAssistState.INACTIVE) crossing.onUserStop().speak()
                routeTracker = RouteProgressTracker(route)
                _state.update { it.copy(route = route, routeLoading = false, guidancePaused = false, previewRoute = null) }
                setMode(SessionMode.NAVIGATING)
                say(navPhrases.routeStart(route, place.name))
                lastContextFetchPoint = null
                currentFix()?.let(::maybeRefreshCrossingContext)
            }
            is Outcome.Failure -> {
                _state.update { it.copy(routeLoading = false, destination = null) }
                val text = routeErrorText(result.error)
                say(EnginePhrases.error(text, "nav"))
                addError(text)
                log.log("NAV", "route failed: ${result.error}")
                stopSensorsIfIdle()
            }
        }
    }

    fun clearSearch() = post {
        _state.update {
            it.copy(searchQuery = "", searchResults = emptyList(), searchError = null, previewPlace = null, previewRoute = null, previewError = null)
        }
    }

    fun startExploring() = post { if (_state.value.mode == SessionMode.IDLE) setMode(SessionMode.EXPLORING) }

    fun stopExploring() = post { if (_state.value.mode == SessionMode.EXPLORING) setMode(SessionMode.IDLE) }

    fun renamePlace(id: String, name: String) = post { savedPlaces.rename(id, name) }

    fun deletePlace(id: String) = post { savedPlaces.delete(id) }

    fun dismissError(id: Long) = post { _state.update { s -> s.copy(errors = s.errors.filterNot { it.id == id }) } }

    fun dismissAlert() = post { _state.update { it.copy(safetyAlert = null) } }

    private var errorIds = 0L

    private fun addError(text: String) {
        _state.update { s -> s.copy(errors = (s.errors + EngineError(++errorIds, text)).takeLast(3)) }
    }

    fun stopNavigation() = post { endNavigation(announce = true) }

    private fun endNavigation(announce: Boolean) {
        val wasNavigating = routeTracker != null
        routeTracker = null
        rerouteJob?.cancel()
        crossing.onRouteEnded().speak()
        _state.update { it.copy(route = null, progress = null, destination = null, currentInstruction = null, guidancePaused = false) }
        if (announce && wasNavigating) say(EnginePhrases.info(EnginePhrases.NAVIGATION_STOPPED, "nav"))
        resetCrossingContext()
        setMode(SessionMode.IDLE)
    }

    private fun handleNavigationEvents(events: List<NavigationEvent>, fix: LocationFix?) {
        val name = _state.value.destination?.name
        for (e in events) {
            log.log("NAV", e::class.simpleName ?: "event")
            when (e) {
                is NavigationEvent.RerouteNeeded -> reroute(e)
                is NavigationEvent.Arrived -> {
                    navPhrases.forEvent(e, name)?.let(::say)
                    endNavigation(announce = false)
                    return
                }
                else -> {
                    if (_state.value.guidancePaused && e !is NavigationEvent.GpsDegraded) continue
                    navPhrases.forEvent(e, name)?.let { a ->
                        _state.update { it.copy(currentInstruction = a.text) }
                        say(a)
                    }
                }
            }
        }
    }

    private fun reroute(e: NavigationEvent.RerouteNeeded) {
        val dest = _state.value.destination ?: return
        if (rerouteJob?.isActive == true) return
        rerouteJob = scope.launch {
            when (val r = routing.walkingRoute(e.from, dest.point, e.headingDeg)) {
                is Outcome.Success -> {
                    routeTracker?.setRoute(r.value)
                    _state.update { it.copy(route = r.value) }
                    log.log("NAV", "rerouted (attempt ${e.attempt})")
                }
                is Outcome.Failure -> log.log("NAV", "reroute failed: ${r.error}")
            }
        }
    }

    private suspend fun awaitFix(timeoutMs: Long = 8_000): LocationFix? {
        val deadline = time.elapsedMs() + timeoutMs
        while (time.elapsedMs() < deadline) {
            currentFix()?.let { return it }
            delay(250)
        }
        return null
    }

    // =====================================================================================
    // Crossing assist
    // =====================================================================================

    fun startTrafficObservation() = post {
        if (_state.value.mode == SessionMode.IDLE) setMode(SessionMode.CROSSING_ASSIST) else startSensors()
        if (_state.value.crossingState in OBSERVING_STATES) {
            val now = time.elapsedMs()
            val cam = _state.value.camera
            val framesFresh = lastFrameMs?.let { now - it <= STALE_FRAME_MS } == true
            when {
                // Never "No vehicles recognised" from a camera that is not delivering analysed frames.
                cam is CameraState.Unavailable && cam.reason != UncertaintyReason.DETECTOR_UNAVAILABLE ->
                    sayObservation(EnginePhrases.error(EnginePhrases.CAMERA_UNAVAILABLE, "traffic"))
                cam is CameraState.Unavailable || (cam is CameraState.Running && cam.capabilities?.isMock == true) ->
                    sayObservation(EnginePhrases.error(EnginePhrases.DETECTOR_UNAVAILABLE, "traffic"))
                !SceneWarmupGate.isDetectorReady(cam) || !framesFresh ->
                    sayObservation(EnginePhrases.info(EnginePhrases.CAMERA_STARTING, "traffic"))
                else -> {
                    val orientation = _state.value.orientation ?: orientationEstimator.estimate(orientationSource.state.value)
                    reporter.describeOnRequest(latestEstimates.values.toList(), orientation, now).forEach(::sayObservation)
                }
            }
        } else {
            crossing.onUserRequestObservation().speak()
        }
        currentFix()?.let(::maybeRefreshCrossingContext)
    }

    fun stopTrafficObservation() = post {
        crossing.onUserStop().speak()
        if (_state.value.mode == SessionMode.CROSSING_ASSIST) {
            resetCrossingContext()
            setMode(SessionMode.IDLE)
        }
    }

    fun confirmAtCrossing() = post { crossing.onUserConfirmedAtCrossing().speak() }

    fun nextSegment() = post { crossing.onUserNextSegment().speak() }

    fun confirmRefuge() = post { crossing.onUserConfirmedRefuge().speak() }

    private fun resetCrossingContext() {
        crossingContext = null
        cachedMetadata = null
        lastContextFetchPoint = null
        contextJob?.cancel()
        reporter.reset()
        crosswalkGate.reset()
        _state.update { it.copy(observationLines = emptyList(), crossingContextText = null) }
    }

    // =====================================================================================
    // Scene, location queries
    // =====================================================================================

    /**
     * Describes the scene once enough frames have been analysed **since this request** by a ready
     * detector ([SceneWarmupGate]); a cold camera or a detector still loading never produces
     * "Nothing recognised".
     */
    fun whatsAround() = post {
        val previousMode = _state.value.mode
        if (previousMode == SessionMode.IDLE) setMode(SessionMode.EXPLORING)
        val gate = SceneWarmupGate(requestMs = time.elapsedMs())
        sceneGates += gate
        try {
            if (!SceneWarmupGate.isDetectorReady(_state.value.camera) && _state.value.camera !is CameraState.Unavailable) {
                say(EnginePhrases.info(EnginePhrases.CAMERA_STARTING, "scene"))
            }
            var verdict = gate.verdict(time.elapsedMs(), _state.value.camera)
            while (verdict == SceneWarmupGate.Verdict.WAIT) {
                delay(SCENE_POLL_MS)
                verdict = gate.verdict(time.elapsedMs(), _state.value.camera)
            }
            log.log("SCENE", "$verdict after ${gate.analysedFrames} frames")
            when (verdict) {
                SceneWarmupGate.Verdict.READY, SceneWarmupGate.Verdict.PARTIAL -> {
                    val desc = sceneDescriber.describe(latestTracks, time.elapsedMs(), _state.value.qualityIssues)
                    say(desc.toAnnouncement().copy(priority = AnnouncementPriority.NAVIGATION))
                }
                SceneWarmupGate.Verdict.NOT_READY -> say(EnginePhrases.error(EnginePhrases.CAMERA_NOT_READY, "scene"))
                SceneWarmupGate.Verdict.DETECTOR_UNAVAILABLE -> say(EnginePhrases.error(EnginePhrases.DETECTOR_UNAVAILABLE, "scene"))
                SceneWarmupGate.Verdict.CAMERA_UNAVAILABLE, SceneWarmupGate.Verdict.WAIT ->
                    say(EnginePhrases.error(EnginePhrases.CAMERA_UNAVAILABLE, "scene"))
            }
        } finally {
            sceneGates -= gate
        }
        if (previousMode == SessionMode.IDLE && _state.value.mode == SessionMode.EXPLORING) setMode(SessionMode.IDLE)
    }

    fun whereAmI() = post {
        startSensors()
        val fix = awaitFix(6_000)
        if (fix == null) {
            say(navPhrases.whereAmIAnnouncement(null, null, null))
            stopSensorsIfIdle()
            return@post
        }
        val place = (geocoding.reverse(fix.point) as? Outcome.Success)?.value
        // "Facing" uses camera orientation only when it is reliable (phone upright, steady).
        val facing = _state.value.orientation?.takeIf { it.isReliable }?.cameraBearingDeg
        say(navPhrases.whereAmIAnnouncement(place, fix, facing))
        stopSensorsIfIdle()
    }

    fun repeatLast() = post { audio.repeatLast() }

    fun setTorch(on: Boolean) = post {
        val cam = _state.value.camera
        when {
            cam is CameraState.Running && !cam.hasTorch -> say(EnginePhrases.info(EnginePhrases.TORCH_UNAVAILABLE, "torch"))
            cam is CameraState.Running -> {
                frameFeed.setTorch(on)
                say(EnginePhrases.confirm(if (on) EnginePhrases.TORCH_ON else EnginePhrases.TORCH_OFF, "torch"))
            }
            else -> say(EnginePhrases.info(EnginePhrases.CAMERA_UNAVAILABLE, "torch"))
        }
    }

    fun savePlace(name: String) = post {
        val fix = awaitFix(6_000) ?: run {
            say(EnginePhrases.error(EnginePhrases.LOCATION_UNAVAILABLE, "save"))
            return@post
        }
        val label = (geocoding.reverse(fix.point) as? Outcome.Success)?.value?.address
        savedPlaces.save(Place(name, fix.point, label, "saved", null))
        say(EnginePhrases.confirm(EnginePhrases.savedPlace(name), "save"))
    }

    // =====================================================================================
    // Voice commands
    // =====================================================================================

    fun listenForCommand() = post {
        audio.stopAll()
        _state.update { it.copy(isListening = true) }
        val result = speechInput.listenOnce()
        _state.update { it.copy(isListening = false) }
        when (result) {
            is SpeechResult.Success -> {
                _state.update { it.copy(voiceTranscript = result.alternatives.firstOrNull()) }
                handleCommand(voiceParser.parse(result.alternatives))
            }
            SpeechResult.NoMatch -> say(EnginePhrases.info(EnginePhrases.NO_SPEECH, "voice"))
            SpeechResult.PermissionDenied -> say(EnginePhrases.error(EnginePhrases.MIC_PERMISSION, "voice"))
            SpeechResult.Unavailable -> say(EnginePhrases.error(EnginePhrases.VOICE_UNAVAILABLE, "voice"))
            is SpeechResult.Error -> say(EnginePhrases.info(EnginePhrases.NO_SPEECH, "voice"))
        }
    }

    private suspend fun handleCommand(cmd: VoiceCommand) {
        log.log("VOICE", cmd.toString())
        when (cmd) {
            is VoiceCommand.NavigateTo -> {
                say(EnginePhrases.info(EnginePhrases.searching(cmd.query), "search"))
                search(cmd.query).join()
                _state.value.searchResults.firstOrNull()?.let { beginNavigation(it, null) }
            }
            is VoiceCommand.GoToSavedPlace -> {
                val p = savedPlaces.find(cmd.name)
                if (p == null) say(EnginePhrases.info(EnginePhrases.unknownSavedPlace(cmd.name), "saved"))
                else beginNavigation(p, null)
            }
            VoiceCommand.WhereAmI -> whereAmI()
            VoiceCommand.WhatsAround -> whatsAround()
            VoiceCommand.TrafficAssist -> startTrafficObservation()
            VoiceCommand.NextSegment -> nextSegment()
            VoiceCommand.ConfirmAtCrossing -> confirmAtCrossing()
            VoiceCommand.RepeatLast -> audio.repeatLast()
            VoiceCommand.Stop -> {
                if (_state.value.crossingState != CrossingAssistState.INACTIVE) stopTrafficObservation()
                else if (routeTracker != null) stopNavigation()
                else audio.stopAll()
            }
            VoiceCommand.PauseGuidance -> {
                _state.update { it.copy(guidancePaused = true) }
                say(EnginePhrases.info(EnginePhrases.GUIDANCE_PAUSED, "guidance"))
            }
            VoiceCommand.ResumeGuidance -> {
                _state.update { it.copy(guidancePaused = false) }
                say(EnginePhrases.info(EnginePhrases.GUIDANCE_RESUMED, "guidance"))
            }
            VoiceCommand.Sos -> requestSos()
            VoiceCommand.HelpCommands -> say(EnginePhrases.info(EnginePhrases.HELP, "help"))
            is VoiceCommand.SaveCurrentPlace ->
                if (cmd.name.isNullOrBlank()) say(EnginePhrases.info(EnginePhrases.SAVE_PLACE_NEEDS_NAME, "save")) else savePlace(cmd.name!!)
            VoiceCommand.TorchOn -> setTorch(true)
            VoiceCommand.TorchOff -> setTorch(false)
            VoiceCommand.SpeechFaster -> _effects.tryEmit(EngineEffect.AdjustSpeech(rateDelta = 0.1f))
            VoiceCommand.SpeechSlower -> _effects.tryEmit(EngineEffect.AdjustSpeech(rateDelta = -0.1f))
            VoiceCommand.SpeechLouder -> _effects.tryEmit(EngineEffect.AdjustSpeech(volumeSteps = 1))
            VoiceCommand.SpeechQuieter -> _effects.tryEmit(EngineEffect.AdjustSpeech(volumeSteps = -1))
            is VoiceCommand.Unknown -> say(EnginePhrases.info(EnginePhrases.NOT_UNDERSTOOD, "voice"))
        }
    }

    // =====================================================================================
    // SOS (always confirmed by the user before anything is sent)
    // =====================================================================================

    fun requestSos() = post {
        if (settings.emergencyContacts.isEmpty()) {
            say(EnginePhrases.error(EnginePhrases.SOS_NO_CONTACTS, "sos"))
            return@post
        }
        _state.update { it.copy(sos = SosStatus.AwaitingConfirmation(settings.emergencyContacts.size)) }
        say(Announcement(EnginePhrases.SOS_CONFIRM, AnnouncementPriority.WARNING, "app:sos", ttlMs = 8_000))
    }

    fun dismissSosResult() = post {
        if (_state.value.sos is SosStatus.Sent || _state.value.sos is SosStatus.Failed) _state.update { it.copy(sos = SosStatus.Idle) }
    }

    fun cancelSos() = post {
        if (_state.value.sos !is SosStatus.AwaitingConfirmation) return@post
        _state.update { it.copy(sos = SosStatus.Idle) }
        say(EnginePhrases.info(EnginePhrases.SOS_CANCELLED, "sos"))
    }

    fun confirmSos() = post {
        if (_state.value.sos !is SosStatus.AwaitingConfirmation) return@post
        _state.update { it.copy(sos = SosStatus.Sending) }
        startSensors()
        val fix = awaitFix(4_000)
        val age = fix?.let { time.elapsedMs() - it.elapsedMs }
        val message = sosMessenger.buildMessage(fix, age)
        log.log("SOS", "confirmed by user")
        val outcome: SosStatus = when (val r = sosMessenger.sendSos(settings.emergencyContacts, message)) {
            is SosResult.Sent -> {
                val text = if (r.unconfirmed.isEmpty()) EnginePhrases.sosSent(r.sentTo.size) else EnginePhrases.sosPending(r.sentTo.size + r.unconfirmed.size)
                say(Announcement(text, AnnouncementPriority.WARNING, "app:sos", ttlMs = 8_000))
                SosStatus.Sent(text)
            }
            is SosResult.NeedsUserAction -> {
                say(EnginePhrases.info(EnginePhrases.SOS_OPENING_SMS, "sos"))
                _effects.tryEmit(EngineEffect.LaunchIntent(r.intent))
                SosStatus.Sent(EnginePhrases.SOS_OPENING_SMS)
            }
            is SosResult.Failed -> {
                say(EnginePhrases.error(EnginePhrases.SOS_FAILED, "sos"))
                r.fallback?.let { _effects.tryEmit(EngineEffect.LaunchIntent(it)) }
                SosStatus.Failed(EnginePhrases.SOS_FAILED)
            }
            SosResult.NoValidContacts -> {
                say(EnginePhrases.error(EnginePhrases.SOS_NO_CONTACTS, "sos"))
                SosStatus.Failed(EnginePhrases.SOS_NO_CONTACTS)
            }
        }
        _state.update { it.copy(sos = outcome) }
        stopSensorsIfIdle()
    }

    // =====================================================================================
    // Speech helpers
    // =====================================================================================

    private fun say(a: Announcement) {
        audio.submit(a.copy(createdAtMs = if (a.createdAtMs == 0L) time.elapsedMs() else a.createdAtMs))
    }

    private fun sayObservation(a: Announcement) {
        say(a)
        // Session confirmations and the coverage reminder are spoken but are not observations; the
        // screen states coverage once, permanently. Repeating a line twice in a row adds nothing.
        if (a.text in NOT_OBSERVATIONS) return
        _state.update {
            val isContext = a.priority == AnnouncementPriority.NAVIGATION
            it.copy(
                observationLines = if (isContext || it.observationLines.lastOrNull() == a.text) it.observationLines
                else (it.observationLines + a.text).takeLast(MAX_OBSERVATION_LINES),
                crossingContextText = if (isContext) a.text else it.crossingContextText,
            )
        }
    }

    private fun List<CrossingAssistEvent>.speak() {
        if (isEmpty()) return
        val orientation = _state.value.orientation
        for (e in this) {
            log.log("CROSSING", e.toString().take(160))
            reporter.onAssistEvent(e, orientation).forEach(::sayObservation)
        }
        _state.update {
            it.copy(
                refugeMapped = crossing.refugeStatus == com.visionnav.core.crossing.RefugeStatus.MAPPED,
                userConfirmedAtCrossing = crossing.userPosition.value == com.visionnav.core.crossing.UserPositionAssessment.USER_CONFIRMED,
            )
        }
    }

    private fun post(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    companion object {
        const val CLIENT_SESSION = "session"
        private const val TICK_MS = 500L
        private const val STALE_FRAME_MS = 1_500L
        private const val ALERT_HOLD_MS = 3_000L
        private const val PATH_HINT_INTERVAL_MS = 6_000L
        private const val LOW_LIGHT_PERSIST_MS = 2_000L
        private const val SCENE_POLL_MS = 100L
        private const val CONTEXT_RADIUS_M = 120.0
        private const val CONTEXT_REFETCH_M = 60.0
        private const val CONTEXT_REFETCH_MS = 120_000L
        private const val MAX_OBSERVATION_LINES = 8
        private const val PREVIEW_REUSE_MS = 120_000L
        private val NOT_OBSERVATIONS = setOf(CrossingPhrases.OBSERVING, CrossingPhrases.COVERAGE_REMINDER, CrossingPhrases.OBSERVATION_ENDED)
        private const val NO_LOCATION = "no location"
        private val OBSERVING_STATES = setOf(CrossingAssistState.OBSERVING_TRAFFIC, CrossingAssistState.OBSERVATION_UNCERTAIN)
    }
}

