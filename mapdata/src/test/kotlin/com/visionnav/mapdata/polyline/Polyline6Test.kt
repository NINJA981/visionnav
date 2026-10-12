package com.visionnav.mapdata.polyline

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.geo.GeoPoint
import com.visionnav.mapdata.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

class Polyline6Test {

    private fun assertPoint(p: GeoPoint, lat: Double, lon: Double, tol: Double = 1e-9) {
        assertThat(p.lat).isWithin(tol).of(lat)
        assertThat(p.lon).isWithin(tol).of(lon)
    }

    @Test
    fun `Google reference vector at precision 5`() {
        val pts = Polyline6.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", precision = 5)
        assertThat(pts).hasSize(3)
        assertPoint(pts[0], 38.5, -120.2)
        assertPoint(pts[1], 40.7, -120.95)
        assertPoint(pts[2], 43.252, -126.453)
    }

    @Test
    fun `same string at precision 6 scales by ten`() {
        val pts = Polyline6.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertPoint(pts[0], 3.85, -12.02)
        assertPoint(pts[1], 4.07, -12.095)
        assertPoint(pts[2], 4.3252, -12.6453)
    }

    @Test
    fun `round trip Chennai points at precision 6`() {
        val pts = listOf(GeoPoint(13.082697, 80.270678), GeoPoint(13.081172, 80.270882), GeoPoint(-33.868820, 151.209296))
        val decoded = Polyline6.decode(Polyline6.encode(pts))
        decoded.zip(pts).forEach { (d, p) -> assertPoint(d, p.lat, p.lon, 1e-6) }
    }

    @Test
    fun `decodes live Valhalla fixture shape`() {
        val root = Json.parseToJsonElement(Fixtures.read("valhalla_route_chennai.json")).jsonObject
        val shape = root["trip"]!!.jsonObject["legs"]!!.jsonArray[0].jsonObject["shape"]!!.jsonPrimitive.content
        val pts = Polyline6.decode(shape)
        assertThat(pts).hasSize(56)
        assertPoint(pts.first(), 13.082697, 80.270678)
        assertPoint(pts.last(), 13.085105, 80.27587)
    }

    @Test
    fun `empty string decodes to empty list`() {
        assertThat(Polyline6.decode("")).isEmpty()
    }

    @Test
    fun `truncated input throws`() {
        assertThrows(IllegalArgumentException::class.java) { Polyline6.decode("_p~iF~ps|U_") }
        assertThrows(IllegalArgumentException::class.java) { Polyline6.decode("_p~iF") }
    }
}
