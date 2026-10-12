package com.visionnav.core.crossing

import com.visionnav.core.common.DataSource
import com.visionnav.core.common.Reliability
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.crossing.CrossingCandidate.MappedCrossing
import com.visionnav.core.crossing.CrossingCandidate.PossibleCrossing
import com.visionnav.core.crossing.CrossingCandidate.VisualCrosswalk
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.navigation.Route
import com.visionnav.core.sensing.LocationFix
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tunables for [RoadContextInterpreter]. Defaults follow docs/PLAN.md §4.1. */
data class RoadContextConfig(
    /** Vehicle roads whose closest point is farther than this from the user are dropped. */
    val roadSearchRadiusM: Double = 60.0,
    /** Mapped crossings farther than this from the user are dropped. */
    val crossingSearchRadiusM: Double = 80.0,
    /** GPS accuracy (68 % radius) above which position-based claims are withheld. */
    val lowAccuracyThresholdM: Float = 25f,
    /** GPS proximity at which the user is "near" a crossing — still only [UserPositionAssessment.NEAR_UNCONFIRMED]. */
    val nearCrossingM: Double = 15.0,
    /** How far ahead along the route an unmapped road crossing is looked for. */
    val possibleCrossingLookAheadM: Double = 40.0,
    /** Route crossing maneuvers that begin up to this far behind the user still count (user may be on it). */
    val possibleCrossingBehindToleranceM: Double = 5.0,
    /** A possible crossing is suppressed if a mapped crossing lies within this radius. */
    val mappedCrossingMatchRadiusM: Double = 15.0,
    /** Possible crossings closer than this to each other are merged. */
    val possibleCrossingMergeRadiusM: Double = 10.0,
    /** Route points this far before/after an intersection are probed to verify the route really crosses. */
    val crossingSideProbeM: Double = 4.0,
    /** Minimum lateral offset of the probe points from the road line to count as "on the other side". */
    val minCrossingSideOffsetM: Double = 0.3,
    /** Candidates within this distance compete for "which crossing is the user at". */
    val ambiguityRadiusM: Double = 30.0,
    /** Two competing candidates whose distances differ by less than this are ambiguous. */
    val ambiguityMarginM: Double = 5.0,
    /** With a route, only candidates this close to the route geometry compete. */
    val routeRelevanceM: Double = 12.0,
)

/**
 * Turns raw map tags, an optional GPS fix and an optional route into a [RoadCrossingContext].
 *
 * Safety rationale:
 * - **Never assume.** A road without an `oneway` tag is [RoadDirectionality.UNKNOWN] with zero
 *   expected flows. Treating "untagged" as two-way is common in routing engines, but for a
 *   blind pedestrian an incorrect "two-way road" claim is worse than "road direction unavailable".
 * - **Never fabricate.** Without a GPS fix, `location`/`locationAccuracyM` are null, distances are
 *   null and the user position is [UserPositionAssessment.UNKNOWN].
 * - **Never merge evidence kinds.** Mapped, visual and possible crossings stay distinct types; GPS
 *   proximity alone yields at most [UserPositionAssessment.NEAR_UNCONFIRMED].
 *
 * Pure and stateless; safe to call from any thread.
 */
class RoadContextInterpreter(private val config: RoadContextConfig = RoadContextConfig()) {

