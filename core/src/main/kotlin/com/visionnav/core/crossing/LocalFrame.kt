package com.visionnav.core.crossing

import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Planar vector in metres (x = east, y = north) inside a [LocalFrame]. */
internal data class Vec(val x: Double, val y: Double) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
    operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec(x * k, y * k)
    fun cross(o: Vec): Double = x * o.y - y * o.x
    fun dot(o: Vec): Double = x * o.x + y * o.y
    fun length(): Double = sqrt(x * x + y * y)
    fun distanceTo(o: Vec): Double = (this - o).length()
}

/**
 * Local equirectangular projection around [origin]. Accurate to centimetres over the few
 * hundred metres the crossing logic looks at, which is far below GPS error.
 */
internal class LocalFrame(private val origin: GeoPoint) {
    private val cosLat = cos(origin.lat * PI / 180.0)

    fun toXY(p: GeoPoint): Vec = Vec(
        (p.lon - origin.lon) * PI / 180.0 * cosLat * GeoMath.EARTH_RADIUS_M,
        (p.lat - origin.lat) * PI / 180.0 * GeoMath.EARTH_RADIUS_M,
    )

    fun toGeo(v: Vec): GeoPoint = GeoPoint(
        origin.lat + v.y / GeoMath.EARTH_RADIUS_M * 180.0 / PI,
        origin.lon + v.x / (GeoMath.EARTH_RADIUS_M * cosLat) * 180.0 / PI,
    )
}

/** A polyline in a [LocalFrame] with cumulative lengths, for along-distance queries. */
internal class PlanarPolyline(val points: List<Vec>) {
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + points[i - 1].distanceTo(points[i])
    }
    val length: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative.last()

    /** Distance along the polyline of the point closest to [p]. */
    fun alongOf(p: Vec): Double {
        if (points.size < 2) return 0.0
        var bestDist = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val d = points[i + 1] - a
            val len2 = d.dot(d)
            val t = if (len2 == 0.0) 0.0 else ((p - a).dot(d) / len2).coerceIn(0.0, 1.0)
            val q = a + d * t
            val dist = q.distanceTo(p)
            if (dist < bestDist) {
                bestDist = dist
                bestAlong = cumulative[i] + sqrt(len2) * t
            }
        }
        return bestAlong
    }

    /** Point at [along] metres from the start, clamped to the polyline ends. */
    fun pointAt(along: Double): Vec {
        if (points.size == 1) return points[0]
        val a = along.coerceIn(0.0, length)
        for (i in 0 until points.size - 1) {
            if (a <= cumulative[i + 1] || i == points.size - 2) {
                val segLen = cumulative[i + 1] - cumulative[i]
                val t = if (segLen == 0.0) 0.0 else ((a - cumulative[i]) / segLen).coerceIn(0.0, 1.0)
                return points[i] + (points[i + 1] - points[i]) * t
            }
        }
        return points.last()
    }

    companion object {
        /**
         * Intersection parameter `t` on segment p1→p2 where it meets q1→q2 (endpoints inclusive),
         * or null if they don't intersect or are parallel/collinear (running *along* a road is not crossing it).
         */
        fun intersect(p1: Vec, p2: Vec, q1: Vec, q2: Vec): Double? {
            val r = p2 - p1
            val s = q2 - q1
            val denom = r.cross(s)
            if (kotlin.math.abs(denom) < 1e-9) return null
            val qp = q1 - p1
            val t = qp.cross(s) / denom
            val u = qp.cross(r) / denom
            val eps = 1e-9
            if (t < -eps || t > 1 + eps || u < -eps || u > 1 + eps) return null
            return t.coerceIn(0.0, 1.0)
        }

        /** Signed perpendicular distance of [p] from the infinite line q1→q2 (+ = left of direction). */
        fun signedOffset(p: Vec, q1: Vec, q2: Vec): Double {
            val d = q2 - q1
            val len = d.length()
            return if (len == 0.0) 0.0 else d.cross(p - q1) / len
        }
    }
}
