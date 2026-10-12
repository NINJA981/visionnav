package com.visionnav.core.common

/**
 * Injected clock. [elapsedMs] is monotonic (use for tracking/cooldowns);
 * [wallMs] is wall-clock time (use only for display and logs).
 */
interface TimeSource {
    fun elapsedMs(): Long
    fun wallMs(): Long
}

object SystemTimeSource : TimeSource {
    override fun elapsedMs(): Long = System.nanoTime() / 1_000_000
    override fun wallMs(): Long = System.currentTimeMillis()
}

/** Manually advanced clock for deterministic tests. */
class FakeTimeSource(var now: Long = 0L) : TimeSource {
    override fun elapsedMs(): Long = now
    override fun wallMs(): Long = now
    fun advance(ms: Long) { now += ms }
}

/** Where a piece of information came from. Never conflate sources. */
enum class DataSource { OSM_MAP, ROUTE_ENGINE, CAMERA, GPS, SENSOR, USER, INFERRED }

/** How much a value can be trusted. Ordered from least to most reliable. */
enum class Reliability { UNKNOWN, LOW, INFERRED, MAPPED, RELIABLE }

/**
 * Every reason the system can be uncertain. Each maps to one short spoken phrase
 * (see `com.visionnav.core.announce`). Never replaced by a fabricated value.
 */
enum class UncertaintyReason {
    GPS_UNAVAILABLE,
    GPS_LOW_ACCURACY,
    HEADING_UNAVAILABLE,
    HEADING_UNRELIABLE,
    ROAD_DIRECTION_UNAVAILABLE,
    MAP_DATA_UNAVAILABLE,
    CAMERA_UNAVAILABLE,
    CAMERA_OBSTRUCTED,
    LOW_LIGHT,
    GLARE,
    BLUR,
    DETECTOR_UNAVAILABLE,
    TRACK_TOO_SHORT,
    CAMERA_MOVING,
    MOTION_INCONSISTENT,
    IDENTITY_SWITCH,
    CROSSING_AMBIGUOUS,
    ORIENTATION_UNRELIABLE,
}

/** Result type for fallible service calls. */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: ServiceError) : Outcome<Nothing>
}

sealed interface ServiceError {
    data object Offline : ServiceError
    data object Timeout : ServiceError
    data object RateLimited : ServiceError
    data object NotFound : ServiceError
    data class Http(val code: Int) : ServiceError
    data class Parse(val message: String) : ServiceError
    data class Unknown(val message: String) : ServiceError
}
