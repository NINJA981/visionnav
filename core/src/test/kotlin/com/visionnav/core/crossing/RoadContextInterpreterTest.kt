package com.visionnav.core.crossing

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.DataSource
import com.visionnav.core.common.Reliability
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.crossing.CrossingCandidate.MappedCrossing
import com.visionnav.core.crossing.CrossingCandidate.PossibleCrossing
import com.visionnav.core.geo.GeoMath
import org.junit.Test

class RoadContextInterpreterTest {

    private val interpreter = RoadContextInterpreter()

    private fun interpretWay(tags: Map<String, String>): RoadSegmentContext =
        interpreter.interpret(Fx.metadata(listOf(Fx.eastWestWay(tags = tags))), Fx.fix(), null, 0).roads.single()

    // ---- Brief 1: one-way metadata handled

    @Test
    fun brief01_onewayYes1True_isOneWayAlongNodeOrder() {
        for (v in listOf("yes", "1", "true", "YES")) {
            val road = interpretWay(mapOf("highway" to "residential", "oneway" to v))
            assertThat(road.directionality).isEqualTo(RoadDirectionality.ONE_WAY)
            assertThat(road.expectedFlows).hasSize(1)
            assertThat(road.expectedFlows[0].bearingDeg).isWithin(1.0).of(90.0)
            assertThat(road.expectedFlows[0].source).isEqualTo(DataSource.OSM_MAP)
            assertThat(road.expectedFlows[0].reliability).isEqualTo(Reliability.MAPPED)
            assertThat(road.reliability).isEqualTo(Reliability.MAPPED)
        }
    }

    @Test
    fun brief01_onewayMinus1AndReverse_isOneWayReversed() {
        for (v in listOf("-1", "reverse")) {
            val road = interpretWay(mapOf("highway" to "residential", "oneway" to v))
            assertThat(road.directionality).isEqualTo(RoadDirectionality.ONE_WAY)
            assertThat(road.expectedFlows.single().bearingDeg).isWithin(1.0).of(270.0)
        }
    }

    @Test
    fun brief01_roundaboutWithoutOneway_isInferredOneWay() {
        val road = interpretWay(mapOf("highway" to "primary", "junction" to "roundabout"))
        assertThat(road.directionality).isEqualTo(RoadDirectionality.ONE_WAY)
        assertThat(road.reliability).isEqualTo(Reliability.INFERRED)
        assertThat(road.expectedFlows.single().reliability).isEqualTo(Reliability.INFERRED)

        val circular = interpretWay(mapOf("highway" to "primary", "junction" to "circular"))
        assertThat(circular.reliability).isEqualTo(Reliability.INFERRED)

        // An explicit tag wins over the convention.
        val explicit = interpretWay(mapOf("highway" to "primary", "junction" to "roundabout", "oneway" to "no"))
        assertThat(explicit.directionality).isEqualTo(RoadDirectionality.TWO_WAY)
    }

    @Test
    fun brief01_modeSpecificOnewayTags_doNotMakeRoadOneWayForCars() {
        val road = interpretWay(mapOf("highway" to "residential", "oneway:bus" to "yes", "oneway:bicycle" to "no"))
        assertThat(road.directionality).isEqualTo(RoadDirectionality.UNKNOWN)
        assertThat(road.expectedFlows).isEmpty()
    }

    // ---- Brief 2: two-way preserves both flows

    @Test
    fun brief02_twoWay_preservesBothOppositeFlows() {
        val road = interpretWay(mapOf("highway" to "secondary", "oneway" to "no", "lanes" to "2", "maxspeed" to "30 mph", "name" to "Anna Salai"))
        assertThat(road.directionality).isEqualTo(RoadDirectionality.TWO_WAY)
        assertThat(road.expectedFlows).hasSize(2)
        val (a, b) = road.expectedFlows
        assertThat(GeoMath.absAngleDiff(a.bearingDeg, b.bearingDeg)).isWithin(1e-6).of(180.0)
        assertThat(road.expectedFlows.map { it.reliability }).containsExactly(Reliability.MAPPED, Reliability.MAPPED)
        assertThat(road.lanes).isEqualTo(2)
        assertThat(road.maxSpeedKph).isEqualTo(48)
        assertThat(road.name).isEqualTo("Anna Salai")
        assertThat(road.distanceM!!).isWithin(0.5).of(10.0)
    }

    // ---- Brief 3: missing direction → UNKNOWN

