package com.visionnav.mapdata

import com.visionnav.core.common.SystemTimeSource
import com.visionnav.core.common.TimeSource
import com.visionnav.mapdata.http.HttpClientFactory
import com.visionnav.mapdata.http.MapDataHttp
import com.visionnav.mapdata.overpass.OverpassPolicy
import com.visionnav.mapdata.overpass.OverpassRoadMetadataService
import com.visionnav.mapdata.photon.PhotonGeocodingService
import com.visionnav.mapdata.photon.PhotonPolicy
import com.visionnav.mapdata.valhalla.PedestrianCostingOptions
import com.visionnav.mapdata.valhalla.ValhallaRoutingService
import okhttp3.OkHttpClient
import java.io.File

/**
 * Convenience wiring of all map services over one shared [OkHttpClient] and one
 * [MapDataDiagnostics]. Typical Android usage:
 *
 * ```
 * val mapData = MapDataServices.create(overpassCacheDir = File(context.cacheDir, "overpass"))
 * mapData.routing.walkingRoute(from, to, heading)
 * ```
 */
class MapDataServices(
    val routing: ValhallaRoutingService,
    val roadMetadata: OverpassRoadMetadataService,
    val geocoding: PhotonGeocodingService,
    val diagnostics: MapDataDiagnostics,
) {
    companion object {
        fun create(
            config: MapDataConfig = MapDataConfig(),
            overpassCacheDir: File? = null,
            time: TimeSource = SystemTimeSource,
            baseClient: OkHttpClient? = null,
            costing: PedestrianCostingOptions = PedestrianCostingOptions(),
            overpassPolicy: OverpassPolicy = OverpassPolicy(),
            photonPolicy: PhotonPolicy = PhotonPolicy(),
        ): MapDataServices {
            val http = MapDataHttp(HttpClientFactory.create(config, baseClient))
            val diagnostics = MapDataDiagnostics(time)
            return MapDataServices(
                routing = ValhallaRoutingService(http, config, costing, diagnostics),
                roadMetadata = OverpassRoadMetadataService(http, config, time, overpassCacheDir, overpassPolicy, diagnostics),
                geocoding = PhotonGeocodingService(http, config, time, photonPolicy, diagnostics),
                diagnostics = diagnostics,
            )
        }
    }
}
