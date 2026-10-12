package com.visionnav.mapdata.valhalla

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.ServiceError
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.navigation.ManeuverType
import com.visionnav.mapdata.Fixtures
import com.visionnav.mapdata.errorOrFail
import com.visionnav.mapdata.polyline.Polyline6
import com.visionnav.mapdata.valueOrFail
import org.junit.Test

class ValhallaRouteParserTest {

    @Test
    fun `parses live Chennai fixture`() {
        val route = ValhallaRouteParser.parse(Fixtures.read("valhalla_route_chennai.json")).valueOrFail()

        assertThat(route.geometry).hasSize(56)
        assertThat(route.lengthM).isWithin(1e-6).of(1122.0)
        assertThat(route.durationS).isWithin(1e-6).of(909.835)
        assertThat(route.maneuvers.map { it.type }).containsExactly(
            ManeuverType.START, ManeuverType.LEFT, ManeuverType.CONTINUE, ManeuverType.LEFT, ManeuverType.ARRIVE_LEFT,
        ).inOrder()
        assertThat(route.maneuvers.map { it.beginShapeIndex }).containsExactly(0, 4, 6, 48, 55).inOrder()

        val start = route.maneuvers[0]
        assertThat(start.streetNames).containsExactly("Raja Muthiah Road")
        assertThat(start.lengthM).isWithin(1e-9).of(171.0)
        assertThat(start.bearingBeforeDeg).isNull()
        assertThat(start.bearingAfterDeg).isEqualTo(173.0)
        assertThat(start.engineInstruction).isEqualTo("Walk south on Raja Muthiah Road.")

        val walkway = route.maneuvers[1]
        assertThat(walkway.streetNames).isEmpty()
        assertThat(walkway.bearingBeforeDeg).isEqualTo(173.0)
        assertThat(walkway.bearingAfterDeg).isEqualTo(81.0)
        assertThat(route.maneuvers[2].streetNames)
            .containsExactly("General Hospital Road", "SHU86", "Grand Western Trunk Road").inOrder()
        // This live route has no crosswalk maneuvers; the engine does not flag plain road crossings.
        assertThat(route.maneuvers.none { it.isCrossing }).isTrue()
    }

    @Test
    fun `maneuver type table matches Valhalla API reference`() {
        val expected = mapOf(
            0 to ManeuverType.OTHER, 1 to ManeuverType.START, 2 to ManeuverType.START, 3 to ManeuverType.START,
            4 to ManeuverType.ARRIVE, 5 to ManeuverType.ARRIVE_RIGHT, 6 to ManeuverType.ARRIVE_LEFT,
            7 to ManeuverType.CONTINUE, 8 to ManeuverType.CONTINUE, 9 to ManeuverType.SLIGHT_RIGHT,
            10 to ManeuverType.RIGHT, 11 to ManeuverType.SHARP_RIGHT, 12 to ManeuverType.U_TURN,
            13 to ManeuverType.U_TURN, 14 to ManeuverType.SHARP_LEFT, 15 to ManeuverType.LEFT,
            16 to ManeuverType.SLIGHT_LEFT, 17 to ManeuverType.CONTINUE, 18 to ManeuverType.SLIGHT_RIGHT,
            19 to ManeuverType.SLIGHT_LEFT, 20 to ManeuverType.SLIGHT_RIGHT, 21 to ManeuverType.SLIGHT_LEFT,
            22 to ManeuverType.CONTINUE, 23 to ManeuverType.SLIGHT_RIGHT, 24 to ManeuverType.SLIGHT_LEFT,
            25 to ManeuverType.CONTINUE, 26 to ManeuverType.ENTER_ROUNDABOUT, 27 to ManeuverType.EXIT_ROUNDABOUT,
            28 to ManeuverType.OTHER, 29 to ManeuverType.OTHER, 30 to ManeuverType.OTHER, 36 to ManeuverType.OTHER,
            37 to ManeuverType.SLIGHT_RIGHT, 38 to ManeuverType.SLIGHT_LEFT, 39 to ManeuverType.ELEVATOR,
            40 to ManeuverType.STAIRS, 41 to ManeuverType.ESCALATOR, 42 to ManeuverType.ENTER_BUILDING,
            43 to ManeuverType.EXIT_BUILDING, 99 to ManeuverType.OTHER,
        )
        expected.forEach { (code, type) ->
            assertThat(ValhallaManeuverTypes.toManeuverType(code)).isEqualTo(type)
        }
    }

    private fun routeJson(maneuvers: String, shape: String, units: String = "kilometers", summaryLength: Double = 0.1) = """
        {"trip":{"locations":[],"legs":[{"maneuvers":[$maneuvers],
         "summary":{"length":$summaryLength,"time":80.0},"shape":"$shape"}],
         "summary":{"length":$summaryLength,"time":80.0},"status_message":"Found route between points","status":0,
         "units":"$units","language":"en-US"}}
    """.trimIndent()

