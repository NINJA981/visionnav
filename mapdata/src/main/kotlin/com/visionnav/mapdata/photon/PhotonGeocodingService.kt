package com.visionnav.mapdata.photon

import com.visionnav.core.common.Outcome
import com.visionnav.core.common.ServiceError
import com.visionnav.core.common.SystemTimeSource
import com.visionnav.core.common.TimeSource
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.navigation.GeocodingService
import com.visionnav.core.navigation.Place
import com.visionnav.mapdata.MapDataConfig
import com.visionnav.mapdata.MapDataDiagnostics
import com.visionnav.mapdata.MapService
import com.visionnav.mapdata.http.MapDataHttp
import com.visionnav.mapdata.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Locale

/** Ranking / caching knobs for [PhotonGeocodingService]. */
data class PhotonPolicy(
    /** Results within this distance of the bias point rank first (Photon order kept inside each group). */
    val nearbyRadiusM: Double = 5_000.0,
    /** Same (case-insensitive) name within this distance → duplicate; first one kept. */
    val dedupeDistanceM: Double = 30.0,
    val cacheTtlMs: Long = 5 * 60 * 1000,
    val cacheEntries: Int = 64,
    /** Hard cap on the number of results requested from Photon. */
    val maxRequestLimit: Int = 20,
)

/**
 * [GeocodingService] backed by Photon (`/api` search with location bias, `/reverse`).
 *
 * Search over-fetches (2× `limit`) so that de-duplication still yields `limit` results, then
 * re-ranks: results within [PhotonPolicy.nearbyRadiusM] of `near` first, each group in Photon's
 * relevance order. Identical queries (same text, bias rounded to ~100 m, same limit) are served
 * from a 5-minute in-memory cache; distances are always recomputed from the exact `near`.
 */
class PhotonGeocodingService(
    private val http: MapDataHttp,
    private val config: MapDataConfig = MapDataConfig(),
    private val time: TimeSource = SystemTimeSource,
    private val policy: PhotonPolicy = PhotonPolicy(),
    private val diagnostics: MapDataDiagnostics? = null,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GeocodingService {

    private val baseUrl: HttpUrl = config.photonBaseUrl.toHttpUrl()
    private val cache = LruCache<String, CachedSearch>(policy.cacheEntries)

    private class CachedSearch(val places: List<Place>, val atElapsedMs: Long)

    override suspend fun search(query: String, near: GeoPoint?, limit: Int): Outcome<List<Place>> {
        val q = query.trim()
        if (q.isEmpty() || limit <= 0) return Outcome.Success(emptyList())
        val requestLimit = (limit * 2).coerceIn(limit.coerceAtMost(policy.maxRequestLimit), policy.maxRequestLimit)
        val key = cacheKey(q, near, requestLimit)

        val cached = cache[key]?.takeIf { time.elapsedMs() - it.atElapsedMs in 0..policy.cacheTtlMs }
        val raw: List<Place> = if (cached != null) {
            diagnostics?.recordCacheHit(MapService.PHOTON)
            cached.places
        } else {
            val url = baseUrl.newBuilder().addPathSegment("api")
                .addQueryParameter("q", q)
                .addQueryParameter("limit", requestLimit.toString())
                .apply {
                    if (near != null) {
                        addQueryParameter("lat", fmt(near.lat))
                        addQueryParameter("lon", fmt(near.lon))
                    }
                }
                .addQueryParameter("lang", config.photonLanguage)
                .build()
            when (val r = get(url) { body -> PhotonParser.parse(body, near = null) }) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value.also { cache[key] = CachedSearch(it, time.elapsedMs()) }
            }
        }
        return Outcome.Success(rank(raw, near, limit))
    }

    override suspend fun reverse(point: GeoPoint): Outcome<Place> {
        val url = baseUrl.newBuilder().addPathSegment("reverse")
            .addQueryParameter("lat", fmt(point.lat))
            .addQueryParameter("lon", fmt(point.lon))
            .addQueryParameter("lang", config.photonLanguage)
            .build()
        return when (val r = get(url) { body -> PhotonParser.parse(body, near = point) }) {
            is Outcome.Failure -> r
            is Outcome.Success -> r.value.minByOrNull { it.distanceM ?: Double.MAX_VALUE }
                ?.let { Outcome.Success(it) }
                ?: Outcome.Failure(ServiceError.NotFound)
        }
    }

    /**
     * Distance annotation, nearby-first ranking and de-duplication. Pure; exposed for tests.
     * [places] must be in Photon order.
     */
    internal fun rank(places: List<Place>, near: GeoPoint?, limit: Int): List<Place> {
        val withDistance = places.map { p -> p.copy(distanceM = near?.let { GeoMath.distanceM(it, p.point) }) }
        val ordered = if (near == null) {
            withDistance
        } else {
            val (nearby, far) = withDistance.partition { (it.distanceM ?: Double.MAX_VALUE) <= policy.nearbyRadiusM }
            nearby + far
        }
        val kept = ArrayList<Place>(limit)
        for (p in ordered) {
            if (kept.size >= limit) break
            val dup = kept.any {
                it.name.equals(p.name, ignoreCase = true) && GeoMath.distanceM(it.point, p.point) <= policy.dedupeDistanceM
            }
            if (!dup) kept += p
        }
        return kept
    }

    private suspend fun <T> get(url: HttpUrl, parse: (String) -> Outcome<T>): Outcome<T> {
        val request = Request.Builder().url(url).get().header("Accept", "application/json").build()
        diagnostics?.recordRequest(MapService.PHOTON)
        val outcome: Outcome<T> = when (val r = http.execute(request)) {
            is Outcome.Failure -> r
            is Outcome.Success -> if (r.value.isSuccessful) {
                withContext(parseDispatcher) { parse(r.value.body) }
            } else {
                Outcome.Failure(MapDataHttp.statusToError(r.value.code))
            }
        }
        when (outcome) {
            is Outcome.Success -> diagnostics?.recordSuccess(MapService.PHOTON)
            is Outcome.Failure -> diagnostics?.recordFailure(MapService.PHOTON, outcome.error, url.encodedPath)
        }
        return outcome
    }

    private fun cacheKey(q: String, near: GeoPoint?, requestLimit: Int): String {
        val bias = near?.let { String.format(Locale.ROOT, "%.3f,%.3f", it.lat, it.lon) } ?: "-"
        return "${q.lowercase(Locale.ROOT)}|$bias|$requestLimit"
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.6f", v)
}
