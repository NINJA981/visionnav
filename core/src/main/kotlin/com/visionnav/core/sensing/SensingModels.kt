package com.visionnav.core.sensing

import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.geo.GeoPoint
import kotlinx.coroutines.flow.StateFlow

/** A single GPS/fused fix. Optional fields are null when the provider did not supply them. */
data class LocationFix(
    val point: GeoPoint,
    val accuracyM: Float,
    val speedMps: Float?,
    /** Course over ground, degrees true. Only meaningful while moving. */
    val courseDeg: Float?,
    val courseAccuracyDeg: Float?,
    val elapsedMs: Long,
)

sealed interface LocationState {
    data class Available(val fix: LocationFix) : LocationState
    data class Unavailable(val reason: UncertaintyReason) : LocationState
}

/** Single shared location subscription. Implemented in :platform. */
interface LocationSource {
    val state: StateFlow<LocationState>
    fun start()
    fun stop()
}

enum class SensorAccuracy { UNRELIABLE, LOW, MEDIUM, HIGH }

/**
 * Device orientation in the Earth frame. [azimuthDeg] is the direction the **rear camera**
 * points when the phone is held upright in portrait (true north, declination-corrected).
 */
data class DeviceOrientation(
    val azimuthDeg: Float,
    /** Angle of the phone's screen from vertical: 0 = upright, 90 = flat face-up. */
    val tiltFromVerticalDeg: Float,
    val rollDeg: Float,
    val accuracy: SensorAccuracy,
    /** Angular speed magnitude from gyroscope, rad/s; null if no gyroscope. */
    val gyroRateRadPerSec: Float?,
    /** Rotation about the device's vertical axis (yaw), rad/s, signed; positive = turning right. */
    val yawRateRadPerSec: Float?,
    /** Magnetic field strength in microtesla; null if unknown. Earth's field is ~25–65 µT. */
    val magneticFieldUt: Float?,
    val elapsedMs: Long,
)

sealed interface OrientationState {
    data class Available(val orientation: DeviceOrientation) : OrientationState
    data class Unavailable(val reason: UncertaintyReason) : OrientationState
}

/** Device heading/orientation. Implemented in :platform. Never written from camera data. */
interface OrientationSource {
    val state: StateFlow<OrientationState>
    fun start()
    fun stop()
}

/** Whether the user is walking or standing still, from step detector + GPS speed. */
enum class MotionActivity { WALKING, STATIONARY, UNKNOWN }

interface ActivitySource {
    val activity: StateFlow<MotionActivity>
    fun start()
    fun stop()
}