    @Test
    fun brief03_missingOnewayTag_isUnknownWithZeroFlows_notTwoWay() {
        val ctx = interpreter.interpret(Fx.metadata(listOf(Fx.eastWestWay())), Fx.fix(), null, 0)
        val road = ctx.roads.single()
        assertThat(road.directionality).isEqualTo(RoadDirectionality.UNKNOWN)
        assertThat(road.expectedFlows).isEmpty()
        assertThat(ctx.uncertainties).contains(UncertaintyReason.ROAD_DIRECTION_UNAVAILABLE)
    }

    @Test
    fun brief03_alternatingAndReversible_areUnknown() {
        for (v in listOf("alternating", "reversible", "maybe")) {
            assertThat(interpretWay(mapOf("highway" to "residential", "oneway" to v)).directionality)
                .isEqualTo(RoadDirectionality.UNKNOWN)
        }
    }

    // ---- Brief 4: missing GPS

    @Test
    fun brief04_missingGps_nullLocation_noFabricatedCoordinates() {
        val meta = Fx.metadata(
            ways = listOf(Fx.eastWestWay(tags = mapOf("highway" to "residential", "oneway" to "yes"))),
            features = listOf(Fx.feature(7, Fx.at(10.0, 0.0), mapOf("highway" to "crossing"))),
        )
        val route = Fx.northRoute(listOf(Fx.crossingManeuver(2)))
        val ctx = interpreter.interpret(meta, location = null, route = route, nowMs = 42)

        assertThat(ctx.location).isNull()
        assertThat(ctx.locationAccuracyM).isNull()
        assertThat(ctx.uncertainties).contains(UncertaintyReason.GPS_UNAVAILABLE)
        assertThat(ctx.userPosition).isEqualTo(UserPositionAssessment.UNKNOWN)
        assertThat(ctx.roads.map { it.distanceM }).containsExactly(null)
        assertThat(ctx.crossings.filterIsInstance<MappedCrossing>().map { it.distanceM }).containsExactly(null)
        // "Ahead along the route" needs a position, so nothing is inferred.
        assertThat(ctx.crossings.filterIsInstance<PossibleCrossing>()).isEmpty()
        assertThat(ctx.elapsedMs).isEqualTo(42)
    }

    @Test
    fun brief04_lowAccuracyGps_flagsAndWithholdsPosition() {
        val ctx = interpreter.interpret(
            Fx.metadata(features = listOf(Fx.feature(7, Fx.at(5.0, 0.0), mapOf("highway" to "crossing")))),
            Fx.fix(accuracyM = 40f), null, 0,
        )
        assertThat(ctx.uncertainties).contains(UncertaintyReason.GPS_LOW_ACCURACY)
        assertThat(ctx.userPosition).isEqualTo(UserPositionAssessment.UNKNOWN)
        assertThat(ctx.locationAccuracyM).isEqualTo(40f)
    }

    @Test
    fun missingMetadata_reportsMapDataUnavailable() {
        val ctx = interpreter.interpret(null, Fx.fix(), null, 0)
        assertThat(ctx.roads).isEmpty()
        assertThat(ctx.uncertainties).containsAtLeast(UncertaintyReason.MAP_DATA_UNAVAILABLE, UncertaintyReason.ROAD_DIRECTION_UNAVAILABLE)
        assertThat(ctx.userPosition).isEqualTo(UserPositionAssessment.UNKNOWN)
        assertThat(ctx.location).isEqualTo(Fx.USER)
    }

    // ---- Brief 5: crossing uncertainty preserved

    @Test
    fun brief05_mappedAndPossibleCrossingsStayDistinct_andGpsNeverConfirms() {
        val mappedRoad = RoadWay(2, listOf(Fx.at(-60.0, 8.0), Fx.at(60.0, 8.0)), mapOf("highway" to "residential")) // N-S road 8 m east
        val crossedRoad = Fx.eastWestWay(id = 1, northM = 20.0)
        val meta = Fx.metadata(
            ways = listOf(mappedRoad, crossedRoad),
            features = listOf(Fx.feature(9, Fx.at(0.0, 8.0), mapOf("highway" to "crossing", "crossing" to "traffic_signals"))),
        )
        val ctx = interpreter.interpret(meta, Fx.fix(), Fx.northRoute(), 0)

        val mapped = ctx.crossings.filterIsInstance<MappedCrossing>().single()
        val possible = ctx.crossings.filterIsInstance<PossibleCrossing>().single()
        assertThat(mapped.reliability).isEqualTo(Reliability.MAPPED)
        assertThat(mapped.control).isEqualTo(CrossingControl.TRAFFIC_SIGNALS)
        assertThat(possible.reliability).isEqualTo(Reliability.INFERRED)
        assertThat(possible.distanceM!!).isWithin(1.0).of(20.0)
        assertThat(ctx.crossings.none { it is CrossingCandidate.VisualCrosswalk }).isTrue()
        assertThat(ctx.userPosition).isEqualTo(UserPositionAssessment.NEAR_UNCONFIRMED)
        assertThat(ctx.userPosition).isNotEqualTo(UserPositionAssessment.USER_CONFIRMED)
    }

