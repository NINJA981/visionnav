package com.visionnav.core.crossing

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.Reliability
import com.visionnav.core.crossing.Fx.estimate
import com.visionnav.core.crossing.Fx.reliable
import com.visionnav.core.crossing.Fx.road
import com.visionnav.core.crossing.Fx.unreliable
import com.visionnav.core.tracking.ApproachState
import com.visionnav.core.tracking.ImageMotion
import com.visionnav.core.vision.ObjectClass
import org.junit.Test

/** Camera faces north (0°); the road runs east-west, i.e. across the view. */
class FlowFusionTest {

    private val fusion = FlowFusion()
    private val oneWayEast = road(RoadDirectionality.ONE_WAY, listOf(90.0))
    private val rightward = estimate(1, imageMotion = ImageMotion.MOVING_RIGHT_IN_IMAGE)
    private val leftward = estimate(2, imageMotion = ImageMotion.MOVING_LEFT_IN_IMAGE)

    // ---- Brief 11: unreliable orientation → no world-direction claim

    @Test
    fun brief11_worldLateralOnlyWithReliableOrientation() {
        assertThat(fusion.worldLateralDirection(rightward, reliable(0.0))).isEqualTo(WorldLateral.LEFT_TO_RIGHT)
        assertThat(fusion.worldLateralDirection(leftward, reliable(0.0))).isEqualTo(WorldLateral.RIGHT_TO_LEFT)
        assertThat(fusion.worldLateralDirection(rightward, unreliable(0.0))).isEqualTo(WorldLateral.UNKNOWN)
        assertThat(fusion.worldLateralDirection(rightward, CameraOrientationEstimate(null, Reliability.RELIABLE, emptySet())))
            .isEqualTo(WorldLateral.UNKNOWN)
        assertThat(fusion.worldLateralDirection(estimate(3, imageMotion = ImageMotion.UNKNOWN), reliable(0.0)))
            .isEqualTo(WorldLateral.UNKNOWN)
        assertThat(fusion.approximateTravelBearing(rightward, unreliable(0.0))).isNull()
    }

    @Test
    fun travelBearing_isCameraBearingPlusMinus90_binned() {
        assertThat(fusion.approximateTravelBearing(rightward, reliable(10.0))).isWithin(1e-9).of(90.0)
        assertThat(fusion.approximateTravelBearing(leftward, reliable(10.0))).isWithin(1e-9).of(270.0)
        assertThat(fusion.approximateTravelBearing(rightward, reliable(350.0))).isWithin(1e-9).of(90.0)
        assertThat(fusion.approximateTravelBearing(rightward, reliable(30.0))).isWithin(1e-9).of(135.0)
    }

    // ---- Brief 13: expected flow compared only when transforms are reliable

    @Test
    fun brief13_matchAndMismatch_whenEverythingReliable() {
        assertThat(fusion.compare(rightward, reliable(0.0), oneWayEast, 66f)).isEqualTo(FlowComparison.EXPECTED_FLOW_MATCH)
        assertThat(fusion.compare(leftward, reliable(0.0), oneWayEast, 66f)).isEqualTo(FlowComparison.EXPECTED_FLOW_MISMATCH)
        // Camera turned to face south: image-right is now west.
        assertThat(fusion.compare(rightward, reliable(180.0), oneWayEast, 66f)).isEqualTo(FlowComparison.EXPECTED_FLOW_MISMATCH)
    }

