package com.visionnav.mapdata.overpass

import com.visionnav.core.common.Outcome
import com.visionnav.core.common.ServiceError
import com.visionnav.core.common.SystemTimeSource
import com.visionnav.core.common.TimeSource
import com.visionnav.core.crossing.RoadMetadata
import com.visionnav.core.crossing.RoadMetadataService
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.mapdata.MapDataConfig
import com.visionnav.mapdata.MapDataDiagnostics
import com.visionnav.mapdata.MapService
import com.visionnav.mapdata.http.MapDataHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.io.File

/** Politeness, caching and radius rules for [OverpassRoadMetadataService]. */
data class OverpassPolicy(
    val defaultRadiusM: Double = 120.0,
    val minRadiusM: Double = 30.0,
    val maxRadiusM: Double = 300.0,
    /** Minimum spacing between request starts to the same endpoint. */
    val minIntervalMs: Long = 2_000,
    val cacheTtlMs: Long = 24L * 60 * 60 * 1000,
    val memoryTiles: Int = 64,
    val maxDiskTiles: Int = 512,
    /** First backoff after a 429/504; doubles per consecutive throttle, capped at [backoffMaxMs]. */
    val backoffBaseMs: Long = 5_000,
    val backoffMaxMs: Long = 10 * 60 * 1000,
    /**
     * When every endpoint fails, serve an expired tile not older than [staleMaxAgeMs].
     * The result's `fetchedAtMs` exposes its true age. Road geometry/tags change slowly.
     */
    val serveStaleOnError: Boolean = true,
    val staleMaxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
    /** Overpass server-side `[timeout:]`, seconds. */
    val queryTimeoutS: Int = 15,
)

/**
 * [RoadMetadataService] backed by the Overpass API.
 *
 * - One query per ~250 m tile & radius bucket (see [OverpassTileGrid]), cached 24 h in memory
 *   (LRU, 64 tiles) and optionally on disk under [cacheDir] (pass e.g. `File(context.cacheDir,
 *   "overpass")`; null disables disk caching). Any cached circle that covers the request is reused.
 * - Results are trimmed to the requested circle; tags are verbatim (interpretation lives in
 *   `:core` crossing).
 * - At most one Overpass request in flight (Mutex) and ≥ [OverpassPolicy.minIntervalMs] between
 *   requests to the same endpoint.
 * - 429/504 put that endpoint into exponential backoff (Retry-After honoured if longer). While
 *   backed off, the endpoint is skipped and [ServiceError.RateLimited] is returned rather than
 *   waiting. 5xx, timeouts and connection errors fall through to the fallback endpoints.
 */