    private val shape3 = Polyline6.encode(
        listOf(GeoPoint(13.0600, 80.2490), GeoPoint(13.0601, 80.2491), GeoPoint(13.0602, 80.2492)),
    ).replace("\\", "\\\\")

    @Test
    fun `crosswalk maneuvers are flagged from the en-US narrative`() {
        val json = routeJson(
            """
            {"type":1,"instruction":"Walk north on the walkway.","length":0.01,"begin_shape_index":0,"end_shape_index":1},
            {"type":10,"instruction":"Turn right onto the crosswalk.","length":0.02,"begin_shape_index":1,"end_shape_index":2,"bearing_before":0,"bearing_after":90},
            {"type":8,"instruction":"Continue on the crosswalk.","length":0.01,"begin_shape_index":1,"end_shape_index":2},
            {"type":8,"instruction":"Continue on The Crosswalk Road.","street_names":["The Crosswalk Road"],"length":0.01,"begin_shape_index":1},
            {"type":4,"instruction":"You have arrived at your destination.","length":0.0,"begin_shape_index":2}
            """.trimIndent(),
            shape3,
        )
        val route = ValhallaRouteParser.parse(json).valueOrFail()
        assertThat(route.maneuvers.map { it.isCrossing }).containsExactly(false, true, true, false, false).inOrder()
        assertThat(route.maneuvers[1].type).isEqualTo(ManeuverType.RIGHT) // turn direction preserved
        assertThat(route.maneuvers[2].type).isEqualTo(ManeuverType.CROSS_ROAD)
        assertThat(route.maneuvers[3].type).isEqualTo(ManeuverType.CONTINUE)
    }

    @Test
    fun `miles are converted to metres`() {
        val json = routeJson(
            """{"type":1,"instruction":"Walk.","length":1.0,"begin_shape_index":0}""",
            shape3,
            units = "miles",
            summaryLength = 1.0,
        )
        val route = ValhallaRouteParser.parse(json).valueOrFail()
        assertThat(route.lengthM).isWithin(1e-6).of(1609.344)
        assertThat(route.maneuvers[0].lengthM).isWithin(1e-6).of(1609.344)
    }

    @Test
    fun `multiple legs are stitched without duplicate joint and with shifted indices`() {
        val leg1 = Polyline6.encode(listOf(GeoPoint(13.0, 80.0), GeoPoint(13.001, 80.0))).replace("\\", "\\\\")
        val leg2 = Polyline6.encode(listOf(GeoPoint(13.001, 80.0), GeoPoint(13.002, 80.0), GeoPoint(13.003, 80.0)))
            .replace("\\", "\\\\")
        val json = """
            {"trip":{"units":"kilometers","summary":{"length":0.333,"time":240},"legs":[
              {"shape":"$leg1","maneuvers":[{"type":1,"begin_shape_index":0},{"type":4,"begin_shape_index":1}]},
              {"shape":"$leg2","maneuvers":[{"type":1,"begin_shape_index":0},{"type":4,"begin_shape_index":2}]}
            ]}}
        """.trimIndent()
        val route = ValhallaRouteParser.parse(json).valueOrFail()
        assertThat(route.geometry).hasSize(4)
        assertThat(route.maneuvers.map { it.beginShapeIndex }).containsExactly(0, 1, 1, 3).inOrder()
        assertThat(route.lengthM).isWithin(1e-6).of(333.0)
    }

    @Test
    fun `no path error body maps to NotFound`() {
        assertThat(ValhallaRouteParser.parse(Fixtures.read("valhalla_error_442_handwritten.json")).errorOrFail())
            .isEqualTo(ServiceError.NotFound)
    }

    @Test
    fun `missing trip or shape is a parse error`() {
        assertThat(ValhallaRouteParser.parse("{}").errorOrFail()).isInstanceOf(ServiceError.Parse::class.java)
        assertThat(ValhallaRouteParser.parse("""{"trip":{"legs":[{"maneuvers":[]}]}}""").errorOrFail())
            .isInstanceOf(ServiceError.Parse::class.java)
        assertThat(ValhallaRouteParser.parse("""{"trip":{"legs":[{"shape":"_p~iF"}]}}""").errorOrFail())
            .isInstanceOf(ServiceError.Parse::class.java)
        assertThat(ValhallaRouteParser.parse("not json").errorOrFail()).isInstanceOf(ServiceError.Parse::class.java)
    }

    @Test
    fun `crosswalk detection requires an unnamed maneuver`() {
        assertThat(ValhallaRouteParser.isCrosswalkManeuver(emptyList(), "Turn left onto the crosswalk.")).isTrue()
        assertThat(ValhallaRouteParser.isCrosswalkManeuver(listOf("Mount Road"), "Turn left onto the crosswalk.")).isFalse()
        assertThat(ValhallaRouteParser.isCrosswalkManeuver(emptyList(), "Turn left onto the walkway.")).isFalse()
    }
}
