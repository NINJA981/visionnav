package com.visionnav.perception.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.vision.AnalysisDemand
import com.visionnav.core.vision.CameraState
import com.visionnav.core.vision.Detection
import com.visionnav.core.vision.DetectorCapabilities
import com.visionnav.core.vision.FrameFeed
import com.visionnav.core.vision.FrameObservation
import com.visionnav.perception.crosswalk.CrosswalkDetector
import com.visionnav.perception.detect.ObjectDetector
import com.visionnav.perception.preprocess.ImagePreprocessor
import com.visionnav.perception.preprocess.PreprocessedFrame
import com.visionnav.perception.quality.FrameQualityAnalyzer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import androidx.camera.core.CameraState as XCameraState

/**
 * **The** single camera session of the app ([FrameFeed] implementation).
 *
 * - Owns its own [LifecycleOwner], so it runs independently of activities (e.g. from the
 *   navigation foreground service). The owner is RESUMED only while there is demand.
 * - Binds the default back camera with ONE [ImageAnalysis] use case (YUV_420_888,
 *   `STRATEGY_KEEP_ONLY_LATEST`, ~640×480, target rotation pinned to `ROTATION_0` so "upright" is
 *   the device's natural portrait orientation) plus an optional [Preview] for the debug overlay.
 * - **Demand-driven**: clients call [setDemand]; the effective demand is the maximum. When every
 *   client is at [AnalysisDemand.NONE] the use cases are unbound and the camera is released.
 * - Frames are dropped by timestamp ([FrameThrottle]) *before* any work, according to the
 *   effective fps × [setThrottleFactor]. A frame is also dropped if the previous one is still in
 *   inference. Accepted frames are converted on the analysis thread (then the [ImageProxy] is
 *   closed), and quality + crosswalk + detection run on a single inference thread.
 * - Emits [FrameObservation]s on [frames] (`extraBufferCapacity = 1`, `DROP_OLDEST`).
 *   `elapsedMs` is `SystemClock.elapsedRealtime()` when the frame reached the analyzer.
 * - Privacy: frames are processed in memory only; nothing is written to disk or sent anywhere.
 *
 * State ([cameraState]): `Inactive` (no demand) → `Starting` → `Running(capabilities, torchOn,
 * hasTorch)`; `Unavailable(CAMERA_UNAVAILABLE)` on missing permission / bind failure / camera
 * error (retried on the next [setDemand]); `Unavailable(DETECTOR_UNAVAILABLE)` when the detector
 * cannot be created or fails 3 times in a row (frames with quality/crosswalk keep flowing, with
 * empty detections that must not be read as "nothing there"). A mock detector is reported via
 * `capabilities.isMock`.
 */