    fun interpret(metadata: RoadMetadata?, location: LocationFix?, route: Route?, nowMs: Long): RoadCrossingContext {
        val uncertainties = mutableSetOf<UncertaintyReason>()
        val here = location?.point
        val accuracy = location?.accuracyM
        val accuracyOk = accuracy != null && accuracy.isFinite() && accuracy <= config.lowAccuracyThresholdM
        when {
            location == null -> uncertainties += UncertaintyReason.GPS_UNAVAILABLE
            !accuracyOk -> uncertainties += UncertaintyReason.GPS_LOW_ACCURACY
        }
        if (metadata == null) {
            uncertainties += UncertaintyReason.MAP_DATA_UNAVAILABLE
            uncertainties += UncertaintyReason.ROAD_DIRECTION_UNAVAILABLE
        }

        // The segment whose bearing we report is chosen near the user; without GPS we fall back to the
        // map query centre. That only picks which segment of a mapped way to describe — it is never
        // reported as the user's location, and distances stay null.
        val bearingReference = here ?: metadata?.center
        val roads = metadata?.ways.orEmpty()
            .filter { isVehicleRoad(it.tags) }
            .map { interpretRoad(it, bearingReference, here) }
            .filter { it.distanceM == null || it.distanceM <= config.roadSearchRadiusM }
            .sortedWith(compareBy(nullsLast<Double>()) { it.distanceM })
        if (roads.any { it.directionality == RoadDirectionality.UNKNOWN }) {
            uncertainties += UncertaintyReason.ROAD_DIRECTION_UNAVAILABLE
        }

        val mapped = metadata?.features.orEmpty()
            .mapNotNull { interpretMappedCrossing(it, here) }
            .filter { it.distanceM == null || it.distanceM <= config.crossingSearchRadiusM }
        // "Ahead along the route" is only meaningful with a position fix.
        val possible = if (here != null && route != null) findPossibleCrossings(route, here, roads, mapped) else emptyList()
        val crossings: List<CrossingCandidate> = (mapped + possible).sortedWith(compareBy(nullsLast<Double>()) { it.distanceOrNull() })

        if (here != null && isAmbiguous(crossings, route)) uncertainties += UncertaintyReason.CROSSING_AMBIGUOUS

        val userPosition = when {
            here == null || !accuracyOk -> UserPositionAssessment.UNKNOWN
            crossings.any { c -> c.distanceOrNull()?.let { it <= config.nearCrossingM } == true } ->
                UserPositionAssessment.NEAR_UNCONFIRMED
            metadata == null -> UserPositionAssessment.UNKNOWN
            else -> UserPositionAssessment.NOT_NEAR
        }

        return RoadCrossingContext(
            location = here,
            locationAccuracyM = accuracy,
            roads = roads,
            crossings = crossings,
            userPosition = userPosition,
            uncertainties = uncertainties,
            elapsedMs = nowMs,
        )
    }

    private fun interpretRoad(way: RoadWay, reference: GeoPoint?, here: GeoPoint?): RoadSegmentContext {
        val parsed = parseDirectionality(way.tags)
        val segmentIndex = reference?.let { GeoMath.projectOntoPolyline(it, way.geometry)?.segmentIndex } ?: 0
        val localBearing = GeoMath.segmentBearingDeg(way.geometry, segmentIndex)

        var directionality = parsed.directionality
        var reliability = parsed.reliability
        val flows = when {
            directionality == RoadDirectionality.UNKNOWN -> emptyList()
            localBearing == null -> {
                // Direction is tagged but the geometry can't express it: report UNKNOWN rather than guess.
                directionality = RoadDirectionality.UNKNOWN
                reliability = Reliability.UNKNOWN
                emptyList()
            }
            directionality == RoadDirectionality.ONE_WAY -> listOf(
                ExpectedFlow(GeoMath.normalizeDeg(localBearing + if (parsed.reversed) 180.0 else 0.0), DataSource.OSM_MAP, reliability),
            )
            else -> listOf(
                ExpectedFlow(GeoMath.normalizeDeg(localBearing), DataSource.OSM_MAP, reliability),
                ExpectedFlow(GeoMath.normalizeDeg(localBearing + 180.0), DataSource.OSM_MAP, reliability),
            )
        }
        return RoadSegmentContext(
            osmWayId = way.osmWayId,
            name = way.tags["name"] ?: way.tags["ref"],
            highwayType = way.tags["highway"],
            geometry = way.geometry,
            directionality = directionality,
            expectedFlows = flows,
            lanes = way.tags["lanes"]?.trim()?.toIntOrNull()?.takeIf { it in 1..20 },
            maxSpeedKph = parseMaxSpeedKph(way.tags["maxspeed"]),
            distanceM = here?.let { GeoMath.projectOntoPolyline(it, way.geometry)?.distanceM },
            source = DataSource.OSM_MAP,
            reliability = reliability,
        )
    }

