package com.visionnav.platform.orientation

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.sensing.DeviceOrientation
import com.visionnav.core.sensing.LocationSource
import com.visionnav.core.sensing.LocationState
import com.visionnav.core.sensing.OrientationSource
import com.visionnav.core.sensing.OrientationState
import com.visionnav.platform.common.RefCounter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device / **rear-camera** orientation from the fused rotation-vector sensor, with gyroscope
 * yaw rate and magnetometer field strength for reliability gating (PLAN §4.4).
 *
 * ## What this is — and is not
 * [DeviceOrientation.azimuthDeg] is the true-north bearing along which the **rear camera looks**
 * (device −Z axis), i.e. where the phone is pointed. It is **not** the user's walking heading:
 * people routinely point the phone sideways, swing it, or hold it flat. It must never be written
 * into, or used to overwrite, the navigation/walking heading — that comes from GPS course over
 * ground (`LocationFix.courseDeg`, only meaningful while moving). Consumers such as
 * `CameraOrientationEstimator` use it solely to map camera-image left/right to world directions,
 * and only when [DeviceOrientation.accuracy], [DeviceOrientation.tiltFromVerticalDeg],
 * [DeviceOrientation.yawRateRadPerSec] and [DeviceOrientation.magneticFieldUt] say it is reliable.
 *
 * ## Behaviour
 * - Requires `TYPE_ROTATION_VECTOR` (magnetometer-referenced). `GAME_ROTATION_VECTOR` has no
 *   absolute north and is **not** used as a fallback: without a rotation vector the state is
 *   `Unavailable(HEADING_UNAVAILABLE)` permanently.
 * - Declination from [GeomagneticField] using the last fix seen on the shared [LocationSource]
 *   (observed only — this class never starts location). Unknown declination → 0 and accuracy is
 *   downgraded one level (see [OrientationMath.combineHeadingAccuracy]).
 * - Azimuth low-pass filtered with circular smoothing (handles 359°→0°); emitted at ≤ 20 Hz.
 * - No rotation-vector event for [STALE_AFTER_MS] → `Unavailable(HEADING_UNAVAILABLE)`.
 * - Ref-counted [start]/[stop]; sensors run on a dedicated background thread at
 *   `SENSOR_DELAY_GAME` (gyro, rotation) and `SENSOR_DELAY_UI` (magnetometer magnitude only).
 * - Timestamps are on the `SystemClock.elapsedRealtime()` base.
 */