class OverpassRoadMetadataService(
    private val http: MapDataHttp,
    private val config: MapDataConfig = MapDataConfig(),
    private val time: TimeSource = SystemTimeSource,
    cacheDir: File? = null,
    private val policy: OverpassPolicy = OverpassPolicy(),
    private val diagnostics: MapDataDiagnostics? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoadMetadataService {

    private val cache = OverpassTileCache(time, policy.cacheTtlMs, policy.memoryTiles, cacheDir, policy.maxDiskTiles)
    private val endpoints = config.overpassEndpoints.map { EndpointState(it) }
    private val requestMutex = Mutex()

    override suspend fun roadsNear(point: GeoPoint, radiusM: Double): Outcome<RoadMetadata> {
        val r = normalizeRadius(radiusM)
        findFresh(point, r)?.let { return hit(it, point, r) }

        return requestMutex.withLock {
            // Another caller may have filled the cache while we waited for the lock.
            findFresh(point, r)?.let { return@withLock hit(it, point, r) }

            val key = OverpassTileGrid.keyFor(point, r)
            val center = OverpassTileGrid.centerOf(key)
            val fetchR = OverpassTileGrid.fetchRadiusM(key)
            when (val fetched = fetch(center, fetchR)) {
                is Outcome.Success -> {
                    val tile = OverpassTile(key, center, fetchR, fetched.value.ways, fetched.value.features, time.wallMs())
                    withContext(ioDispatcher) { cache.put(tile) }
                    diagnostics?.recordSuccess(MapService.OVERPASS)
                    Outcome.Success(trim(tile, point, r))
                }
                is Outcome.Failure -> {
                    diagnostics?.recordFailure(MapService.OVERPASS, fetched.error, "roadsNear r=$r")
                    val stale = if (policy.serveStaleOnError) {
                        withContext(ioDispatcher) { cache.find(point, r, policy.staleMaxAgeMs) }
                    } else {
                        null
                    }
                    if (stale != null) {
                        diagnostics?.recordStaleServed(MapService.OVERPASS)
                        Outcome.Success(trim(stale, point, r))
                    } else {
                        fetched
                    }
                }
            }
        }
    }

    /** Clamps to [OverpassPolicy.minRadiusM]..[OverpassPolicy.maxRadiusM]; non-finite/≤0 → default. */
    fun normalizeRadius(radiusM: Double): Double {
        val r = if (radiusM.isFinite() && radiusM > 0) radiusM else policy.defaultRadiusM
        return r.coerceIn(policy.minRadiusM, policy.maxRadiusM)
    }

    private suspend fun findFresh(point: GeoPoint, r: Double): OverpassTile? =
        withContext(ioDispatcher) { cache.findFresh(point, r) }

    private fun hit(tile: OverpassTile, point: GeoPoint, r: Double): Outcome<RoadMetadata> {
        diagnostics?.recordCacheHit(MapService.OVERPASS)
        return Outcome.Success(trim(tile, point, r))
    }

    /** Restricts a (larger) tile to the requested circle. Pure geometry; tags untouched. */
    private fun trim(tile: OverpassTile, point: GeoPoint, r: Double): RoadMetadata = RoadMetadata(
        center = point,
        radiusM = r,
        ways = tile.ways.filter { w ->
            (GeoMath.projectOntoPolyline(point, w.geometry)?.distanceM ?: Double.MAX_VALUE) <= r
        },
        features = tile.features.filter { GeoMath.distanceM(point, it.point) <= r },
        fetchedAtMs = tile.fetchedAtWallMs,
    )

    /** Tries each endpoint in order. Must be called with [requestMutex] held. */
    private suspend fun fetch(center: GeoPoint, radiusM: Double): Outcome<OverpassData> {
        val query = OverpassQuery.build(center, radiusM, policy.queryTimeoutS)
        val errors = ArrayList<ServiceError>()
        for (ep in endpoints) {
            if (time.elapsedMs() < ep.backoffUntilElapsedMs) {
                errors += ServiceError.RateLimited
                continue
            }
            ep.lastRequestElapsedMs?.let { last ->
                val wait = last + policy.minIntervalMs - time.elapsedMs()
                if (wait > 0) delay(wait)
            }
            ep.lastRequestElapsedMs = time.elapsedMs()
            diagnostics?.recordRequest(MapService.OVERPASS)

            val request = Request.Builder()
                .url(ep.url)
                .post(FormBody.Builder().add("data", query).build())
                .header("Accept", "application/json")
                .build()
            val response = when (val r = http.execute(request)) {
                is Outcome.Failure -> {
                    errors += r.error
                    continue // offline / timeout / reset: try the next mirror
                }
                is Outcome.Success -> r.value
            }
            when {
                response.isSuccessful -> {
                    val parsed = withContext(ioDispatcher) { OverpassParser.parse(response.body) }
                    if (parsed is Outcome.Success && parsed.value.isRuntimeError) {
                        // Partial data from an overloaded server: never cache it.
                        ep.throttled(null)
                        errors += ServiceError.Timeout
                        continue
                    }
                    if (parsed is Outcome.Success) ep.succeeded()
                    return parsed
                }
                response.code == 429 || response.code == 504 -> {
                    ep.throttled(MapDataHttp.parseRetryAfterMs(response.retryAfter, time.wallMs()))
                    errors += if (response.code == 429) ServiceError.RateLimited else ServiceError.Timeout
                }
                response.code == 400 -> return Outcome.Failure(ServiceError.Http(400)) // query bug: same everywhere
                else -> errors += ServiceError.Http(response.code)
            }
        }
        return Outcome.Failure(summarize(errors))
    }

    private fun summarize(errors: List<ServiceError>): ServiceError = when {
        errors.isEmpty() -> ServiceError.Unknown("No Overpass endpoint configured")
        errors.all { it == ServiceError.Offline } -> ServiceError.Offline
        ServiceError.RateLimited in errors -> ServiceError.RateLimited
        ServiceError.Timeout in errors -> ServiceError.Timeout
        else -> errors.last()
    }

    private inner class EndpointState(val url: String) {
        var lastRequestElapsedMs: Long? = null
        var backoffUntilElapsedMs: Long = Long.MIN_VALUE
        private var consecutiveThrottles = 0

        fun succeeded() {
            consecutiveThrottles = 0
            backoffUntilElapsedMs = Long.MIN_VALUE
        }

        fun throttled(retryAfterMs: Long?) {
            consecutiveThrottles++
            val shift = (consecutiveThrottles - 1).coerceAtMost(20)
            val exp = (policy.backoffBaseMs shl shift).coerceAtMost(policy.backoffMaxMs)
            val wait = maxOf(exp, (retryAfterMs ?: 0).coerceAtMost(policy.backoffMaxMs))
            backoffUntilElapsedMs = time.elapsedMs() + wait
        }
    }
}
