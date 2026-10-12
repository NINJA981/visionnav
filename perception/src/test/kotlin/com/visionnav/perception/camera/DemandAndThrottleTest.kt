package com.visionnav.perception.camera

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.vision.AnalysisDemand
import com.visionnav.perception.camera.DemandAggregator.Action
import org.junit.Test

class DemandAggregatorTest {

    @Test fun effectiveDemandIsMaximum() {
        val agg = DemandAggregator()
        agg.set("nav", AnalysisDemand.WALKING)
        agg.set("crossing", AnalysisDemand.OBSERVING)
        agg.set("scene", AnalysisDemand.LOW)
        assertThat(agg.effective).isEqualTo(AnalysisDemand.OBSERVING)
        agg.set("crossing", AnalysisDemand.NONE)
        assertThat(agg.effective).isEqualTo(AnalysisDemand.WALKING)
    }

    @Test fun firstDemandStartsAndLastNoneStops() {
        val agg = DemandAggregator()
        assertThat(agg.set("a", AnalysisDemand.WALKING).action).isEqualTo(Action.START)
        assertThat(agg.set("b", AnalysisDemand.OBSERVING).action).isEqualTo(Action.UPDATE_RATE)
        assertThat(agg.set("b", AnalysisDemand.NONE).action).isEqualTo(Action.UPDATE_RATE)
        val stop = agg.set("a", AnalysisDemand.NONE)
        assertThat(stop.action).isEqualTo(Action.STOP)
        assertThat(stop.current).isEqualTo(AnalysisDemand.NONE)
        assertThat(agg.activeClients).isEmpty()
    }

    @Test fun singleSessionNeverDuplicated() {
        val agg = DemandAggregator()
        val actions = listOf(
            agg.set("a", AnalysisDemand.WALKING),
            agg.set("b", AnalysisDemand.WALKING),
            agg.set("c", AnalysisDemand.LOW),
            agg.set("a", AnalysisDemand.WALKING), // repeat
            agg.set("a", AnalysisDemand.OBSERVING),
        ).map { it.action }
        assertThat(actions.count { it == Action.START }).isEqualTo(1)
        assertThat(actions).doesNotContain(Action.STOP)
    }

    @Test fun noneForUnknownClientIsNoop() {
        val agg = DemandAggregator()
        assertThat(agg.set("ghost", AnalysisDemand.NONE).action).isEqualTo(Action.NONE)
        assertThat(agg.effective).isEqualTo(AnalysisDemand.NONE)
    }

    @Test fun restartAfterStop() {
        val agg = DemandAggregator()
        agg.set("a", AnalysisDemand.LOW)
        agg.set("a", AnalysisDemand.NONE)
        assertThat(agg.set("a", AnalysisDemand.LOW).action).isEqualTo(Action.START)
    }

    @Test fun clearReleases() {
        val agg = DemandAggregator()
        agg.set("a", AnalysisDemand.LOW)
        agg.set("b", AnalysisDemand.OBSERVING)
        assertThat(agg.clear().action).isEqualTo(Action.STOP)
        assertThat(agg.clear().action).isEqualTo(Action.NONE)
    }
}

class FrameThrottleTest {

    /** Feeds a 30 fps stream for [seconds] and returns the number of accepted frames. */
    private fun accepted(targetFps: Int, factor: Float = 1f, seconds: Int = 4, jitterNs: Long = 0): Int {
        val t = FrameThrottle()
        val frameNs = 33_333_333L
        var n = 0
        for (i in 0 until 30 * seconds) {
            val ts = 1_000_000_000L + i * frameNs + if (i % 2 == 0) jitterNs else -jitterNs
            if (t.tryAccept(ts, targetFps, factor)) n++
        }
        return n
    }

    @Test fun walkingRateFromThirtyFps() = assertThat(accepted(5)).isIn(19..21)

    @Test fun observingRate() = assertThat(accepted(10)).isIn(38..41)

    @Test fun lowRate() = assertThat(accepted(2)).isIn(7..9)

    @Test fun throttleFactorHalvesRate() = assertThat(accepted(10, factor = 0.5f)).isIn(19..21)

    @Test fun throttleFactorHasFloor() = assertThat(accepted(2, factor = 0.01f, seconds = 10)).isIn(5..6)

    @Test fun jitterToleratedWithoutDroppingRate() = assertThat(accepted(5, jitterNs = 2_000_000)).isIn(19..21)

    @Test fun noDemandAcceptsNothing() = assertThat(accepted(0)).isEqualTo(0)

    @Test fun timestampResetIsAccepted() {
        val t = FrameThrottle()
        assertThat(t.tryAccept(5_000_000_000L, 5, 1f)).isTrue()
        assertThat(t.tryAccept(5_010_000_000L, 5, 1f)).isFalse()
        assertThat(t.tryAccept(1_000L, 5, 1f)).isTrue() // camera rebind: new timestamp base
    }

    @Test fun resetAcceptsImmediately() {
        val t = FrameThrottle()
        assertThat(t.tryAccept(1_000_000_000L, 2, 1f)).isTrue()
        t.reset()
        assertThat(t.tryAccept(1_010_000_000L, 2, 1f)).isTrue()
    }
}

class FieldOfViewTest {

    // Typical 1/2.55" main camera: 4.38 mm focal, 5.64 × 4.23 mm sensor (4:3).
    private val f = 4.38f
    private val sw = 5.64f
    private val sh = 4.23f

    @Test fun portraitUsesSensorShortSide() {
        val fov = FieldOfView.horizontalFovDeg(f, sw, sh, 640, 480, 90)!!
        assertThat(fov).isWithin(0.2f).of(51.56f)
        assertThat(FieldOfView.horizontalFovDeg(f, sw, sh, 640, 480, 270)!!).isWithin(1e-3f).of(fov)
    }

    @Test fun landscapeUsesSensorLongSide() {
        assertThat(FieldOfView.horizontalFovDeg(f, sw, sh, 640, 480, 0)!!).isWithin(0.2f).of(65.55f)
    }

    @Test fun wideStreamIsCroppedVertically() {
        // 16:9 stream on a 4:3 sensor keeps full width, crops height ⇒ narrower portrait FOV.
        val fov = FieldOfView.horizontalFovDeg(f, sw, sh, 1280, 720, 90)!!
        assertThat(fov).isWithin(0.3f).of(39.7f)
    }

    @Test fun activeArrayScaleApplied() {
        val full = FieldOfView.horizontalFovDeg(f, sw, sh, 640, 480, 0)!!
        val cropped = FieldOfView.horizontalFovDeg(f, sw, sh, 640, 480, 0, 0.9f, 0.9f)!!
        assertThat(cropped).isLessThan(full)
    }

    @Test fun missingOrInvalidInputsGiveNull() {
        assertThat(FieldOfView.horizontalFovDeg(null, sw, sh, 640, 480, 90)).isNull()
        assertThat(FieldOfView.horizontalFovDeg(f, null, sh, 640, 480, 90)).isNull()
        assertThat(FieldOfView.horizontalFovDeg(0f, sw, sh, 640, 480, 90)).isNull()
        assertThat(FieldOfView.horizontalFovDeg(f, sw, sh, 0, 480, 90)).isNull()
    }
}
