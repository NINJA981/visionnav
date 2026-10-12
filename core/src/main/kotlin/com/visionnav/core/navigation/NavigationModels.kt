package com.visionnav.core.navigation

import com.visionnav.core.common.Outcome
import com.visionnav.core.geo.GeoPoint

enum class ManeuverType {
    START, CONTINUE, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    U_TURN, CROSS_ROAD, ENTER_ROUNDABOUT, EXIT_ROUNDABOUT, STAIRS, ELEVATOR, ESCALATOR,
    ENTER_BUILDING, EXIT_BUILDING, ARRIVE, ARRIVE_LEFT, ARRIVE_RIGHT, OTHER,
}

data class Maneuver(
    val type: ManeuverType,
    /** Instruction text as returned by the routing engine, for logs/debug only. Spoken text is generated locally. */
    val engineInstruction: String,
    val streetNames: List<String>,
    /** Index into [Route.geometry] where this maneuver begins. */
    val beginShapeIndex: Int,
    val lengthM: Double,
    val bearingBeforeDeg: Double?,
    val bearingAfterDeg: Double?,
    /** True if the engine reports this segment as a road crossing (e.g. Valhalla `crosswalk`/`crossing` edge use). */
    val isCrossing: Boolean,
)

data class Route(
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val lengthM: Double,
    val durationS: Double,
)

data class Place(
    val name: String,
    val point: GeoPoint,
    val address: String?,
    val category: String?,
    /** Distance from the bias location, if one was provided. */
    val distanceM: Double?,
)

interface RoutingService {
    suspend fun walkingRoute(from: GeoPoint, to: GeoPoint, headingDeg: Double? = null): Outcome<Route>
}

interface GeocodingService {
    /** Search biased toward [near]. Results are ranked with nearby matches first. */
    suspend fun search(query: String, near: GeoPoint?, limit: Int = 5): Outcome<List<Place>>
    suspend fun reverse(point: GeoPoint): Outcome<Place>
}