    @Test
    fun brief13_insufficientWhenAnyTransformOrInputIsNotReliable() {
        val insufficient = FlowComparison.INSUFFICIENT_INFORMATION
        assertThat(fusion.compare(leftward, unreliable(0.0), oneWayEast, 66f)).isEqualTo(insufficient)
        assertThat(fusion.compare(leftward, reliable(0.0), oneWayEast, null)).isEqualTo(insufficient)
        assertThat(fusion.compare(leftward, reliable(0.0), null, 66f)).isEqualTo(insufficient)
        assertThat(fusion.compare(leftward, reliable(0.0), road(RoadDirectionality.UNKNOWN, emptyList(), Reliability.UNKNOWN), 66f))
            .isEqualTo(insufficient)
        assertThat(fusion.compare(leftward, reliable(0.0), road(RoadDirectionality.ONE_WAY, listOf(90.0), Reliability.LOW), 66f))
            .isEqualTo(insufficient)
        assertThat(fusion.compare(leftward, reliable(0.0), road(RoadDirectionality.ONE_WAY, listOf(90.0), distanceM = null), 66f))
            .isEqualTo(insufficient)
        assertThat(fusion.compare(leftward, reliable(0.0), road(RoadDirectionality.ONE_WAY, listOf(90.0), distanceM = 80.0), 66f))
            .isEqualTo(insufficient)
        // Camera looks along the road (road not across the view).
        assertThat(fusion.compare(leftward, reliable(90.0), oneWayEast, 66f)).isEqualTo(insufficient)
        assertThat(fusion.compareDetailed(leftward, reliable(90.0), oneWayEast, 66f).limitation)
            .isEqualTo(FusionLimitation.ROAD_NOT_ACROSS_VIEW)
        // No lateral motion, or not a vehicle.
        assertThat(fusion.compare(estimate(4, imageMotion = ImageMotion.STATIONARY_OR_LOW_MOTION, approach = ApproachState.APPROACHING), reliable(0.0), oneWayEast, 66f))
            .isEqualTo(insufficient)
        assertThat(fusion.compare(estimate(5, cls = ObjectClass.PERSON, imageMotion = ImageMotion.MOVING_LEFT_IN_IMAGE), reliable(0.0), oneWayEast, 66f))
            .isEqualTo(insufficient)
    }

    @Test
    fun twoWayRoad_matchesEitherDirection_neverMismatch() {
        val twoWay = road(RoadDirectionality.TWO_WAY, listOf(90.0, 270.0))
        assertThat(fusion.compare(rightward, reliable(0.0), twoWay, 66f)).isEqualTo(FlowComparison.EXPECTED_FLOW_MATCH)
        assertThat(fusion.compare(leftward, reliable(0.0), twoWay, 66f)).isEqualTo(FlowComparison.EXPECTED_FLOW_MATCH)
        for (cam in 0 until 360 step 15) {
            for (e in listOf(rightward, leftward)) {
                assertThat(fusion.compare(e, reliable(cam.toDouble()), twoWay, 66f)).isNotEqualTo(FlowComparison.EXPECTED_FLOW_MISMATCH)
            }
        }
    }

    @Test
    fun inferredRoundaboutDirection_isComparedButFlagged() {
        val roundabout = road(RoadDirectionality.ONE_WAY, listOf(90.0), Reliability.INFERRED)
        val r = fusion.compareDetailed(leftward, reliable(0.0), roundabout, 66f)
        assertThat(r.comparison).isEqualTo(FlowComparison.EXPECTED_FLOW_MISMATCH)
        assertThat(r.basedOnInferredDirection).isTrue()
        assertThat(fusion.compareDetailed(leftward, reliable(0.0), oneWayEast, 66f).basedOnInferredDirection).isFalse()
    }

    @Test
    fun fusionIsPure_sameInputsSameOutputs() {
        val a = fusion.compareDetailed(leftward, reliable(0.0), oneWayEast, 66f)
        repeat(5) { assertThat(fusion.compareDetailed(leftward, reliable(0.0), oneWayEast, 66f)).isEqualTo(a) }
    }

    // ---- MismatchPersistence

    @Test
    fun mismatchPersistence_requiresDurationOrTwoTracks() {
        val p = MismatchPersistence()
        val mm = FlowComparison.EXPECTED_FLOW_MISMATCH
        assertThat(p.update(1, mm, 0)).isFalse()
        assertThat(p.update(1, mm, 500)).isFalse()
        assertThat(p.update(1, mm, 1_000)).isFalse()
        assertThat(p.update(1, mm, 1_500)).isTrue()

        val q = MismatchPersistence()
        assertThat(q.update(1, mm, 0)).isFalse()
        assertThat(q.update(2, mm, 200)).isTrue()
    }

    @Test
    fun mismatchPersistence_matchOrGapResetsRun() {
        val p = MismatchPersistence()
        val mm = FlowComparison.EXPECTED_FLOW_MISMATCH
        p.update(1, mm, 0)
        p.update(1, mm, 800)
        p.update(1, FlowComparison.EXPECTED_FLOW_MATCH, 1_000)
        assertThat(p.update(1, mm, 1_600)).isFalse()

        val q = MismatchPersistence()
        q.update(1, mm, 0)
        assertThat(q.update(1, mm, 2_000)).isFalse() // gap > 1 s restarts the run
        assertThat(q.update(1, mm, 2_800)).isFalse()
        assertThat(q.update(1, mm, 3_500)).isTrue()
        q.reset()
        assertThat(q.isConfirmed(3_500)).isFalse()
    }
}
