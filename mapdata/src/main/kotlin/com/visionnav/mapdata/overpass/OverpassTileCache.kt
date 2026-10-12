package com.visionnav.mapdata.overpass

import com.visionnav.core.common.TimeSource
import com.visionnav.core.crossing.MappedFeature
import com.visionnav.core.crossing.RoadWay
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.mapdata.util.LruCache
import com.visionnav.mapdata.util.MapDataJson
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/** Identifies a cached Overpass fetch: a ~250 m grid cell plus a radius bucket. */
data class TileKey(val latIdx: Int, val lonIdx: Int, val bucketM: Int) {
    internal val fileName: String get() = "tile_${latIdx}_${lonIdx}_$bucketM.json"
}

/**
 * Fixed ~[TILE_SIZE_M] grid. A request (point, r) maps to the cell containing the point and the
 * smallest bucket ≥ r. The fetch is centred on the cell centre with radius
 * `bucket + half-diagonal + margin`, so it always covers the requested circle for any point in
 * the cell — and later requests anywhere in the cell (or nearby, see coverage) reuse it.
 */
object OverpassTileGrid {
    const val TILE_SIZE_M = 250.0
    private const val M_PER_DEG_LAT = 111_320.0
    private const val LAT_STEP = TILE_SIZE_M / M_PER_DEG_LAT
    private const val MARGIN_M = 10.0
    val RADIUS_BUCKETS_M: List<Int> = listOf(60, 120, 200, 300)
    private val HALF_DIAGONAL_M = TILE_SIZE_M * sqrt(2.0) / 2.0

    fun bucketFor(radiusM: Double): Int = RADIUS_BUCKETS_M.firstOrNull { it >= radiusM } ?: RADIUS_BUCKETS_M.last()

    fun keyFor(point: GeoPoint, radiusM: Double): TileKey {
        val latIdx = floor(point.lat / LAT_STEP).toInt()
        val lonIdx = floor(point.lon / lonStep(latIdx)).toInt()
        return TileKey(latIdx, lonIdx, bucketFor(radiusM))
    }

    fun centerOf(key: TileKey): GeoPoint {
        val lat = ((key.latIdx + 0.5) * LAT_STEP).coerceIn(-89.9, 89.9)
        val lon = ((key.lonIdx + 0.5) * lonStep(key.latIdx)).coerceIn(-180.0, 180.0)
        return GeoPoint(lat, lon)
    }

    /** Overpass `around` radius used to fill [key]; rounded up to 10 m. */
    fun fetchRadiusM(key: TileKey): Double = ceil((key.bucketM + HALF_DIAGONAL_M + MARGIN_M) / 10.0) * 10.0

    private fun lonStep(latIdx: Int): Double {
        val rowLat = ((latIdx + 0.5) * LAT_STEP).coerceIn(-89.0, 89.0)
        return TILE_SIZE_M / (M_PER_DEG_LAT * cos(rowLat * PI / 180.0))
    }
}

/** A cached Overpass result for a circle. Tags are verbatim. */
data class OverpassTile(
    val key: TileKey,
    val center: GeoPoint,
    val radiusM: Double,
    val ways: List<RoadWay>,
    val features: List<MappedFeature>,
    /** Wall-clock fetch time (persisted, so wall time rather than monotonic). */
    val fetchedAtWallMs: Long,
) {
    /** True if the circle (point, r) lies entirely inside this tile's fetched circle. */
    fun covers(point: GeoPoint, radiusM: Double): Boolean =
        GeoMath.distanceM(center, point) + radiusM <= this.radiusM
}

/**
 * Two-level cache for Overpass tiles: in-memory LRU + optional on-disk JSON directory.
 *
 * Lookups return any tile whose circle **covers** the requested circle and whose age is within the
 * requested limit. Disk files are written atomically (temp file + atomic rename) and corrupt files
 * are deleted. All methods are thread-safe; disk methods block, so call them off the main thread.
 */
