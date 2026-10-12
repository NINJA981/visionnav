package com.visionnav.core.geo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeoMathTest {

    private val chennaiCentral = GeoPoint(13.0827, 80.2707)

    @Test
    fun `distance of one degree latitude is about 111 km`() {
        val d = GeoMath.distanceM(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0))
        assertThat(d).isWithin(200.0).of(111_195.0)
    }

    @Test
    fun `bearing cardinal directions`() {
        val north = GeoMath.destination(chennaiCentral, 0.0, 100.0)
        val east = GeoMath.destination(chennaiCentral, 90.0, 100.0)
        val south = GeoMath.destination(chennaiCentral, 180.0, 100.0)
        val west = GeoMath.destination(chennaiCentral, 270.0, 100.0)
        assertThat(GeoMath.bearingDeg(chennaiCentral, north)).isWithin(0.1).of(0.0)
        assertThat(GeoMath.bearingDeg(chennaiCentral, east)).isWithin(0.1).of(90.0)
        assertThat(GeoMath.bearingDeg(chennaiCentral, south)).isWithin(0.1).of(180.0)
        assertThat(GeoMath.bearingDeg(chennaiCentral, west)).isWithin(0.1).of(270.0)
    }

    @Test
    fun `destination round trips distance`() {
        val p = GeoMath.destination(chennaiCentral, 37.0, 250.0)
        assertThat(GeoMath.distanceM(chennaiCentral, p)).isWithin(0.5).of(250.0)
    }

    @Test
    fun `signed angle difference wraps around north`() {
        assertThat(GeoMath.signedAngleDiff(350.0, 10.0)).isWithin(1e-9).of(20.0)
        assertThat(GeoMath.signedAngleDiff(10.0, 350.0)).isWithin(1e-9).of(-20.0)
        assertThat(GeoMath.absAngleDiff(90.0, 270.0)).isWithin(1e-9).of(180.0)
    }

    @Test
    fun `projection onto polyline finds nearest segment and along distance`() {
        val a = chennaiCentral
        val b = GeoMath.destination(a, 90.0, 100.0)
        val c = GeoMath.destination(b, 0.0, 100.0)
        val probe = GeoMath.destination(GeoMath.destination(b, 0.0, 50.0), 90.0, 5.0)
        val proj = GeoMath.projectOntoPolyline(probe, listOf(a, b, c))!!
        assertThat(proj.segmentIndex).isEqualTo(1)
        assertThat(proj.distanceM).isWithin(0.5).of(5.0)
        assertThat(proj.alongM).isWithin(1.0).of(150.0)
    }

    @Test
    fun `projection on empty line is null`() {
        assertThat(GeoMath.projectOntoPolyline(chennaiCentral, emptyList())).isNull()
    }

    @Test
    fun `clock hours`() {
        assertThat(GeoMath.clockHour(0.0)).isEqualTo(12)
        assertThat(GeoMath.clockHour(90.0)).isEqualTo(3)
        assertThat(GeoMath.clockHour(-90.0)).isEqualTo(9)
        assertThat(GeoMath.clockHour(60.0)).isEqualTo(2)
        assertThat(GeoMath.clockHour(180.0)).isEqualTo(6)
        assertThat(GeoMath.clockHour(-14.0)).isEqualTo(12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid latitude rejected`() {
        GeoPoint(91.0, 0.0)
    }
}
