package com.visionnav.core.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Spherical-earth geometry. Accurate to well under GPS error at pedestrian scales. */
object GeoMath {
    const val EARTH_RADIUS_M = 6_371_008.8

    private fun Double.rad() = this * PI / 180.0
    private fun Double.deg() = this * 180.0 / PI

    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = (b.lat - a.lat).rad()
        val dLon = (b.lon - a.lon).rad()
        val h = sin(dLat / 2).let { it * it } +
            cos(a.lat.rad()) * cos(b.lat.rad()) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [a] to [b], degrees true in [0, 360). */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val φ1 = a.lat.rad()
        val φ2 = b.lat.rad()
        val dλ = (b.lon - a.lon).rad()
        val y = sin(dλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dλ)
        return normalizeDeg(atan2(y, x).deg())
    }

    fun destination(from: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val δ = distanceM / EARTH_RADIUS_M
        val θ = bearingDeg.rad()
        val φ1 = from.lat.rad()
        val λ1 = from.lon.rad()
        val φ2 = asin(sin(φ1) * cos(δ) + cos(φ1) * sin(δ) * cos(θ))
        val λ2 = λ1 + atan2(sin(θ) * sin(δ) * cos(φ1), cos(δ) - sin(φ1) * sin(φ2))
        return GeoPoint(φ2.deg(), normalizeLon(λ2.deg()))
    }

    fun normalizeDeg(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    private fun normalizeLon(deg: Double): Double = ((deg + 540.0) % 360.0) - 180.0

    /** Signed smallest difference [to] − [from] in (−180, 180]. Positive = clockwise. */
    fun signedAngleDiff(from: Double, to: Double): Double {
        val d = normalizeDeg(to - from)
        return if (d > 180.0) d - 360.0 else d
    }

    fun absAngleDiff(a: Double, b: Double): Double = abs(signedAngleDiff(a, b))

    /** Result of projecting a point onto a polyline. */
    data class Projection(
        val point: GeoPoint,
        /** Index of the segment start vertex. */
        val segmentIndex: Int,
        /** Fraction along the segment, 0..1. */
        val fraction: Double,
        val distanceM: Double,
        /** Distance along the polyline from its start to [point]. */
        val alongM: Double,
    )

    /**
     * Projects [p] onto [line] using a local equirectangular approximation (fine for < ~5 km).
     * Returns null for an empty line.
     */
    fun projectOntoPolyline(p: GeoPoint, line: List<GeoPoint>): Projection? {
        if (line.isEmpty()) return null
        if (line.size == 1) return Projection(line[0], 0, 0.0, distanceM(p, line[0]), 0.0)
        val cosLat = cos(p.lat.rad())
        fun toXY(g: GeoPoint) = Pair((g.lon - p.lon).rad() * cosLat * EARTH_RADIUS_M, (g.lat - p.lat).rad() * EARTH_RADIUS_M)
        var best: Projection? = null
        var along = 0.0
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            val (ax, ay) = toXY(a)
            val (bx, by) = toXY(b)
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
            val tc = t.coerceIn(0.0, 1.0)
            val px = ax + tc * dx
            val py = ay + tc * dy
            val d = sqrt(px * px + py * py)
            val segLen = sqrt(len2)
            if (best == null || d < best.distanceM) {
                val proj = GeoPoint(a.lat + (b.lat - a.lat) * tc, a.lon + (b.lon - a.lon) * tc)
                best = Projection(proj, i, tc, d, along + segLen * tc)
            }
            along += segLen
        }
        return best
    }

    fun polylineLengthM(line: List<GeoPoint>): Double =
        line.zipWithNext().sumOf { (a, b) -> distanceM(a, b) }

    /** Local bearing of [line] at segment [segmentIndex]. */
    fun segmentBearingDeg(line: List<GeoPoint>, segmentIndex: Int): Double? {
        if (line.size < 2) return null
        val i = segmentIndex.coerceIn(0, line.size - 2)
        return bearingDeg(line[i], line[i + 1])
    }

    /** Converts a relative angle (−180..180, + = right) into a clock-face hour 1..12 (12 = straight ahead). */
    fun clockHour(relativeDeg: Double): Int {
        val h = Math.round(normalizeDeg(relativeDeg) / 30.0).toInt() % 12
        return if (h == 0) 12 else h
    }
}
