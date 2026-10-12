package com.visionnav.mapdata.valhalla

import com.visionnav.core.common.Outcome
import com.visionnav.core.common.ServiceError
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.navigation.Route
import com.visionnav.core.navigation.RoutingService
import com.visionnav.mapdata.MapDataConfig
import com.visionnav.mapdata.MapDataDiagnostics
import com.visionnav.mapdata.MapService
import com.visionnav.mapdata.http.MapDataHttp
import com.visionnav.mapdata.util.MapDataJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Valhalla `pedestrian` costing options sent with every request. */
data class PedestrianCostingOptions(
    /** km/h. Valhalla default is 5.1; 4.5 suits cane / guide-dog walking pace. */
    val walkingSpeedKph: Double = 4.5,
    /** Extra seconds per flight of steps; discourages stairs without forbidding them. */
    val stepPenaltyS: Double = 30.0,
    /** 0..1 preference for living streets (shared space with cars). Below Valhalla's 0.6 default. */
    val useLivingStreets: Double = 0.3,
    /** 0..1; 0 avoids ferries. */
    val useFerry: Double = 0.0,
    /**
     * Valhalla pedestrian `type`: "foot" (default), "wheelchair" or "blind". "blind" changes the
     * maneuver/narrative structure (splits at every intersection); left at "foot" until evaluated.
     */
    val pedestrianType: String = "foot",
)

/**
 * [RoutingService] backed by Valhalla `POST /route` with `costing=pedestrian`.
 *
 * Geometry is decoded from polyline6; lengths are converted to metres; maneuver codes are mapped by
 * [ValhallaManeuverTypes]. See [ValhallaRouteParser] for the (limited) crossing information the
 * engine exposes. No-route errors (170/171/441/442) map to [ServiceError.NotFound].
 */
class ValhallaRoutingService(
    private val http: MapDataHttp,
    private val config: MapDataConfig = MapDataConfig(),
    private val costing: PedestrianCostingOptions = PedestrianCostingOptions(),
    private val diagnostics: MapDataDiagnostics? = null,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Heading tolerance sent with the start heading, degrees. */
    private val headingToleranceDeg: Int = 60,
) : RoutingService {

    private val routeUrl = config.valhallaBaseUrl.toHttpUrl().newBuilder().addPathSegment("route").build()

    override suspend fun walkingRoute(from: GeoPoint, to: GeoPoint, headingDeg: Double?): Outcome<Route> {
        val body = buildRequestJson(from, to, headingDeg).toString()
        val request = Request.Builder()
            .url(routeUrl)
            .post(body.toRequestBody(JSON))
            .header("Accept", "application/json")
            .build()

        diagnostics?.recordRequest(MapService.VALHALLA)
        val outcome = when (val http = http.execute(request)) {
            is Outcome.Failure -> http
            is Outcome.Success -> {
                val resp = http.value
                when {
                    resp.isSuccessful -> withContext(parseDispatcher) { ValhallaRouteParser.parse(resp.body) }
                    resp.code == 429 -> Outcome.Failure(ServiceError.RateLimited)
                    else -> Outcome.Failure(errorFromBody(resp.code, resp.body))
                }
            }
        }
        when (outcome) {
            is Outcome.Success -> diagnostics?.recordSuccess(MapService.VALHALLA)
            is Outcome.Failure -> diagnostics?.recordFailure(MapService.VALHALLA, outcome.error, "route")
        }
        return outcome
    }

    internal fun buildRequestJson(from: GeoPoint, to: GeoPoint, headingDeg: Double?): JsonObject = buildJsonObject {
        putJsonArray("locations") {
            add(
                buildJsonObject {
                    put("lat", from.lat)
                    put("lon", from.lon)
                    if (headingDeg != null && headingDeg.isFinite()) {
                        put("heading", Math.round(GeoMath.normalizeDeg(headingDeg)).toInt() % 360)
                        put("heading_tolerance", headingToleranceDeg)
                    }
                },
            )
            add(
                buildJsonObject {
                    put("lat", to.lat)
                    put("lon", to.lon)
                },
            )
        }
        put("costing", "pedestrian")
        putJsonObject("costing_options") {
            putJsonObject("pedestrian") {
                put("walking_speed", costing.walkingSpeedKph)
                put("step_penalty", costing.stepPenaltyS)
                put("use_living_streets", costing.useLivingStreets)
                put("use_ferry", costing.useFerry)
                if (costing.pedestrianType != "foot") put("type", costing.pedestrianType)
            }
        }
        putJsonObject("directions_options") {
            put("units", "kilometers")
            // en-US is required for crosswalk detection from instruction text (see ValhallaRouteParser).
            put("language", config.valhallaLanguage)
        }
    }

    private fun errorFromBody(httpCode: Int, body: String): ServiceError {
        val parsed = runCatching {
            MapDataJson.decodeFromString(ValhallaResponse.serializer(), body)
        }.getOrNull()
        val code = parsed?.errorCode ?: return ServiceError.Http(httpCode)
        return ValhallaRouteParser.errorFor(code, parsed.statusCode ?: httpCode)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