    @Test
    fun brief05_userPositionNotNearWhenNoCrossingClose() {
        val ctx = interpreter.interpret(Fx.metadata(listOf(Fx.eastWestWay(northM = 50.0))), Fx.fix(), null, 0)
        assertThat(ctx.userPosition).isEqualTo(UserPositionAssessment.NOT_NEAR)
    }

    // ---- Mapped crossing tags

    @Test
    fun mappedCrossingTags_controlRefugeTactileAudible() {
        fun crossing(tags: Map<String, String>): MappedCrossing? =
            interpreter.interpret(Fx.metadata(features = listOf(Fx.feature(1, Fx.at(10.0, 0.0), tags))), Fx.fix(), null, 0)
                .crossings.filterIsInstance<MappedCrossing>().singleOrNull()

        assertThat(crossing(mapOf("highway" to "crossing", "crossing" to "zebra"))!!.control).isEqualTo(CrossingControl.MARKED_ZEBRA)
        assertThat(crossing(mapOf("highway" to "crossing", "crossing" to "marked"))!!.control).isEqualTo(CrossingControl.MARKED_ZEBRA)
        assertThat(crossing(mapOf("highway" to "crossing", "crossing" to "uncontrolled", "crossing_ref" to "zebra"))!!.control)
            .isEqualTo(CrossingControl.MARKED_ZEBRA)
        assertThat(crossing(mapOf("highway" to "crossing", "crossing" to "uncontrolled"))!!.control).isEqualTo(CrossingControl.UNCONTROLLED)
        assertThat(crossing(mapOf("highway" to "crossing", "crossing" to "unmarked"))!!.control).isEqualTo(CrossingControl.UNMARKED)
        assertThat(crossing(mapOf("highway" to "traffic_signals", "crossing" to "yes"))!!.control).isEqualTo(CrossingControl.TRAFFIC_SIGNALS)
        assertThat(crossing(mapOf("highway" to "crossing"))!!.control).isEqualTo(CrossingControl.UNKNOWN)
        assertThat(crossing(mapOf("highway" to "crossing", "crossing" to "no"))).isNull()
        assertThat(crossing(mapOf("highway" to "traffic_signals"))).isNull()

        val full = crossing(
            mapOf("highway" to "crossing", "crossing:island" to "yes", "tactile_paving" to "yes", "traffic_signals:sound" to "yes"),
        )!!
        assertThat(full.refuge).isEqualTo(RefugeStatus.MAPPED)
        assertThat(full.hasTactilePaving).isTrue()
        assertThat(full.hasAudibleSignal).isTrue()
        assertThat(crossing(mapOf("highway" to "crossing", "crossing:island" to "no"))!!.refuge).isEqualTo(RefugeStatus.NOT_MAPPED)
        val bare = crossing(mapOf("highway" to "crossing"))!!
        assertThat(bare.refuge).isEqualTo(RefugeStatus.UNKNOWN)
        assertThat(bare.hasTactilePaving).isNull()
        assertThat(bare.hasAudibleSignal).isNull()
    }

    // ---- Road filter & parsing

    @Test
    fun vehicleRoadFilter() {
        val f = RoadContextInterpreter::isVehicleRoad
        assertThat(f(mapOf("highway" to "residential"))).isTrue()
        assertThat(f(mapOf("highway" to "primary_link"))).isTrue()
        assertThat(f(mapOf("highway" to "living_street"))).isTrue()
        assertThat(f(mapOf("highway" to "service"))).isTrue()
        assertThat(f(mapOf("highway" to "footway"))).isFalse()
        assertThat(f(mapOf("highway" to "cycleway"))).isFalse()
        assertThat(f(mapOf("highway" to "steps"))).isFalse()
        assertThat(f(mapOf("highway" to "pedestrian"))).isFalse()
        assertThat(f(mapOf("highway" to "pedestrian", "motor_vehicle" to "destination"))).isTrue()
        assertThat(f(mapOf("highway" to "track", "motor_vehicle" to "yes"))).isTrue()
        assertThat(f(mapOf("building" to "yes"))).isFalse()
    }

