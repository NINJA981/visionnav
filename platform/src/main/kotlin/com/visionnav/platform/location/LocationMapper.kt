package com.visionnav.platform.location

import android.location.Location
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.sensing.LocationFix

/**
 * Maps an Android [Location] to the core [LocationFix] contract.
 *
 * Honesty rules:
 * - Optional fields (speed, course, course accuracy) are only populated when the provider
 *   explicitly reports them (`hasSpeed()`, `hasBearing()`, `hasBearingAccuracy()`); otherwise
 *   they are `null`, never 0.
 * - A location without a horizontal accuracy, or with out-of-range / non-finite coordinates,
 *   is rejected (`null`) rather than published with an invented accuracy.
 */
object LocationMapper {

    /**
     * @param fallbackElapsedMs used only when the provider did not set
     *   `elapsedRealtimeNanos` (0); must be on the [android.os.SystemClock.elapsedRealtime] base.
     * @return the mapped fix, or null if the location is unusable.
     */
    fun toFix(location: Location, fallbackElapsedMs: Long): LocationFix? {
        val lat = location.latitude
        val lon = location.longitude
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (!location.hasAccuracy()) return null
        val accuracy = location.accuracy
        if (!accuracy.isFinite() || accuracy < 0f) return null

        val speed = if (location.hasSpeed() && location.speed.isFinite() && location.speed >= 0f) {
            location.speed
        } else {
            null
        }
        val course = if (location.hasBearing() && location.bearing.isFinite()) {
            normalizeDeg(location.bearing)
        } else {
            null
        }
        val courseAccuracy = if (
            course != null &&
            location.hasBearingAccuracy() &&
            location.bearingAccuracyDegrees.isFinite() &&
            location.bearingAccuracyDegrees >= 0f
        ) {
            location.bearingAccuracyDegrees
        } else {
            null
        }
        val elapsedNanos = location.elapsedRealtimeNanos
        val elapsedMs = if (elapsedNanos > 0L) elapsedNanos / 1_000_000L else fallbackElapsedMs

        return LocationFix(
            point = GeoPoint(lat, lon),
            accuracyM = accuracy,
            speedMps = speed,
            courseDeg = course,
            courseAccuracyDeg = courseAccuracy,
            elapsedMs = elapsedMs,
        )
    }

    private fun normalizeDeg(deg: Float): Float {
        val n = deg % 360f
        return if (n < 0f) n + 360f else n
    }
}
