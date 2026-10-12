package com.visionnav.mapdata

/**
 * Endpoints, identification and timeouts for the public OSM services used by VisionNav.
 *
 * The public OSM-community services (Valhalla FOSSGIS instance, Overpass API, Photon by Komoot)
 * require a descriptive, contactable `User-Agent`; [userAgent] is sent on every request.
 *
 * All URLs may be pointed at self-hosted instances later without code changes.
 */
data class MapDataConfig(
    /** Valhalla base URL; `/route` is appended. */
    val valhallaBaseUrl: String = "https://valhalla1.openstreetmap.de",
    /** Primary Overpass interpreter endpoint (full URL, used as-is). */
    val overpassBaseUrl: String = "https://overpass-api.de/api/interpreter",
    /** Mirrors tried in order when the primary fails with 5xx / 429 / timeout / connection errors. */
    val overpassFallbackUrls: List<String> = listOf("https://overpass.kumi.systems/api/interpreter"),
    /** Photon base URL; `/api` and `/reverse` are appended. */
    val photonBaseUrl: String = "https://photon.komoot.io",
    val userAgent: String = DEFAULT_USER_AGENT,
    /** Language for engine instructions (Valhalla) and result names (Photon). */
    val valhallaLanguage: String = "en-US",
    val photonLanguage: String = "en",
    val connectTimeoutMs: Long = 10_000,
    /** Socket read timeout. Must exceed the Overpass server-side `[timeout:]` (15 s). */
    val readTimeoutMs: Long = 25_000,
    val writeTimeoutMs: Long = 10_000,
    /** Hard cap on a whole call (connect + write + server time + read). 0 disables it. */
    val callTimeoutMs: Long = 35_000,
) {
    /** Primary Overpass endpoint followed by the fallbacks, without duplicates. */
    val overpassEndpoints: List<String> get() = (listOf(overpassBaseUrl) + overpassFallbackUrls).distinct()

    companion object {
        const val DEFAULT_USER_AGENT =
            "VisionNav/1.0 (accessible pedestrian navigation; contact: rajakumaran1722@gmail.com)"
    }
}