    @Test
    fun maxSpeedParsing() {
        val p = RoadContextInterpreter::parseMaxSpeedKph
        assertThat(p("50")).isEqualTo(50)
        assertThat(p("50 km/h")).isEqualTo(50)
        assertThat(p("30 mph")).isEqualTo(48)
        assertThat(p("walk")).isNull()
        assertThat(p("none")).isNull()
        assertThat(p("RU:urban")).isNull()
        assertThat(p(null)).isNull()
    }

    // ---- Possible crossings

    @Test
    fun possibleCrossing_routeCrossingUnmappedRoadAhead() {
        val ctx = interpreter.interpret(Fx.metadata(listOf(Fx.eastWestWay(northM = 20.0, tags = mapOf("highway" to "residential", "name" to "Elm St")))), Fx.fix(), Fx.northRoute(), 0)
        val p = ctx.crossings.filterIsInstance<PossibleCrossing>().single()
        assertThat(p.roadName).isEqualTo("Elm St")
        assertThat(GeoMath.distanceM(p.point!!, Fx.at(20.0, 0.0))).isLessThan(1.0)
    }

    @Test
    fun possibleCrossing_suppressedByNearbyMappedCrossing_andIgnoredBeyondLookAhead() {
        val suppressed = interpreter.interpret(
            Fx.metadata(
                listOf(Fx.eastWestWay(northM = 20.0)),
                listOf(Fx.feature(3, Fx.at(20.0, 5.0), mapOf("highway" to "crossing"))),
            ),
            Fx.fix(), Fx.northRoute(), 0,
        )
        assertThat(suppressed.crossings.filterIsInstance<PossibleCrossing>()).isEmpty()
        assertThat(suppressed.crossings.filterIsInstance<MappedCrossing>()).hasSize(1)

        val far = interpreter.interpret(Fx.metadata(listOf(Fx.eastWestWay(northM = 55.0))), Fx.fix(), Fx.northRoute(), 0)
        assertThat(far.crossings).isEmpty()
    }

    @Test
    fun possibleCrossing_notReportedWhenWalkingAlongOrJoiningRoad() {
        // Route runs parallel to the road (sidewalk 3 m off): never crosses.
        val parallelRoute = com.visionnav.core.navigation.Route(
            listOf(Fx.at(0.0, 0.0), Fx.at(0.0, 30.0), Fx.at(0.0, 60.0)), emptyList(), 60.0, 50.0,
        )
        val parallel = interpreter.interpret(Fx.metadata(listOf(Fx.eastWestWay(northM = 3.0))), Fx.fix(), parallelRoute, 0)
        assertThat(parallel.crossings).isEmpty()

        // Route walks up to the road and then along it: touches, does not cross.
        val joinRoute = com.visionnav.core.navigation.Route(
            listOf(Fx.at(0.0, 0.0), Fx.at(10.0, 0.0), Fx.at(10.0, 30.0)), emptyList(), 40.0, 30.0,
        )
        val join = interpreter.interpret(Fx.metadata(listOf(Fx.eastWestWay(northM = 10.0))), Fx.fix(), joinRoute, 0)
        assertThat(join.crossings).isEmpty()
    }

    @Test
    fun possibleCrossing_fromRouteManeuver() {
        val ctx = interpreter.interpret(Fx.metadata(), Fx.fix(), Fx.northRoute(listOf(Fx.crossingManeuver(2, "Mount Road"))), 0)
        val p = ctx.crossings.filterIsInstance<PossibleCrossing>().single()
        assertThat(p.roadName).isEqualTo("Mount Road")
        assertThat(p.distanceM!!).isWithin(1.0).of(30.0)
    }

    @Test
    fun competingCandidatesAtSimilarDistance_areAmbiguous() {
        val ctx = interpreter.interpret(
            Fx.metadata(
                features = listOf(
                    Fx.feature(1, Fx.at(10.0, 0.0), mapOf("highway" to "crossing")),
                    Fx.feature(2, Fx.at(0.0, 12.0), mapOf("highway" to "crossing")),
                ),
            ),
            Fx.fix(), null, 0,
        )
        assertThat(ctx.uncertainties).contains(UncertaintyReason.CROSSING_AMBIGUOUS)

        val clear = interpreter.interpret(
            Fx.metadata(
                features = listOf(
                    Fx.feature(1, Fx.at(10.0, 0.0), mapOf("highway" to "crossing")),
                    Fx.feature(2, Fx.at(0.0, 28.0), mapOf("highway" to "crossing")),
                ),
            ),
            Fx.fix(), null, 0,
        )
        assertThat(clear.uncertainties).doesNotContain(UncertaintyReason.CROSSING_AMBIGUOUS)
    }
}
