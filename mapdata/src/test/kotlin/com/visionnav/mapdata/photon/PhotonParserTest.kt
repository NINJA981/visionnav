package com.visionnav.mapdata.photon

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.ServiceError
import com.visionnav.core.geo.GeoPoint
import com.visionnav.mapdata.Fixtures
import com.visionnav.mapdata.errorOrFail
import com.visionnav.mapdata.valueOrFail
import org.junit.Test

class PhotonParserTest {

    @Test
    fun `parses live Chennai Central search fixture`() {
        val near = GeoPoint(13.0827, 80.2707)
        val places = PhotonParser.parse(Fixtures.read("photon_search_chennai_central.json"), near).valueOrFail()
        assertThat(places).hasSize(8)
        assertThat(places.map { it.name }).contains("Puratchi Thalaivar Dr. M. G. Ramachandran Central")

        val rpf = places.single { it.name == "Chennai Central RPF" }
        assertThat(rpf.point).isEqualTo(GeoPoint(13.0830613, 80.2732996))
        assertThat(rpf.address).isEqualTo("Peoples Park Road, Periamet, Chennai")
        assertThat(rpf.category).isEqualTo("amenity=police")
        assertThat(rpf.distanceM!!).isWithin(30.0).of(280.0)

        // No street → address from locality + city only.
        val tower = places.single { it.name == "Chennai Central Tower" }
        assertThat(tower.address).isEqualTo("Ward 59, Chennai")
        assertThat(tower.category).isEqualTo("landuse=construction")
    }

    @Test
    fun `parses live reverse fixture`() {
        val point = GeoPoint(13.0827, 80.2707)
        val places = PhotonParser.parse(Fixtures.read("photon_reverse_chennai.json"), point).valueOrFail()
        val meta = places.single()
        assertThat(meta.name).isEqualTo("Meta Hospital")
        assertThat(meta.address).isEqualTo("Raja Muthiah Road, Periamet, Chennai")
        assertThat(meta.category).isEqualTo("amenity=hospital")
        assertThat(meta.distanceM!!).isLessThan(20.0)
    }

    private fun feature(lon: Double, lat: Double, props: String) =
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{$props}}"""

    @Test
    fun `name falls back to housenumber and street, address omits missing and duplicate parts`() {
        val json = """{"type":"FeatureCollection","features":[
            ${feature(80.25, 13.06, "\"housenumber\":\"12\",\"street\":\"Anna Salai\",\"district\":\"Teynampet\",\"city\":\"Chennai\"")},
            ${feature(80.25, 13.06, "\"street\":\"Anna Salai\",\"city\":\"Chennai\"")},
            ${feature(80.25, 13.06, "\"name\":\"Chennai\",\"city\":\"Chennai\",\"osm_key\":\"place\",\"osm_value\":\"city\"")},
            ${feature(80.25, 13.06, "\"name\":\"Bus stop\",\"osm_key\":\"highway\"")}
        ]}"""
        val places = PhotonParser.parse(json, near = null).valueOrFail()
        assertThat(places[0].name).isEqualTo("12 Anna Salai")
        assertThat(places[0].address).isEqualTo("Teynampet, Chennai")
        assertThat(places[0].distanceM).isNull()
        assertThat(places[1].name).isEqualTo("Anna Salai")
        assertThat(places[1].address).isEqualTo("Chennai")
        assertThat(places[2].name).isEqualTo("Chennai")
        assertThat(places[2].address).isNull()
        assertThat(places[2].category).isEqualTo("place=city")
        assertThat(places[3].category).isNull() // osm_value missing → no category invented
    }

    @Test
    fun `features without a name or coordinates are dropped`() {
        val json = """{"features":[
            {"geometry":{"coordinates":[80.25]},"properties":{"name":"Half"}},
            {"properties":{"name":"No geometry"}},
            ${feature(80.25, 13.06, "\"country\":\"India\"")},
            ${feature(80.25, 95.0, "\"name\":\"Bad lat\"")},
            ${feature(80.25, 13.06, "\"name\":\"Good\"")}
        ]}"""
        assertThat(PhotonParser.parse(json, null).valueOrFail().map { it.name }).containsExactly("Good")
    }

    @Test
    fun `malformed body is a parse error`() {
        assertThat(PhotonParser.parse("<html>", null).errorOrFail()).isInstanceOf(ServiceError.Parse::class.java)
    }
}
