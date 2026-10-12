package com.visionnav.mapdata.valhalla

import com.visionnav.core.common.Outcome
import com.visionnav.core.common.ServiceError
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.navigation.Maneuver
import com.visionnav.core.navigation.ManeuverType
import com.visionnav.core.navigation.Route
import com.visionnav.mapdata.polyline.Polyline6
import com.visionnav.mapdata.util.MapDataJson
import kotlinx.serialization.SerializationException

/**
 * Converts a Valhalla `/route` JSON body into a [Route].
 *
 * ## Crossings — what Valhalla actually provides
 * Valhalla's graph has an edge use `pedestrian_crossing` (OSM `footway=crossing`), and its
 * maneuver builder sets an internal `pedestrian_crossing` flag when the route steps from a footway
 * onto such an edge. That flag is **not serialised** in the `/route` JSON (no edge `use`, no
 * crossing field on maneuvers). Its only visible effect is the narrative: an unnamed crossing edge
 * is named "the crosswalk" in en-US instructions (e.g. "Turn right onto the crosswalk.").
 *
 * [isCrosswalkManeuver] therefore flags a maneuver as a crossing only when it has no street name
 * AND its instruction refers to "the crosswalk". This is reliable only for `language=en-US` (the
 * service always requests it) and only for crossings mapped as `footway=crossing` ways — road
 * crossings at plain intersections or unmapped crossings are NOT reported. The crossing module
 * separately infers possible crossings from Overpass geometry; treat `isCrossing=false` as
 * "unknown", never as "no crossing".
 */
object ValhallaRouteParser {
    private const val KM_TO_M = 1000.0
    private const val MILE_TO_M = 1609.344

    /** Valhalla error codes meaning "no route between these points". */
    val NO_ROUTE_ERROR_CODES: Set<Int> = setOf(
        170, // Locations are in unconnected regions
        171, // No suitable edges near location
        441, // Location is unreachable
        442, // No path could be found for input
    )

    private val CROSSWALK_PHRASE = Regex("""\bthe crosswalk\b""", RegexOption.IGNORE_CASE)

    fun parse(body: String): Outcome<Route> {
        val response = try {
            MapDataJson.decodeFromString(ValhallaResponse.serializer(), body)
        } catch (e: SerializationException) {
            return Outcome.Failure(ServiceError.Parse("Valhalla: ${e.message?.take(200)}"))
        } catch (e: IllegalArgumentException) {
            return Outcome.Failure(ServiceError.Parse("Valhalla: ${e.message?.take(200)}"))
        }
        response.errorCode?.let { code ->
            return Outcome.Failure(errorFor(code, response.statusCode))
        }
        val trip = response.trip ?: return Outcome.Failure(ServiceError.Parse("Valhalla: missing trip"))
        if (trip.legs.isEmpty()) return Outcome.Failure(ServiceError.Parse("Valhalla: trip has no legs"))
        val unitToM = if (trip.units.equals("miles", ignoreCase = true)) MILE_TO_M else KM_TO_M

        val geometry = ArrayList<GeoPoint>()
        val maneuvers = ArrayList<Maneuver>()
        for ((legIndex, leg) in trip.legs.withIndex()) {
            val shape = leg.shape
            if (shape.isNullOrEmpty()) return Outcome.Failure(ServiceError.Parse("Valhalla: leg $legIndex has no shape"))
            val legPoints = try {
                Polyline6.decode(shape)
            } catch (e: IllegalArgumentException) {
                return Outcome.Failure(ServiceError.Parse("Valhalla: bad shape in leg $legIndex: ${e.message}"))
            }
            if (legPoints.isEmpty()) return Outcome.Failure(ServiceError.Parse("Valhalla: empty shape in leg $legIndex"))
            // Consecutive legs share their joining point; drop the duplicate and shift indices.
            val dropFirst = geometry.isNotEmpty()
            val offset = if (dropFirst) geometry.size - 1 else 0
            geometry.addAll(if (dropFirst) legPoints.drop(1) else legPoints)
            for (m in leg.maneuvers) {
                maneuvers += toManeuver(m, offset, legPoints.size - 1, unitToM)
            }
        }

        val lengthM = trip.summary?.length?.let { it * unitToM }
            ?: trip.legs.mapNotNull { it.summary?.length }.takeIf { it.size == trip.legs.size }?.sum()?.times(unitToM)
            ?: GeoMath.polylineLengthM(geometry)
        val durationS = trip.summary?.time
            ?: trip.legs.sumOf { leg -> leg.summary?.time ?: leg.maneuvers.sumOf { it.time ?: 0.0 } }
        return Outcome.Success(Route(geometry, maneuvers, lengthM, durationS))
    }

    /** True when the engine names this maneuver's way "the crosswalk" (see class KDoc). */
    fun isCrosswalkManeuver(streetNames: List<String>, instruction: String): Boolean =
        streetNames.none { it.isNotBlank() } && CROSSWALK_PHRASE.containsMatchIn(instruction)

    internal fun errorFor(errorCode: Int, statusCode: Int?): ServiceError = when {
        errorCode in NO_ROUTE_ERROR_CODES -> ServiceError.NotFound
        statusCode == 429 -> ServiceError.RateLimited
        statusCode != null -> ServiceError.Http(statusCode)
        else -> ServiceError.Unknown("Valhalla error_code $errorCode")
    }

    private fun toManeuver(m: ValhallaManeuver, offset: Int, legMaxIndex: Int, unitToM: Double): Maneuver {
        val names = m.streetNames.ifEmpty { m.beginStreetNames }
        val crossing = isCrosswalkManeuver(m.streetNames, m.instruction)
        val baseType = ValhallaManeuverTypes.toManeuverType(m.type)
        // A straight-on step onto a crosswalk is a road crossing; turns keep their direction.
        val type = if (crossing && baseType == ManeuverType.CONTINUE) ManeuverType.CROSS_ROAD else baseType
        return Maneuver(
            type = type,
            engineInstruction = m.instruction,
            streetNames = names,
            beginShapeIndex = offset + m.beginShapeIndex.coerceIn(0, legMaxIndex),
            lengthM = (m.length ?: 0.0) * unitToM,
            bearingBeforeDeg = m.bearingBefore,
            bearingAfterDeg = m.bearingAfter,
            isCrossing = crossing,
        )
    }
}
