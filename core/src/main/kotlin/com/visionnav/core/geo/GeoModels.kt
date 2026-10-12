package com.visionnav.core.geo

/** WGS84 coordinate in degrees. */
data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "lat out of range: $lat" }
        require(lon in -180.0..180.0) { "lon out of range: $lon" }
    }
}

/** Bearing in degrees clockwise from true north, normalised to [0, 360). */
@JvmInline
value class Bearing(val degrees: Double) {
    companion object {
        fun of(deg: Double): Bearing = Bearing(((deg % 360.0) + 360.0) % 360.0)
    }
}
