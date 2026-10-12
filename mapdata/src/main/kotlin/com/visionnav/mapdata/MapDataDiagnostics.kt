package com.visionnav.mapdata

import com.visionnav.core.common.ServiceError
import com.visionnav.core.common.SystemTimeSource
import com.visionnav.core.common.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The remote services the map layer talks to. */
enum class MapService { VALHALLA, OVERPASS, PHOTON }

/** Per-service counters for the debug screen. */
data class ServiceStats(
    /** Network requests actually sent (cache hits are not requests). */
    val requests: Long = 0,
    val successes: Long = 0,
    val failures: Long = 0,
    val cacheHits: Long = 0,
    /** Responses served from an expired cache entry because the network failed. */
    val staleServed: Long = 0,
    val lastError: ServiceError? = null,
    /** Wall-clock time of [lastError], for display only. */
    val lastErrorAtMs: Long? = null,
    /** Free-text context for [lastError] (endpoint, error message). Never contains user data beyond coordinates. */
    val lastErrorDetail: String? = null,
)

/**
 * Thread-safe, observable counters and last-error info for every map service.
 * One instance is normally shared by all three services (see [MapDataServices]).
 */
class MapDataDiagnostics(private val time: TimeSource = SystemTimeSource) {
    private val _stats = MutableStateFlow(MapService.entries.associateWith { ServiceStats() })

    /** Snapshot stream keyed by service. */
    val stats: StateFlow<Map<MapService, ServiceStats>> = _stats.asStateFlow()

    operator fun get(service: MapService): ServiceStats = _stats.value.getValue(service)

    fun recordRequest(service: MapService) = mutate(service) { it.copy(requests = it.requests + 1) }

    fun recordSuccess(service: MapService) = mutate(service) { it.copy(successes = it.successes + 1) }

    fun recordCacheHit(service: MapService) = mutate(service) { it.copy(cacheHits = it.cacheHits + 1) }

    fun recordStaleServed(service: MapService) = mutate(service) { it.copy(staleServed = it.staleServed + 1) }

    fun recordFailure(service: MapService, error: ServiceError, detail: String? = null) = mutate(service) {
        it.copy(
            failures = it.failures + 1,
            lastError = error,
            lastErrorAtMs = time.wallMs(),
            lastErrorDetail = detail,
        )
    }

    private inline fun mutate(service: MapService, crossinline f: (ServiceStats) -> ServiceStats) {
        _stats.update { m -> m + (service to f(m.getValue(service))) }
    }
}
