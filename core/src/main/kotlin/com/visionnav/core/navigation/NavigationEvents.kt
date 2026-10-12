package com.visionnav.core.navigation

import com.visionnav.core.geo.GeoPoint

/**
 * Snapshot of progress along the active [Route], produced for every location fix.
 *
 * @param alongM distance from route start to the snapped position.
 * @param remainingM distance from the snapped position to the route end.
 * @param currentManeuverIndex index into [Route.maneuvers] of the next upcoming maneuver
 *   (the one the user is walking toward); `maneuvers.size` when none remain.
 * @param distanceToNextManeuverM along-route distance to that maneuver, or null when none remain.
 * @param offRouteDistanceM perpendicular distance from the fix to the route.
 * @param gpsAccuracyM horizontal accuracy (68 %) of the fix that produced this snapshot.
 */
data class RouteProgress(
    val alongM: Double,
    val remainingM: Double,
    val currentManeuverIndex: Int,
    val nextManeuver: Maneuver?,
    val distanceToNextManeuverM: Double?,
    val offRouteDistanceM: Double,
    val gpsAccuracyM: Float,
    val snappedPoint: GeoPoint,
    val isOffRoute: Boolean,
    val arrived: Boolean,
    val fixElapsedMs: Long,
)

/**
 * Discrete navigation events from [RouteProgressTracker]. Each maneuver event is emitted at most
 * once per maneuver; state events (off route, GPS degraded) are emitted on state transitions only.
 */
sealed interface NavigationEvent {
    /** Early warning (~40 m, earlier with poor GPS) for an upcoming maneuver. */
    data class PrepareManeuver(
        val maneuverIndex: Int,
        val maneuver: Maneuver,
        val distanceM: Double,
        val accuracyM: Float,
    ) : NavigationEvent

    /** The maneuver point is reached (~10 m). */
    data class ExecuteManeuver(
        val maneuverIndex: Int,
        val maneuver: Maneuver,
        val distanceM: Double,
        val accuracyM: Float,
    ) : NavigationEvent

    /** Sustained deviation from the route (distance and duration hysteresis). */
    data class OffRoute(val distanceM: Double, val accuracyM: Float) : NavigationEvent

    data object BackOnRoute : NavigationEvent

    /**
     * The app should request a new route from [from]. Emitted with [OffRoute] and then repeatedly
     * while still off route, with exponential backoff (10 s, 20 s, 40 s, capped at 60 s).
     */
    data class RerouteNeeded(val from: GeoPoint, val headingDeg: Double?, val attempt: Int) : NavigationEvent

    /** Confidently near the destination (good accuracy, sustained). Terminal for this route. */
    data class Arrived(val maneuver: Maneuver?, val distanceM: Double, val accuracyM: Float) : NavigationEvent

    /** Apparently near the destination but GPS accuracy is too poor to claim arrival. */
    data class ArrivalUncertain(val distanceM: Double, val accuracyM: Float) : NavigationEvent

    /** GPS accuracy became poor, or fixes stopped arriving ([accuracyM] null). */
    data class GpsDegraded(val accuracyM: Float?) : NavigationEvent

    data object GpsRecovered : NavigationEvent
}

/** Result of feeding one fix to [RouteProgressTracker]. */
data class RouteUpdate(val progress: RouteProgress, val events: List<NavigationEvent>)
