package com.visionnav.mapdata.polyline

import com.visionnav.core.geo.GeoPoint
import kotlin.math.roundToLong

/**
 * Encoded-polyline codec (Google algorithm) at a configurable precision.
 * Valhalla encodes shapes with precision 6 (1e-6 degrees, ~11 cm).
 */
object Polyline6 {
    const val VALHALLA_PRECISION = 6

    /**
     * Decodes [encoded] into points.
     * @throws IllegalArgumentException on truncated input or out-of-range coordinates.
     */
    fun decode(encoded: String, precision: Int = VALHALLA_PRECISION): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            val (dLat, nextIndex) = decodeValue(encoded, index)
            val (dLon, afterLon) = decodeValue(encoded, nextIndex)
            index = afterLon
            lat += dLat
            lon += dLon
            out += GeoPoint(lat / factor, lon / factor)
        }
        return out
    }

    /** Encodes [points]; inverse of [decode] up to rounding at [precision]. */
    fun encode(points: List<GeoPoint>, precision: Int = VALHALLA_PRECISION): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lon = (p.lon * factor).roundToLong()
            encodeValue(lat - prevLat, sb)
            encodeValue(lon - prevLon, sb)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun decodeValue(s: String, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (true) {
            require(i < s.length) { "Truncated polyline at index $i" }
            val b = s[i++].code - 63
            require(b in 0..63) { "Invalid polyline character at index ${i - 1}" }
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            require(shift <= 64) { "Polyline value too long at index $i" }
            if (b < 0x20) break
        }
        val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return value to i
    }

    private fun encodeValue(v: Long, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else v shl 1
        while (x >= 0x20) {
            sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
            x = x shr 5
        }
        sb.append((x.toInt() + 63).toChar())
    }
}
