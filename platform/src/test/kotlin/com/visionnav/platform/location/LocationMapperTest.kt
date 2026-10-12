package com.visionnav.platform.location

import android.location.Location
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationMapperTest {

    private fun location(block: Location.() -> Unit = {}) = Location("fused").apply {
        latitude = 13.0827
        longitude = 80.2707
        accuracy = 7.5f
        elapsedRealtimeNanos = 123_456_000_000L
        block()
    }

    @Test
    fun `maps all fields when present`() {
        val fix = LocationMapper.toFix(
            location {
                speed = 1.4f
                bearing = 271f
                bearingAccuracyDegrees = 12f
            },
            fallbackElapsedMs = 0L,
        )!!
        assertThat(fix.point.lat).isWithin(1e-9).of(13.0827)
        assertThat(fix.point.lon).isWithin(1e-9).of(80.2707)
        assertThat(fix.accuracyM).isEqualTo(7.5f)
        assertThat(fix.speedMps).isEqualTo(1.4f)
        assertThat(fix.courseDeg).isEqualTo(271f)
        assertThat(fix.courseAccuracyDeg).isEqualTo(12f)
        assertThat(fix.elapsedMs).isEqualTo(123_456L)
    }

    @Test
    fun `missing speed and bearing map to null, not zero`() {
        val fix = LocationMapper.toFix(location(), fallbackElapsedMs = 0L)!!
        assertThat(fix.speedMps).isNull()
        assertThat(fix.courseDeg).isNull()
        assertThat(fix.courseAccuracyDeg).isNull()
    }

    @Test
    fun `bearing without bearing accuracy keeps course with null accuracy`() {
        val fix = LocationMapper.toFix(location { bearing = 10f }, fallbackElapsedMs = 0L)!!
        assertThat(fix.courseDeg).isEqualTo(10f)
        assertThat(fix.courseAccuracyDeg).isNull()
    }

    @Test
    fun `location without accuracy is rejected`() {
        val loc = location()
        loc.removeAccuracy()
        assertThat(LocationMapper.toFix(loc, fallbackElapsedMs = 0L)).isNull()
    }

    @Test
    fun `missing elapsed realtime uses fallback clock`() {
        val fix = LocationMapper.toFix(location { elapsedRealtimeNanos = 0L }, fallbackElapsedMs = 42L)!!
        assertThat(fix.elapsedMs).isEqualTo(42L)
    }
}
