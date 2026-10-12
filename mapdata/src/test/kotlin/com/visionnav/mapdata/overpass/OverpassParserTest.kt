package com.visionnav.mapdata.overpass

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.ServiceError
import com.visionnav.core.geo.GeoPoint
import com.visionnav.mapdata.Fixtures
import com.visionnav.mapdata.errorOrFail
import com.visionnav.mapdata.valueOrFail
import org.junit.Test

class OverpassParserTest {
    private val data = OverpassParser.parse(Fixtures.read("overpass_chennai_100m_handwritten.json")).valueOrFail()
    private fun way(id: Long) = data.ways.single { it.osmWayId == id }
    private fun feature(id: Long) = data.features.single { it.osmNodeId == id }

    @Test
    fun `splits roads, nodes and steps`() {
        assertThat(data.ways.map { it.osmWayId })
            .containsExactly(900000001L, 900000002L, 900000003L, 900000004L, 900000005L).inOrder()
        assertThat(data.features.map { it.osmNodeId }).containsExactly(
            900000006L, 910000001L, 910000002L, 910000003L, 910000004L, 910000005L, 910000006L, 910000007L,
        ).inOrder()
        assertThat(data.remark).isNull()
        assertThat(data.isRuntimeError).isFalse()
    }

    @Test
    fun `way geometry comes from inline out-geom array`() {
        val main = way(900000001)
        assertThat(main.geometry).hasSize(5)
        assertThat(main.geometry.first()).isEqualTo(GeoPoint(13.0594, 80.2487))
        assertThat(main.geometry.last()).isEqualTo(GeoPoint(13.0614, 80.2505))
    }

    @Test
    fun `oneway tag preserved verbatim when present and absent when not mapped`() {
        assertThat(way(900000002).tags["oneway"]).isEqualTo("yes")
        assertThat(way(900000003).tags["oneway"]).isEqualTo("no")
        assertThat(way(900000001).tags).doesNotContainKey("oneway")
        // Roundabout: no implied oneway is added here — interpretation belongs to :core.
        assertThat(way(900000005).tags).containsExactly("highway", "tertiary", "junction", "roundabout")
    }

    @Test
    fun `all tags are copied exactly`() {
        assertThat(way(900000001).tags).containsExactly(
            "highway", "primary", "name", "Fixture Main Road", "lanes", "6",
            "maxspeed", "50", "surface", "asphalt", "sidewalk", "both",
        )
        assertThat(way(900000004).tags).containsExactly("highway", "service", "service", "parking_aisle")
    }

    @Test
    fun `crossing nodes keep crossing and island tags`() {
        val signals = feature(910000001)
        assertThat(signals.point).isEqualTo(GeoPoint(13.0603, 80.2495))
        assertThat(signals.tags).containsExactly(
            "highway", "crossing", "crossing", "traffic_signals", "crossing:island", "yes",
            "tactile_paving", "yes", "button_operated", "yes", "traffic_signals:sound", "yes",
        )
        assertThat(feature(910000002).tags["crossing:markings"]).isEqualTo("zebra")
        val uncontrolled = feature(910000003)
        assertThat(uncontrolled.tags["crossing"]).isEqualTo("uncontrolled")
        assertThat(uncontrolled.tags["crossing:island"]).isEqualTo("no")
        assertThat(uncontrolled.tags).doesNotContainKey("highway")
        // A bare highway=crossing: control is unknown and must stay unknown (no crossing=* invented).
        assertThat(feature(910000007).tags).containsExactly("highway", "crossing")
    }

    @Test
    fun `steps way becomes a feature at its first vertex`() {
        val steps = feature(900000006)
        assertThat(steps.point).isEqualTo(GeoPoint(13.06065, 80.2499))
        assertThat(steps.tags).containsExactly("highway", "steps", "step_count", "12", "handrail", "yes", "incline", "up")
    }

    @Test
    fun `null geometry vertices, empty ways, unknown types and duplicates are skipped`() {
        val json = """
            {"elements":[
              {"type":"way","id":1,"geometry":[null,{"lat":13.0,"lon":80.0},{"lat":13.001,"lon":80.0}],"tags":{"highway":"residential"}},
              {"type":"way","id":2,"tags":{"highway":"residential"}},
              {"type":"relation","id":3,"tags":{"type":"route"}},
              {"type":"node","id":4,"lat":13.0,"lon":80.0},
              {"type":"node","id":4,"lat":13.0,"lon":80.0},
              {"type":"node","id":5}
            ]}
        """.trimIndent()
        val parsed = OverpassParser.parse(json).valueOrFail()
        assertThat(parsed.ways.map { it.osmWayId }).containsExactly(1L)
        assertThat(parsed.ways.single().geometry).hasSize(2)
        assertThat(parsed.features.map { it.osmNodeId }).containsExactly(4L)
        assertThat(parsed.features.single().tags).isEmpty()
    }

    @Test
    fun `runtime error remark is detected`() {
        val parsed = OverpassParser.parse(
            """{"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 1 after 16 seconds."}""",
        ).valueOrFail()
        assertThat(parsed.isRuntimeError).isTrue()
    }

    @Test
    fun `malformed body is a parse error`() {
        assertThat(OverpassParser.parse(Fixtures.read("overpass_busy_504.html")).errorOrFail())
            .isInstanceOf(ServiceError.Parse::class.java)
    }

    @Test
    fun `query contains every requested selector with out geom`() {
        val q = OverpassQuery.build(GeoPoint(13.0604, 80.2496), 307.0)
        assertThat(q).startsWith("[out:json][timeout:15];(")
        assertThat(q).endsWith(");out geom tags;")
        val around = "around:307,13.0604000,80.2496000"
        listOf(
            "way($around)[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|service|" +
                "living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|road)$\"];",
            "node($around)[highway=crossing];",
            "node($around)[crossing];",
            "node($around)[highway=traffic_signals];",
            "node($around)[entrance];",
            "way($around)[highway=steps];",
            "node($around)[kerb];",
        ).forEach { assertThat(q).contains(it) }
    }
}