@Singleton
class CameraSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val detectorProvider: dagger.Lazy<ObjectDetector>,
) : FrameFeed {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "visionnav-camera-analysis").apply { priority = Thread.NORM_PRIORITY }
    }
    private val inferenceExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "visionnav-inference")
    }

    private val _frames = MutableSharedFlow<FrameObservation>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frames: SharedFlow<FrameObservation> = _frames.asSharedFlow()

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Inactive)
    override val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    // ---- demand / throttling -------------------------------------------------------------
    private val demandLock = Any()
    private val aggregator = DemandAggregator()
    @Volatile private var targetFps: Int = 0
    @Volatile private var throttleFactor: Float = 1f
    private val throttle = FrameThrottle() // analysis thread only

    // ---- main-thread session state -------------------------------------------------------
    private enum class Phase { IDLE, STARTING, RUNNING }
    private var phase = Phase.IDLE
    private var startGeneration = 0
    private var lifecycleOwner: SessionLifecycleOwner? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var desiredTorch = false

    // ---- published status (any thread, guarded by publish lock) --------------------------
    private val stateLock = Any()
    private var sessionActive = false
    private var cameraOpen = false
    private var cameraError = false
    private var torchOn = false
    private var hasTorch = false
    private var capabilities: DetectorCapabilities? = null
    private var detectorReady = false
    private var detectorFailed = false

    // ---- frame pipeline ------------------------------------------------------------------
    private val inferenceBusy = AtomicBoolean(false)
    @Volatile private var framesEnabled = false
    @Volatile private var fovInputs: FovInputs? = null
    private var fovCacheKey: FovCacheKey? = null // analysis thread
    private var fovValue: Float? = null // analysis thread
    private val preprocessor = ImagePreprocessor() // analysis thread (exclusive via inferenceBusy)
    private val qualityAnalyzer = FrameQualityAnalyzer() // inference thread
    private val crosswalkDetector = CrosswalkDetector() // inference thread
    private var detector: ObjectDetector? = null // inference thread
    private var detectorLoadAttempted = false // inference thread
    private var consecutiveDetectorFailures = 0 // inference thread

    // =======================================================================================
    // FrameFeed
    // =======================================================================================

    override fun setDemand(clientId: String, demand: AnalysisDemand) {
        val change = synchronized(demandLock) {
            aggregator.set(clientId, demand).also { targetFps = it.current.targetFps }
        }
        Log.d(TAG, "demand $clientId=$demand → effective ${change.current} (${change.action})")
        runOnMain { applyDemand() }
    }

    override fun setTorch(enabled: Boolean) {
        runOnMain {
            desiredTorch = enabled
            val cam = camera
            if (cam != null && cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(enabled)
        }
    }

    // =======================================================================================
    // Extra API for the integrator
    // =======================================================================================

    /**
     * Thermal/battery hook: scales the effective analysis rate by [factor] (clamped to 0.1..1).
     * The integrator maps `PowerManager` thermal status / battery saver to a factor.
     */
    fun setThrottleFactor(factor: Float) {
        throttleFactor = factor.coerceIn(0.1f, 1f)
    }

    /** Current effective demand (max over clients). */
    val effectiveDemand: AnalysisDemand get() = synchronized(demandLock) { aggregator.effective }

    /**
     * Attaches a preview (debug overlay only). The preview is shown while the session runs; it
     * does **not** create demand on its own. Call from any thread.
     */
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        runOnMain {
            val p = preview ?: Preview.Builder().build().also { preview = it }
            p.setSurfaceProvider(surfaceProvider)
            val provider = cameraProvider
            val owner = lifecycleOwner
            if (phase == Phase.RUNNING && provider != null && owner != null && !provider.isBound(p)) {
                try {
                    camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, p)
                } catch (e: Exception) {
                    Log.w(TAG, "preview bind failed", e)
                }
            }
        }
    }

    /** Detaches the debug preview, if any. */
    fun detachPreview() {
        runOnMain {
            val p = preview ?: return@runOnMain
            cameraProvider?.unbind(p)
            p.setSurfaceProvider(null)
            preview = null
        }
    }

    // =======================================================================================
    // Session control (main thread)
    // =======================================================================================

    @MainThread
    private fun applyDemand() {
        val effective = synchronized(demandLock) { aggregator.effective }
        if (effective != AnalysisDemand.NONE) {
            if (phase == Phase.IDLE) start()
        } else if (phase != Phase.IDLE) {
            stop()
        }
    }

    @MainThread
    private fun start() {
        phase = Phase.STARTING
        val generation = ++startGeneration
        updateStatus { sessionActive = true; cameraError = false; cameraOpen = false }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "camera permission not granted")
            failStart()
            return
        }
        loadDetectorAsync()
        val future = try {
            ProcessCameraProvider.getInstance(context)
        } catch (e: Exception) {
            Log.e(TAG, "camera provider unavailable", e)
            failStart()
            return
        }
        future.addListener({
            // Stopped (or stopped and restarted) meanwhile: only the latest start may bind.
            if (phase != Phase.STARTING || generation != startGeneration) return@addListener
            val provider = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "camera provider init failed", e)
                failStart()
                return@addListener
            }
            cameraProvider = provider
            if (synchronized(demandLock) { aggregator.effective } == AnalysisDemand.NONE) {
                stop()
                return@addListener
            }
            bind(provider)
        }, ContextCompat.getMainExecutor(context))
    }

    @MainThread
    private fun bind(provider: ProcessCameraProvider) {
        val owner = lifecycleOwner ?: SessionLifecycleOwner().also { lifecycleOwner = it }
        try {
            owner.registry.currentState = Lifecycle.State.RESUMED
            val useCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(Surface.ROTATION_0)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(TARGET_WIDTH, TARGET_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .build()
            useCase.setAnalyzer(analysisExecutor, ::analyze)
            analysis = useCase
            val useCases = listOfNotNull(useCase, preview).toTypedArray()
            val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
            camera = cam
            fovInputs = readFovInputs(cam.cameraInfo)
            observeCamera(cam.cameraInfo, owner)
            val flash = cam.cameraInfo.hasFlashUnit()
            updateStatus { hasTorch = flash }
            if (desiredTorch && flash) cam.cameraControl.enableTorch(true)
            framesEnabled = true
            phase = Phase.RUNNING
            Log.i(TAG, "camera bound (flash=$flash, fov=${fovInputs})")
        } catch (e: Exception) {
            Log.e(TAG, "camera bind failed", e)
            unbindAll()
            failStart()
        }
    }

    @MainThread
    private fun observeCamera(info: CameraInfo, owner: LifecycleOwner) {
        info.cameraState.observe(owner) { s: XCameraState ->
            val error = s.error
            if (error != null) Log.w(TAG, "camera state ${s.type} error ${error.code} (${error.type})")
            updateStatus {
                cameraOpen = s.type == XCameraState.Type.OPEN && error == null
                cameraError = error != null
            }
        }
        info.torchState.observe(owner) { t -> updateStatus { torchOn = t == TorchState.ON } }
    }

    @MainThread
    private fun failStart() {
        phase = Phase.IDLE
        framesEnabled = false
        lifecycleOwner?.registry?.currentState = Lifecycle.State.CREATED
        updateStatus { cameraError = true; cameraOpen = false }
    }

    @MainThread
    private fun stop() {
        phase = Phase.IDLE
        framesEnabled = false
        unbindAll()
        analysisExecutor.execute { throttle.reset() }
        updateStatus { sessionActive = false; cameraOpen = false; cameraError = false; torchOn = false }
        Log.i(TAG, "camera released (no demand)")
    }

    @MainThread
    private fun unbindAll() {
        val cam = camera
        val owner = lifecycleOwner
        if (cam != null && owner != null) {
            cam.cameraInfo.cameraState.removeObservers(owner)
            cam.cameraInfo.torchState.removeObservers(owner)
        }
        analysis?.clearAnalyzer()
        val provider = cameraProvider
        if (provider != null) {
            val bound = listOfNotNull(analysis, preview).filter { provider.isBound(it) }
            if (bound.isNotEmpty()) provider.unbind(*bound.toTypedArray())
        }
        analysis = null
        camera = null
        owner?.registry?.currentState = Lifecycle.State.CREATED
    }

    // =======================================================================================
    // Frame pipeline
    // =======================================================================================

    /** Analysis thread. Must close [image] on every path. */
    private fun analyze(image: ImageProxy) {
        try {
            if (!framesEnabled || inferenceBusy.get()) return
            if (!throttle.tryAccept(image.imageInfo.timestamp, targetFps, throttleFactor)) return
            val elapsedMs = SystemClock.elapsedRealtime()
            inferenceBusy.set(true)
            val frame = try {
                preprocessor.process(image)
            } catch (t: Throwable) {
                Log.e(TAG, "preprocessing failed", t)
                inferenceBusy.set(false)
                return
            }
            val fov = fovFor(image.width, image.height, frame.rotationDegrees)
            try {
                inferenceExecutor.execute { infer(frame, elapsedMs, fov) }
            } catch (e: RejectedExecutionException) {
                inferenceBusy.set(false)
            }
        } finally {
            image.close()
        }
    }

    /** Inference thread. */
    private fun infer(frame: PreprocessedFrame, elapsedMs: Long, fov: Float?) {
        try {
            val quality = runCatching { qualityAnalyzer.analyze(frame.luma, frame.lumaWidth, frame.lumaHeight) }
                .onFailure { Log.w(TAG, "quality analysis failed", it) }.getOrNull()
            val crosswalk = runCatching { crosswalkDetector.detect(frame.luma, frame.lumaWidth, frame.lumaHeight) }
                .onFailure { Log.w(TAG, "crosswalk analysis failed", it) }.getOrNull()
            val det = detector
            val detections: List<Detection> = if (det == null) {
                emptyList() // DETECTOR_UNAVAILABLE is reported through cameraState
            } else {
                try {
                    det.detect(frame).also { consecutiveDetectorFailures = 0 }
                } catch (t: Throwable) {
                    consecutiveDetectorFailures++
                    Log.e(TAG, "detection failed ($consecutiveDetectorFailures in a row)", t)
                    if (consecutiveDetectorFailures >= MAX_DETECTOR_FAILURES) updateStatus { detectorFailed = true }
                    return // no observation for this frame rather than a misleading empty one
                }
            }
            if (framesEnabled) {
                _frames.tryEmit(FrameObservation(elapsedMs, detections, quality, fov, crosswalk))
            }
        } finally {
            inferenceBusy.set(false)
        }
    }

    /** Loads the detector once, on the inference thread (model load + warm-up are slow). */
    private fun loadDetectorAsync() {
        inferenceExecutor.execute {
            if (detectorLoadAttempted) return@execute
            detectorLoadAttempted = true
            try {
                val d = detectorProvider.get()
                detector = d
                if (d.capabilities.isMock) Log.w(TAG, "using MOCK detector: ${d.capabilities}")
                updateStatus { capabilities = d.capabilities; detectorReady = true }
            } catch (t: Throwable) {
                Log.e(TAG, "detector unavailable", t)
                updateStatus { detectorFailed = true }
            }
        }
    }

    private fun fovFor(width: Int, height: Int, rotation: Int): Float? {
        val inputs = fovInputs ?: return null
        val key = FovCacheKey(width, height, rotation, inputs)
        if (key != fovCacheKey) {
            fovCacheKey = key
            fovValue = FieldOfView.horizontalFovDeg(
                inputs.focalLengthMm, inputs.physicalWidthMm, inputs.physicalHeightMm,
                width, height, rotation, inputs.activeScaleX, inputs.activeScaleY,
            )
        }
        return fovValue
    }

    // =======================================================================================
    // Helpers
    // =======================================================================================

    private inline fun updateStatus(block: () -> Unit) {
        synchronized(stateLock) {
            block()
            _cameraState.value = when {
                !sessionActive -> CameraState.Inactive
                cameraError -> CameraState.Unavailable(UncertaintyReason.CAMERA_UNAVAILABLE)
                detectorFailed -> CameraState.Unavailable(UncertaintyReason.DETECTOR_UNAVAILABLE)
                cameraOpen && detectorReady -> CameraState.Running(capabilities, torchOn, hasTorch)
                else -> CameraState.Starting
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private data class FovInputs(
        val focalLengthMm: Float?,
        val physicalWidthMm: Float?,
        val physicalHeightMm: Float?,
        val activeScaleX: Float,
        val activeScaleY: Float,
    )

    private data class FovCacheKey(val width: Int, val height: Int, val rotation: Int, val inputs: FovInputs)

    @OptIn(ExperimentalCamera2Interop::class)
    private fun readFovInputs(info: CameraInfo): FovInputs? = try {
        val c2 = Camera2CameraInfo.from(info)
        val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val physical = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixelArray = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sx = if (pixelArray != null && active != null && pixelArray.width > 0) active.width().toFloat() / pixelArray.width else 1f
        val sy = if (pixelArray != null && active != null && pixelArray.height > 0) active.height().toFloat() / pixelArray.height else 1f
        if (focal == null || physical == null) null else FovInputs(focal, physical.width, physical.height, sx, sy)
    } catch (e: Exception) {
        Log.w(TAG, "camera characteristics unavailable; FOV unknown", e)
        null
    }

    /** Lifecycle owned by the session, independent of any activity. Main thread only. */
    private class SessionLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        override val lifecycle: Lifecycle get() = registry
    }

    private companion object {
        const val TAG = "CameraSession"
        const val TARGET_WIDTH = 640
        const val TARGET_HEIGHT = 480
        const val MAX_DETECTOR_FAILURES = 3
    }
}
