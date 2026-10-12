package com.visionnav.mapdata.overpass

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.FakeTimeSource
import com.visionnav.core.crossing.MappedFeature
import com.visionnav.core.crossing.RoadWay
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

class OverpassTileCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private val p0 = GeoPoint(13.0604, 80.2496)

    private fun tileFor(point: GeoPoint, r: Double, fetchedAt: Long): OverpassTile {
        val key = OverpassTileGrid.keyFor(point, r)
        return OverpassTile(
            key = key,
            center = OverpassTileGrid.centerOf(key),
            radiusM = OverpassTileGrid.fetchRadiusM(key),
            ways = listOf(RoadWay(1, listOf(point, GeoMath.destination(point, 90.0, 50.0)), mapOf("highway" to "primary", "oneway" to "yes"))),
            features = listOf(MappedFeature(2, point, mapOf("highway" to "crossing", "crossing:island" to "yes"))),
            fetchedAtWallMs = fetchedAt,
        )
    }

    @Test
    fun `fetch circle of a tile always covers the requested circle`() {
        val rnd = Random(42)
        repeat(2_000) {
            val p = GeoPoint(-60 + rnd.nextDouble() * 120, -179 + rnd.nextDouble() * 358)
            val r = 30 + rnd.nextDouble() * 270
            val key = OverpassTileGrid.keyFor(p, r)
            val center = OverpassTileGrid.centerOf(key)
            assertThat(GeoMath.distanceM(center, p) + r).isAtMost(OverpassTileGrid.fetchRadiusM(key))
        }
    }

    @Test
    fun `nearby points share a tile and buckets round up`() {
        val a = OverpassTileGrid.keyFor(p0, 100.0)
        assertThat(a.bucketM).isEqualTo(120)
        assertThat(OverpassTileGrid.keyFor(p0, 30.0).bucketM).isEqualTo(60)
        assertThat(OverpassTileGrid.keyFor(p0, 300.0).bucketM).isEqualTo(300)
        assertThat(OverpassTileGrid.fetchRadiusM(a)).isEqualTo(310.0)
        val far = OverpassTileGrid.keyFor(GeoMath.destination(p0, 0.0, 600.0), 100.0)
        assertThat(far).isNotEqualTo(a)
    }

    @Test
    fun `hit within TTL, miss after expiry`() {
        val clock = FakeTimeSource(1_000_000)
        val cache = OverpassTileCache(clock, ttlMs = day)
        cache.put(tileFor(p0, 100.0, clock.wallMs()))
        assertThat(cache.findFresh(p0, 100.0)).isNotNull()
        clock.advance(day - 1)
        assertThat(cache.findFresh(p0, 100.0)).isNotNull()
        clock.advance(2)
        assertThat(cache.findFresh(p0, 100.0)).isNull()
        // Still available for stale-if-error lookups.
        assertThat(cache.find(p0, 100.0, maxAgeMs = 7 * day)).isNotNull()
    }

    @Test
    fun `covering tile is reused for a smaller nearby circle but not for a non-covered one`() {
        val clock = FakeTimeSource(0)
        val cache = OverpassTileCache(clock)
        val tile = tileFor(p0, 300.0, 0)
        cache.put(tile)
        assertThat(cache.findFresh(GeoMath.destination(p0, 45.0, 80.0), 60.0)).isEqualTo(tile)
        // Same point, but radius bigger than the fetched circle can cover.
        val farPoint = GeoMath.destination(tile.center, 0.0, tile.radiusM - 50.0)
        assertThat(cache.findFresh(farPoint, 100.0)).isNull()
    }

    @Test
    fun `memory LRU evicts the least recently used tile`() {
        val clock = FakeTimeSource(0)
        val cache = OverpassTileCache(clock, maxMemoryTiles = 2)
        val pts = (0..2).map { GeoMath.destination(p0, 0.0, it * 2_000.0) }
        cache.put(tileFor(pts[0], 100.0, 0))
        cache.put(tileFor(pts[1], 100.0, 0))
        assertThat(cache.findFresh(pts[0], 100.0)).isNotNull() // touch 0 → 1 is now eldest
        cache.put(tileFor(pts[2], 100.0, 0))
        assertThat(cache.findFresh(pts[1], 100.0)).isNull()
        assertThat(cache.findFresh(pts[0], 100.0)).isNotNull()
        assertThat(cache.findFresh(pts[2], 100.0)).isNotNull()
    }

    @Test
    fun `disk cache survives a new instance, preserves tags and leaves no temp files`() {
        val dir = tmp.newFolder("overpass")
        val clock = FakeTimeSource(5_000)
        val tile = tileFor(p0, 100.0, 5_000)
        OverpassTileCache(clock, cacheDir = dir).put(tile)

        val files = dir.listFiles()!!.map(File::getName)
        assertThat(files).containsExactly(tile.key.fileName)

        val reloaded = OverpassTileCache(clock, cacheDir = dir).findFresh(p0, 100.0)
        assertThat(reloaded).isEqualTo(tile)
        assertThat(reloaded!!.ways.single().tags).containsExactly("highway", "primary", "oneway", "yes")
        assertThat(reloaded.features.single().tags["crossing:island"]).isEqualTo("yes")
    }

    @Test
    fun `disk tile honours TTL and corrupt files are discarded`() {
        val dir = tmp.newFolder("overpass")
        val clock = FakeTimeSource(0)
        val tile = tileFor(p0, 100.0, 0)
        OverpassTileCache(clock, cacheDir = dir).put(tile)
        clock.advance(day + 1)
        assertThat(OverpassTileCache(clock, cacheDir = dir).findFresh(p0, 100.0)).isNull()

        File(dir, tile.key.fileName).writeText("{ not json")
        assertThat(OverpassTileCache(clock, cacheDir = dir).find(p0, 100.0, Long.MAX_VALUE)).isNull()
        assertThat(File(dir, tile.key.fileName).exists()).isFalse()
    }

    @Test
    fun `disk cache is pruned to the configured size`() {
        val dir = tmp.newFolder("overpass")
        val cache = OverpassTileCache(FakeTimeSource(0), cacheDir = dir, maxDiskTiles = 3)
        repeat(5) { i -> cache.put(tileFor(GeoMath.destination(p0, 90.0, i * 2_000.0), 100.0, 0)) }
        assertThat(dir.listFiles()!!.size).isAtMost(3)
    }
}