@Singleton
class SensorOrientationSource @Inject constructor(
    @ApplicationContext context: Context,
    private val locationSource: LocationSource,
) : OrientationSource {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _state = MutableStateFlow<OrientationState>(UNAVAILABLE)
    override val state: StateFlow<OrientationState> = _state.asStateFlow()

    /** True if the device has the absolute rotation-vector sensor this source needs. */
    val isSupported: Boolean get() = rotationSensor != null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // ---- State below is only touched on the sensor thread (handler) ----
    private val rotationMatrix = FloatArray(9)
    private var hasRotation = false
    private var filteredAzimuth: Float? = null
    private var lastRotationEventMs = 0L
    private var lastEmitMs = 0L
    private var headingErrorRad: Float? = null
    private var rotationStatus: Int? = null
    private var magStatus: Int? = null
    private var gyroRate: Float? = null
    private var yawRate: Float? = null
    private var magneticUt: Float? = null
    private var declinationDeg: Float? = null
    private var declinationOrigin: GeoPoint? = null

    private val refCounter = RefCounter(onFirstAcquire = ::open, onLastRelease = ::close)

    override fun start() = refCounter.acquire()

    override fun stop() {
        refCounter.release()
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> onRotation(event)
                Sensor.TYPE_GYROSCOPE -> onGyro(event)
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magneticUt = OrientationMath.magnitude(event.values[0], event.values[1], event.values[2])
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            when (sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> rotationStatus = accuracy
                Sensor.TYPE_MAGNETIC_FIELD -> magStatus = accuracy
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (!hasRotation || now - lastRotationEventMs > STALE_AFTER_MS) {
                hasRotation = false
                filteredAzimuth = null
                _state.value = UNAVAILABLE
            }
            handler?.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    private fun open() {
        val sm = sensorManager
        val rotation = rotationSensor
        if (sm == null || rotation == null) {
            _state.value = UNAVAILABLE
            return
        }
        val t = HandlerThread("visionnav-orientation").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        h.post { resetLocked() }
        sm.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_GAME, h)
        gyroSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, h) }
        magSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, h) }
        h.postDelayed(watchdog, WATCHDOG_PERIOD_MS)
    }

    private fun close() {
        sensorManager?.unregisterListener(listener)
        handler?.let { h ->
            h.removeCallbacksAndMessages(null)
            // Serialised after any in-flight sensor callback, so a late emission cannot leave
            // a stale Available state behind.
            h.post { _state.value = UNAVAILABLE }
        }
        thread?.quitSafely()
        thread = null
        handler = null
        _state.value = UNAVAILABLE
    }

    private fun resetLocked() {
        hasRotation = false
        filteredAzimuth = null
        lastEmitMs = 0L
        headingErrorRad = null
        gyroRate = null
        yawRate = null
        magneticUt = null
        // rotationStatus / magStatus are kept: onAccuracyChanged is only re-sent on change.
    }

    private fun onRotation(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        hasRotation = true
        val now = SystemClock.elapsedRealtime()
        lastRotationEventMs = now
        headingErrorRad = if (event.values.size >= 5) event.values[4] else null

        updateDeclination()
        val magneticAz = OrientationMath.cameraAzimuthDeg(rotationMatrix)
        val trueAz = OrientationMath.applyDeclination(magneticAz, declinationDeg ?: 0f)
        val filtered = OrientationMath.lowPassAngleDeg(filteredAzimuth, trueAz, AZIMUTH_ALPHA)
        filteredAzimuth = filtered

        if (now - lastEmitMs < MIN_EMIT_INTERVAL_MS) return
        lastEmitMs = now
        _state.value = OrientationState.Available(
            DeviceOrientation(
                azimuthDeg = filtered,
                tiltFromVerticalDeg = OrientationMath.tiltFromVerticalDeg(rotationMatrix),
                rollDeg = OrientationMath.rollDeg(rotationMatrix),
                accuracy = OrientationMath.combineHeadingAccuracy(
                    headingErrorRad = headingErrorRad,
                    rotationStatus = rotationStatus,
                    magnetometerStatus = magStatus,
                    declinationKnown = declinationDeg != null,
                ),
                gyroRateRadPerSec = gyroRate,
                yawRateRadPerSec = yawRate,
                magneticFieldUt = magneticUt,
                elapsedMs = now,
            ),
        )
    }

    private fun onGyro(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        val gz = event.values[2]
        val rate = OrientationMath.magnitude(gx, gy, gz)
        gyroRate = smooth(gyroRate, rate)
        if (hasRotation) {
            yawRate = smooth(yawRate, OrientationMath.yawRateRadPerSec(rotationMatrix, gx, gy, gz))
        }
    }

    private fun smooth(previous: Float?, sample: Float): Float =
        if (previous == null) sample else previous + GYRO_ALPHA * (sample - previous)

    /** Recomputes declination when we first get a fix or have moved > [DECLINATION_REFRESH_M]. */
    private fun updateDeclination() {
        val fix = (locationSource.state.value as? LocationState.Available)?.fix ?: return
        val origin = declinationOrigin
        if (origin != null && GeoMath.distanceM(origin, fix.point) < DECLINATION_REFRESH_M) return
        val field = GeomagneticField(
            fix.point.lat.toFloat(),
            fix.point.lon.toFloat(),
            0f,
            System.currentTimeMillis(),
        )
        val d = field.declination
        if (d.isFinite()) {
            declinationDeg = d
            declinationOrigin = fix.point
        }
    }

    companion object {
        /** Low-pass weight of each new azimuth sample (≈ 50 Hz input). */
        const val AZIMUTH_ALPHA = 0.25f

        /** Low-pass weight for gyro rates. */
        const val GYRO_ALPHA = 0.3f

        /** ≤ 20 Hz emission. */
        const val MIN_EMIT_INTERVAL_MS = 50L

        /** No rotation-vector sample for this long → Unavailable. */
        const val STALE_AFTER_MS = 2_000L

        private const val WATCHDOG_PERIOD_MS = 1_000L
        private const val DECLINATION_REFRESH_M = 5_000.0

        private val UNAVAILABLE = OrientationState.Unavailable(UncertaintyReason.HEADING_UNAVAILABLE)
    }
}
