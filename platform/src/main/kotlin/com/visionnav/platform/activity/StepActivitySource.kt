package com.visionnav.platform.activity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.visionnav.core.sensing.ActivitySource
import com.visionnav.core.sensing.LocationSource
import com.visionnav.core.sensing.LocationState
import com.visionnav.core.sensing.MotionActivity
import com.visionnav.platform.common.RefCounter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WALKING / STATIONARY / UNKNOWN from the hardware step detector (`TYPE_STEP_DETECTOR`) plus GPS
 * speed, decided by the pure [ActivityDecision].
 *
 * - The step detector needs `ACTIVITY_RECOGNITION` on API 29+. Without it (or without the
 *   sensor) only reliable GPS speed can show WALKING; otherwise the result is UNKNOWN. The
 *   permission is re-checked every tick, so granting it later takes effect without a restart.
 * - GPS speed is read from the shared [LocationSource] but this class **does not start it**:
 *   speed contributes only while another component (navigation, crossing assist) holds the
 *   location subscription. Speed counts as reliable only from a fix younger than
 *   [SPEED_MAX_AGE_MS] with accuracy ≤ [SPEED_MAX_ACCURACY_M].
 * - Ref-counted [start]/[stop]; re-evaluated every [TICK_MS] and on every step. Stopped → UNKNOWN.
 */
@Singleton
class StepActivitySource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationSource: LocationSource,
) : ActivitySource {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val stepSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private val _activity = MutableStateFlow(MotionActivity.UNKNOWN)
    override val activity: StateFlow<MotionActivity> = _activity.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var running = false
    private var registered = false
    private var listeningSinceMs: Long? = null
    private var lastStepMs: Long? = null
    private var ticker: Job? = null

    private val refCounter = RefCounter(onFirstAcquire = ::open, onLastRelease = ::close)

    override fun start() = refCounter.acquire()

    override fun stop() {
        refCounter.release()
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_DETECTOR) return
            val now = SystemClock.elapsedRealtime()
            // Event timestamps are usually elapsedRealtimeNanos, but not guaranteed on every
            // device; use them only if plausible (batched steps may arrive late).
            val eventMs = event.timestamp / 1_000_000L
            val stepMs = if (eventMs in (now - PLAUSIBLE_TIMESTAMP_SKEW_MS)..now) eventMs else now
            synchronized(lock) {
                if (!running) return
                if (lastStepMs == null || stepMs > lastStepMs!!) lastStepMs = stepMs
            }
            evaluate()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private fun open() {
        synchronized(lock) {
            running = true
            lastStepMs = null
            listeningSinceMs = null
        }
        ticker = scope.launch {
            while (isActive) {
                ensureRegistration()
                evaluate()
                delay(TICK_MS)
            }
        }
    }

    private fun close() {
        ticker?.cancel()
        ticker = null
        synchronized(lock) {
            running = false
            if (registered) sensorManager?.unregisterListener(listener)
            registered = false
            listeningSinceMs = null
            lastStepMs = null
        }
        _activity.value = MotionActivity.UNKNOWN
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureRegistration() {
        val sensor = stepSensor ?: return
        val permitted = hasPermission()
        synchronized(lock) {
            if (!running) return
            if (permitted && !registered) {
                registered = sensorManager?.registerListener(
                    listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, mainHandler,
                ) == true
                if (registered) listeningSinceMs = SystemClock.elapsedRealtime()
            } else if (!permitted && registered) {
                sensorManager?.unregisterListener(listener)
                registered = false
                listeningSinceMs = null
                lastStepMs = null
            }
        }
    }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val input = synchronized(lock) {
            if (!running) return
            ActivityInputs(
                nowMs = now,
                stepSensorUsable = registered,
                listeningSinceMs = listeningSinceMs,
                lastStepMs = lastStepMs,
                reliableSpeedMps = reliableSpeed(now),
                previous = _activity.value,
            )
        }
        _activity.value = ActivityDecision.decide(input)
    }

    private fun reliableSpeed(nowMs: Long): Float? {
        val fix = (locationSource.state.value as? LocationState.Available)?.fix ?: return null
        if (nowMs - fix.elapsedMs > SPEED_MAX_AGE_MS) return null
        if (fix.accuracyM > SPEED_MAX_ACCURACY_M) return null
        return fix.speedMps
    }

    companion object {
        const val TICK_MS = 500L
        const val SPEED_MAX_AGE_MS = 3_000L
        const val SPEED_MAX_ACCURACY_M = 20f
        private const val PLAUSIBLE_TIMESTAMP_SKEW_MS = 10_000L
    }
}