    private fun interpretMappedCrossing(feature: MappedFeature, here: GeoPoint?): MappedCrossing? {
        val t = feature.tags
        val highway = t["highway"]?.lowercase()
        val crossing = t["crossing"]?.lowercase()
        if (crossing == "no") return null
        if (highway != "crossing" && crossing == null) return null

        val markings = t["crossing:markings"]?.lowercase()
        val control = when {
            crossing == "traffic_signals" || highway == "traffic_signals" || t["crossing:signals"]?.lowercase() == "yes" ->
                CrossingControl.TRAFFIC_SIGNALS
            crossing == "zebra" || crossing == "marked" || t["crossing_ref"]?.lowercase() == "zebra" ||
                (markings != null && markings != "no") -> CrossingControl.MARKED_ZEBRA
            crossing == "uncontrolled" -> CrossingControl.UNCONTROLLED
            crossing == "unmarked" || markings == "no" -> CrossingControl.UNMARKED
            else -> CrossingControl.UNKNOWN
        }
        val island = t["crossing:island"]?.lowercase()
        val refuge = when {
            island == "yes" || t["traffic_calming"]?.lowercase() == "island" -> RefugeStatus.MAPPED
            island == "no" -> RefugeStatus.NOT_MAPPED
            else -> RefugeStatus.UNKNOWN
        }
        val tactile = when (t["tactile_paving"]?.lowercase()) {
            "yes" -> true
            "no" -> false
            else -> null
        }
        val audible = when (t["traffic_signals:sound"]?.lowercase()) {
            "yes", "walk" -> true
            "no" -> false
            else -> null
        }
        return MappedCrossing(
            osmNodeId = feature.osmNodeId,
            point = feature.point,
            control = control,
            refuge = refuge,
            hasTactilePaving = tactile,
            hasAudibleSignal = audible,
            distanceM = here?.let { GeoMath.distanceM(it, feature.point) },
        )
    }

    private data class Hit(val xy: Vec, val roadName: String?)

    /**
     * Unmapped places where the route crosses a road within the look-ahead window:
     * (a) route maneuvers flagged `isCrossing`, (b) route geometry that genuinely crosses a vehicle
     * road way (the route is on opposite sides of the road before and after the intersection, so
     * walking *along* a road or merely touching it does not count). Suppressed where a mapped
     * crossing exists nearby — mapped evidence is reported as such, never duplicated.
     */
    private fun findPossibleCrossings(
        route: Route,
        here: GeoPoint,
        roads: List<RoadSegmentContext>,
        mapped: List<MappedCrossing>,
    ): List<PossibleCrossing> {
        if (route.geometry.isEmpty()) return emptyList()
        val frame = LocalFrame(here)
        val line = PlanarPolyline(route.geometry.map(frame::toXY))
        val userAlong = line.alongOf(Vec(0.0, 0.0))
        val aheadEnd = userAlong + config.possibleCrossingLookAheadM
        val hits = mutableListOf<Hit>()

        for (m in route.maneuvers) {
            if (!m.isCrossing || m.beginShapeIndex !in line.points.indices) continue
            val along = line.cumulative[m.beginShapeIndex]
            if (along >= userAlong - config.possibleCrossingBehindToleranceM && along <= aheadEnd) {
                hits += Hit(line.points[m.beginShapeIndex], m.streetNames.firstOrNull())
            }
        }

        val pts = line.points
        for (road in roads) {
            if (road.geometry.size < 2) continue
            val rp = road.geometry.map(frame::toXY)
            for (i in 0 until pts.size - 1) {
                if (line.cumulative[i + 1] < userAlong || line.cumulative[i] > aheadEnd) continue
                val segLen = line.cumulative[i + 1] - line.cumulative[i]
                for (j in 0 until rp.size - 1) {
                    val t = PlanarPolyline.intersect(pts[i], pts[i + 1], rp[j], rp[j + 1]) ?: continue
                    val along = line.cumulative[i] + segLen * t
                    if (along < userAlong || along > aheadEnd) continue
                    val before = PlanarPolyline.signedOffset(line.pointAt(along - config.crossingSideProbeM), rp[j], rp[j + 1])
                    val after = PlanarPolyline.signedOffset(line.pointAt(along + config.crossingSideProbeM), rp[j], rp[j + 1])
                    val crosses = before * after < 0 &&
                        abs(before) >= config.minCrossingSideOffsetM && abs(after) >= config.minCrossingSideOffsetM
                    if (crosses) hits += Hit(pts[i] + (pts[i + 1] - pts[i]) * t, road.name)
                }
            }
        }

        val merged = mutableListOf<Hit>()
        for (h in hits) {
            val idx = merged.indexOfFirst { it.xy.distanceTo(h.xy) <= config.possibleCrossingMergeRadiusM }
            if (idx < 0) merged += h else if (merged[idx].roadName == null && h.roadName != null) merged[idx] = merged[idx].copy(roadName = h.roadName)
        }
        return merged
            .map { frame.toGeo(it.xy) to it.roadName }
            .filter { (p, _) -> mapped.none { GeoMath.distanceM(it.point, p) <= config.mappedCrossingMatchRadiusM } }
            .map { (p, name) -> PossibleCrossing(point = p, roadName = name, distanceM = GeoMath.distanceM(here, p)) }
    }

