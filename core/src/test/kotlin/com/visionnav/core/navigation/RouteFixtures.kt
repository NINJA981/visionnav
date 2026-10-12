package com.visionnav.core.navigation

import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.sensing.LocationFix

/** Synthetic routes and GPS fixes for navigation tests. */
object RouteFixtures {
    val ORIGIN = GeoPoint(13.0500, 80.2500)

    /** Builds a polyline from [start] following (bearing, length) legs. */
    fun polyline(start: GeoPoint, vararg legs: Pair<Double, Double>): List<GeoPoint> {
        val pts = mutableListOf(start)
        for ((bearing, len) in legs) pts += GeoMath.destination(pts.last(), bearing, len)
        return pts
    }

    fun maneuver(
        type: ManeuverType,
        index: Int,
        street: String? = null,
        before: Double? = null,
        after: Double? = null,
        crossing: Boolean = false,
    ) = Maneuver(type, "engine text", listOfNotNull(street), index, 0.0, before, after, crossing)

    /** 300 m due north with a road crossing at 200 m. */
    fun straight(): Route {
        val g = polyline(ORIGIN, 0.0 to 100.0, 0.0 to 100.0, 0.0 to 100.0)
        return Route(
            g,
            listOf(
                maneuver(ManeuverType.START, 0, after = 0.0),
                maneuver(ManeuverType.CROSS_ROAD, 2, street = "Mount Road", before = 0.0, after = 0.0, crossing = true),
                maneuver(ManeuverType.ARRIVE, 3),
            ),
            GeoMath.polylineLengthM(g),
            240.0,
        )
    }

    /** 200 m north, then right (east) 150 m onto Anna Salai, arrive on the right. */
    fun lShape(): Route {
        val g = polyline(ORIGIN, 0.0 to 100.0, 0.0 to 100.0, 90.0 to 75.0, 90.0 to 75.0)
        return Route(
            g,
            listOf(
                maneuver(ManeuverType.START, 0, after = 0.0),
                maneuver(ManeuverType.RIGHT, 2, street = "Anna Salai", before = 0.0, after = 90.0),
                maneuver(ManeuverType.ARRIVE_RIGHT, 4),
            ),
            GeoMath.polylineLengthM(g),
            250.0,
        )
    }

    /** U-shape: 150 m north, 20 m east, 150 m south. The two long legs are parallel, 20 m apart. */
    fun uShape(): Route {
        val g = polyline(ORIGIN, 0.0 to 150.0, 90.0 to 20.0, 180.0 to 150.0)
        return Route(
            g,
            listOf(
                maneuver(ManeuverType.START, 0, after = 0.0),
                maneuver(ManeuverType.RIGHT, 1, before = 0.0, after = 90.0),
                maneuver(ManeuverType.RIGHT, 2, before = 90.0, after = 180.0),
                maneuver(ManeuverType.ARRIVE, 3),
            ),
            GeoMath.polylineLengthM(g),
            250.0,
        )
    }

    /** Point at [alongM] along [line], shifted [lateralM] to the right of travel (negative = left). */
    fun pointAlong(line: List<GeoPoint>, alongM: Double, lateralM: Double = 0.0): GeoPoint {
        var remaining = alongM
        for (i in 0 until line.size - 1) {
            val seg = GeoMath.distanceM(line[i], line[i + 1])
            val bearing = GeoMath.bearingDeg(line[i], line[i + 1])
            if (remaining <= seg || i == line.size - 2) {
                val onLine = GeoMath.destination(line[i], bearing, remaining)
                return if (lateralM == 0.0) onLine else GeoMath.destination(onLine, bearing + 90.0, lateralM)
            }
            remaining -= seg
        }
        return line.last()
    }

    fun fix(point: GeoPoint, tMs: Long, accuracy: Float = 5f, course: Float? = null) =
        LocationFix(point, accuracy, 1.3f, course, null, tMs)
}