class OverpassTileCache(
    private val time: TimeSource,
    private val ttlMs: Long = 24L * 60 * 60 * 1000,
    maxMemoryTiles: Int = 64,
    private val cacheDir: File? = null,
    private val maxDiskTiles: Int = 512,
) {
    private val memory = LruCache<TileKey, OverpassTile>(maxMemoryTiles)
    private val diskLock = Any()

    init {
        cacheDir?.mkdirs()
    }

    /** A tile covering (point, r) younger than the TTL, or null. */
    fun findFresh(point: GeoPoint, radiusM: Double): OverpassTile? = find(point, radiusM, ttlMs)

    /** A tile covering (point, r) younger than [maxAgeMs] (used for stale-if-error), or null. */
    fun find(point: GeoPoint, radiusM: Double, maxAgeMs: Long): OverpassTile? {
        val now = time.wallMs()
        fun usable(t: OverpassTile) = age(t, now) <= maxAgeMs && t.covers(point, radiusM)

        memory.values().filter(::usable).maxByOrNull { it.fetchedAtWallMs }?.let { hit ->
            memory[hit.key] // touch for LRU order
            return hit
        }
        if (cacheDir == null) return null
        val base = OverpassTileGrid.keyFor(point, radiusM)
        for (bucket in OverpassTileGrid.RADIUS_BUCKETS_M.filter { it >= base.bucketM }) {
            val key = base.copy(bucketM = bucket)
            if (memory[key] != null) continue // already considered above
            val tile = readDisk(key) ?: continue
            memory[key] = tile
            if (usable(tile)) return tile
        }
        return null
    }

    fun put(tile: OverpassTile) {
        memory[tile.key] = tile
        writeDisk(tile)
    }

    fun clearMemory() = memory.clear()

    private fun age(t: OverpassTile, now: Long): Long = (now - t.fetchedAtWallMs).coerceAtLeast(0)

    private fun readDisk(key: TileKey): OverpassTile? {
        val dir = cacheDir ?: return null
        val file = File(dir, key.fileName)
        synchronized(diskLock) {
            if (!file.isFile) return null
            return try {
                MapDataJson.decodeFromString(DiskTile.serializer(), file.readText()).toTile()
            } catch (_: Exception) {
                file.delete() // corrupt or old format
                null
            }
        }
    }

    private fun writeDisk(tile: OverpassTile) {
        val dir = cacheDir ?: return
        synchronized(diskLock) {
            var tmp: File? = null
            try {
                dir.mkdirs()
                tmp = File.createTempFile("tile", ".tmp", dir)
                tmp.writeText(MapDataJson.encodeToString(DiskTile.serializer(), DiskTile.from(tile)))
                val target = File(dir, tile.key.fileName).toPath()
                try {
                    Files.move(tmp.toPath(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
                }
                tmp = null
                prune(dir)
            } catch (_: IOException) {
                // Disk cache is best-effort; memory copy is still valid.
            } finally {
                tmp?.delete()
            }
        }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("tile_") && f.name.endsWith(".json") } ?: return
        if (files.size <= maxDiskTiles) return
        files.sortedBy { it.lastModified() }.take(files.size - maxDiskTiles).forEach { it.delete() }
    }
}

@Serializable
private data class DiskTile(
    val version: Int = FORMAT_VERSION,
    val latIdx: Int,
    val lonIdx: Int,
    val bucketM: Int,
    val centerLat: Double,
    val centerLon: Double,
    val radiusM: Double,
    val fetchedAtWallMs: Long,
    val ways: List<DiskWay> = emptyList(),
    val features: List<DiskFeature> = emptyList(),
) {
    fun toTile(): OverpassTile {
        require(version == FORMAT_VERSION) { "Unsupported tile format $version" }
        return OverpassTile(
            key = TileKey(latIdx, lonIdx, bucketM),
            center = GeoPoint(centerLat, centerLon),
            radiusM = radiusM,
            ways = ways.map { w ->
                require(w.lats.size == w.lons.size) { "Corrupt way ${w.id}" }
                RoadWay(w.id, w.lats.indices.map { GeoPoint(w.lats[it], w.lons[it]) }, w.tags)
            },
            features = features.map { MappedFeature(it.id, GeoPoint(it.lat, it.lon), it.tags) },
            fetchedAtWallMs = fetchedAtWallMs,
        )
    }

    companion object {
        const val FORMAT_VERSION = 1

        fun from(t: OverpassTile) = DiskTile(
            latIdx = t.key.latIdx,
            lonIdx = t.key.lonIdx,
            bucketM = t.key.bucketM,
            centerLat = t.center.lat,
            centerLon = t.center.lon,
            radiusM = t.radiusM,
            fetchedAtWallMs = t.fetchedAtWallMs,
            ways = t.ways.map { w -> DiskWay(w.osmWayId, w.geometry.map { it.lat }, w.geometry.map { it.lon }, w.tags) },
            features = t.features.map { DiskFeature(it.osmNodeId, it.point.lat, it.point.lon, it.tags) },
        )
    }
}

@Serializable
private data class DiskWay(val id: Long, val lats: List<Double>, val lons: List<Double>, val tags: Map<String, String>)

@Serializable
private data class DiskFeature(val id: Long, val lat: Double, val lon: Double, val tags: Map<String, String>)