    private fun isAmbiguous(crossings: List<CrossingCandidate>, route: Route?): Boolean {
        val distances = crossings.mapNotNull { c ->
            val d = c.distanceOrNull() ?: return@mapNotNull null
            if (d > config.ambiguityRadiusM) return@mapNotNull null
            val p = c.pointOrNull()
            val onRoute = route == null || c is PossibleCrossing || p == null ||
                (GeoMath.projectOntoPolyline(p, route.geometry)?.distanceM ?: Double.MAX_VALUE) <= config.routeRelevanceM
            if (onRoute) d else null
        }.sorted()
        return distances.size >= 2 && distances[1] - distances[0] <= config.ambiguityMarginM
    }

    /** Parsed `oneway` semantics. [reversed] means traffic flows against the way's node order. */
    data class ParsedDirection(
        val directionality: RoadDirectionality,
        val reversed: Boolean,
        val reliability: Reliability,
    )

    companion object {
        private val VEHICLE_HIGHWAYS = setOf(
            "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
            "residential", "service", "living_street", "road", "busway",
        )
        private val NON_VEHICLE_HIGHWAYS = setOf(
            "footway", "path", "cycleway", "steps", "pedestrian", "track", "bridleway", "corridor",
        )
        private val MOTOR_ACCESS_ALLOWED = setOf(
            "yes", "designated", "permissive", "destination", "delivery", "private", "customers",
        )

        /**
         * True for ways that motor vehicles may use. Paths/footways count only when motor vehicles
         * are explicitly allowed. Access restrictions on real roads are ignored on purpose:
         * excluding a road could hide traffic (buses, deliveries) from the user.
         */
        fun isVehicleRoad(tags: Map<String, String>): Boolean {
            val highway = tags["highway"]?.trim()?.lowercase() ?: return false
            if (highway in VEHICLE_HIGHWAYS) return true
            if (highway.endsWith("_link") && highway.removeSuffix("_link") in VEHICLE_HIGHWAYS) return true
            if (highway in NON_VEHICLE_HIGHWAYS) {
                return listOf("motor_vehicle", "motorcar").any { tags[it]?.trim()?.lowercase() in MOTOR_ACCESS_ALLOWED }
            }
            return false
        }

        /**
         * `oneway=yes|1|true` → one-way along node order; `-1|reverse` → reversed; `no|false|0` → two-way;
         * `alternating|reversible|<other>` → UNKNOWN; missing → UNKNOWN, except `junction=roundabout|circular`
         * which is one-way by OSM convention (INFERRED). Mode-specific keys such as `oneway:bus` or
         * `oneway:bicycle` are deliberately ignored: they say nothing about car traffic.
         */
        fun parseDirectionality(tags: Map<String, String>): ParsedDirection {
            val oneway = tags["oneway"]?.trim()?.lowercase()
            val junction = tags["junction"]?.trim()?.lowercase()
            return when (oneway) {
                "yes", "1", "true" -> ParsedDirection(RoadDirectionality.ONE_WAY, false, Reliability.MAPPED)
                "-1", "reverse" -> ParsedDirection(RoadDirectionality.ONE_WAY, true, Reliability.MAPPED)
                "no", "false", "0" -> ParsedDirection(RoadDirectionality.TWO_WAY, false, Reliability.MAPPED)
                null, "" -> if (junction == "roundabout" || junction == "circular") {
                    ParsedDirection(RoadDirectionality.ONE_WAY, false, Reliability.INFERRED)
                } else {
                    ParsedDirection(RoadDirectionality.UNKNOWN, false, Reliability.UNKNOWN)
                }
                else -> ParsedDirection(RoadDirectionality.UNKNOWN, false, Reliability.UNKNOWN)
            }
        }

        private val SPEED = Regex("^(\\d+(?:\\.\\d+)?)\\s*(mph|km/h|kmh|kph)?$")

        /** "50" → 50, "50 km/h" → 50, "30 mph" → 48; symbolic values ("walk", "none", "RU:urban") → null. */
        fun parseMaxSpeedKph(value: String?): Int? {
            val v = value?.trim()?.lowercase() ?: return null
            val m = SPEED.matchEntire(v) ?: return null
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            val kph = if (m.groupValues[2] == "mph") n * 1.609344 else n
            return kph.roundToInt().takeIf { it > 0 }
        }
    }
}

internal fun CrossingCandidate.distanceOrNull(): Double? = when (this) {
    is MappedCrossing -> distanceM
    is PossibleCrossing -> distanceM
    is VisualCrosswalk -> null
}

internal fun CrossingCandidate.pointOrNull(): GeoPoint? = when (this) {
    is MappedCrossing -> point
    is PossibleCrossing -> point
    is VisualCrosswalk -> null
}
