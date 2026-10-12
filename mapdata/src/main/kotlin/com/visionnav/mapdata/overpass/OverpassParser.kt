package com.visionnav.mapdata.overpass

import com.visionnav.core.common.Outcome
import com.visionnav.core.common.ServiceError
import com.visionnav.core.crossing.MappedFeature
import com.visionnav.core.crossing.RoadWay
import com.visionnav.core.geo.GeoPoint
import com.visionnav.mapdata.util.MapDataJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.util.Locale

@Serializable
internal data class OverpassResponse(
    val elements: List<OverpassElement> = emptyList(),
    /** Set by Overpass on partial/failed queries, e.g. "runtime error: Query timed out ...". */
    val remark: String? = null,
)

@Serializable
internal data class OverpassElement(
    val type: String = "",
    val id: Long = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Inline way geometry from `out geom`; entries can be null for clipped vertices. */
    val geometry: List<OverpassLatLon?>? = null,
    val tags: Map<String, String> = emptyMap(),
)

@Serializable
internal data class OverpassLatLon(val lat: Double, val lon: Double)

/** Raw parse result: OSM elements with tags exactly as delivered. */
data class OverpassData(
    val ways: List<RoadWay>,
    val features: List<MappedFeature>,
    /** Overpass `remark`, if any. */
    val remark: String?,
) {
    /** Overpass reports server-side failures as a 200 with a "runtime error" remark and partial data. */
    val isRuntimeError: Boolean get() = remark?.contains("runtime error", ignoreCase = true) == true
}

/**
 * Parses an Overpass `[out:json]` body produced by `out geom tags`.
 *
 * - Ways with `highway=steps` become a [MappedFeature] at the way's first vertex
 *   (`osmNodeId` then holds the **way id**); all other ways become [RoadWay]s.
 * - Nodes become [MappedFeature]s.
 * - Tags are copied verbatim — no defaults, no normalisation. A missing tag stays missing.
 * - Elements without usable coordinates are dropped; duplicates (same type+id) are dropped.
 */
object OverpassParser {

    fun parse(body: String): Outcome<OverpassData> {
        val response = try {
            MapDataJson.decodeFromString(OverpassResponse.serializer(), body)
        } catch (e: SerializationException) {
            return Outcome.Failure(ServiceError.Parse("Overpass: ${e.message?.take(200)}"))
        } catch (e: IllegalArgumentException) {
            return Outcome.Failure(ServiceError.Parse("Overpass: ${e.message?.take(200)}"))
        }
        val ways = ArrayList<RoadWay>()
        val features = ArrayList<MappedFeature>()
        val seen = HashSet<String>()
        for (el in response.elements) {
            val type = el.type.lowercase(Locale.ROOT)
            if (!seen.add("$type/${el.id}")) continue
            when (type) {
                "node" -> {
                    val p = point(el.lat, el.lon) ?: continue
                    features += MappedFeature(el.id, p, el.tags)
                }
                "way" -> {
                    val geometry = el.geometry.orEmpty().mapNotNull { it?.let { ll -> point(ll.lat, ll.lon) } }
                    if (geometry.isEmpty()) continue
                    if (el.tags["highway"] == "steps") {
                        features += MappedFeature(el.id, geometry.first(), el.tags)
                    } else {
                        ways += RoadWay(el.id, geometry, el.tags)
                    }
                }
                else -> Unit
            }
        }
        return Outcome.Success(OverpassData(ways, features, response.remark))
    }

    private fun point(lat: Double?, lon: Double?): GeoPoint? {
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return GeoPoint(lat, lon)
    }
}

/** Builds the Overpass QL used for road/crossing context. */
object OverpassQuery {
    private const val ROAD_HIGHWAY_REGEX =
        "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|service|living_street|" +
            "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|road)$"

    /**
     * Roads, crossing nodes, traffic signals, entrances, steps and kerbs within [radiusM] of
     * [center], with inline way geometry (`out geom`).
     */
    fun build(center: GeoPoint, radiusM: Double, timeoutS: Int = 15): String {
        val a = String.format(Locale.ROOT, "around:%.0f,%.7f,%.7f", radiusM, center.lat, center.lon)
        return buildString {
            append("[out:json][timeout:").append(timeoutS).append("];(")
            append("way(").append(a).append(")[highway~\"").append(ROAD_HIGHWAY_REGEX).append("\"];")
            append("node(").append(a).append(")[highway=crossing];")
            append("node(").append(a).append(")[crossing];")
            append("node(").append(a).append(")[highway=traffic_signals];")
            append("node(").append(a).append(")[entrance];")
            append("way(").append(a).append(")[highway=steps];")
            append("node(").append(a).append(")[kerb];")
            append(");out geom tags;")
        }
    }
}
