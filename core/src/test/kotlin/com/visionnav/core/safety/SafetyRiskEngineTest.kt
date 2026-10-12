package com.visionnav.core.safety

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.tracking.ApproachState
import com.visionnav.core.tracking.ImageMotion
import com.visionnav.core.tracking.MotionEstimate
import com.visionnav.core.tracking.TrackFixtures.boxBottom
import com.visionnav.core.tracking.TrackFixtures.trackWithBox
import com.visionnav.core.vision.NormBox
import com.visionnav.core.vision.ObjectClass
import org.junit.Test

class SafetyRiskEngineTest {

    private val veryNearCarInPath = boxBottom(0.5f, bottom = 0.95f, w = 0.6f, h = 0.6f)
    private val farCarOutsidePath = boxBottom(0.1f, bottom = 0.55f, w = 0.1f, h = 0.08f)

    private fun estimate(id: Long, cls: ObjectClass, approach: ApproachState) = MotionEstimate(
        trackId = id, objectClass = cls, imageMotion = ImageMotion.STATIONARY_OR_LOW_MOTION, approach = approach,
        confidence = 0.8f, lateralSpeed = 0f, scaleGrowthRate = 0f, observationCount = 10, observationSpanMs = 1_000,
        reasons = emptySet(),
    )

    private fun single(engine: SafetyRiskEngine, cls: ObjectClass, box: NormBox, approach: ApproachState = ApproachState.UNKNOWN, nowMs: Long = 1_000): SafetyEvent? {
        val t = trackWithBox(1, cls, box, nowMs)
        return engine.evaluate(listOf(t), mapOf(1L to estimate(1, cls, approach)), nowMs).singleOrNull()
    }

    @Test
    fun `very near car in path is CRITICAL`() {
        val e = single(SafetyRiskEngine(), ObjectClass.CAR, veryNearCarInPath)!!
        assertThat(e.severity).isEqualTo(SafetySeverity.CRITICAL)
        assertThat(e.proximity).isEqualTo(ProximityBand.VERY_NEAR)
        assertThat(e.inWalkingPath).isTrue()
        assertThat(e.sector).isEqualTo(ViewSector.CENTER)
    }

    @Test
    fun `very near person in path is CRITICAL`() {
        val e = single(SafetyRiskEngine(), ObjectClass.PERSON, boxBottom(0.45f, bottom = 0.98f, w = 0.3f, h = 0.8f))!!
        assertThat(e.severity).isEqualTo(SafetySeverity.CRITICAL)
    }

    @Test
    fun `far car outside path produces no warning`() {
        val engine = SafetyRiskEngine()
        val e = single(engine, ObjectClass.CAR, farCarOutsidePath)!!
        assertThat(e.severity).isEqualTo(SafetySeverity.INFO)
        assertThat(e.inWalkingPath).isFalse()
        assertThat(e.proximity).isEqualTo(ProximityBand.FAR)

        val filtered = SafetyRiskEngine(SafetyRiskConfig(minEmittedSeverity = SafetySeverity.CAUTION))
        assertThat(single(filtered, ObjectClass.CAR, farCarOutsidePath)).isNull()
    }

    @Test
    fun `far approaching car outside path is at most CAUTION`() {
        val e = single(SafetyRiskEngine(), ObjectClass.CAR, farCarOutsidePath, ApproachState.APPROACHING)!!
        assertThat(e.severity).isEqualTo(SafetySeverity.CAUTION)
    }

    @Test
    fun `approaching vehicle at NEAR is CRITICAL even outside the path`() {
        val box = boxBottom(0.85f, bottom = 0.8f, w = 0.25f, h = 0.35f)
        val e = single(SafetyRiskEngine(), ObjectClass.CAR, box, ApproachState.APPROACHING)!!
        assertThat(e.proximity).isEqualTo(ProximityBand.NEAR)
        assertThat(e.inWalkingPath).isFalse()
        assertThat(e.severity).isEqualTo(SafetySeverity.CRITICAL)
        assertThat(e.approach).isEqualTo(ApproachState.APPROACHING)
    }

    @Test
    fun `same NEAR vehicle with unknown approach is only CAUTION`() {
        val box = boxBottom(0.85f, bottom = 0.8f, w = 0.25f, h = 0.35f)
        assertThat(single(SafetyRiskEngine(), ObjectClass.CAR, box)!!.severity).isEqualTo(SafetySeverity.CAUTION)
    }

    @Test
    fun `approaching vehicle at MEDIUM is WARNING`() {
        val box = boxBottom(0.85f, bottom = 0.7f, w = 0.2f, h = 0.2f)
        val e = single(SafetyRiskEngine(), ObjectClass.CAR, box, ApproachState.APPROACHING)!!
        assertThat(e.proximity).isEqualTo(ProximityBand.MEDIUM)
        assertThat(e.severity).isEqualTo(SafetySeverity.WARNING)
    }

    @Test
    fun `near person in path is WARNING and medium bench in path is CAUTION`() {
        val person = single(SafetyRiskEngine(), ObjectClass.PERSON, boxBottom(0.5f, bottom = 0.8f, w = 0.15f, h = 0.5f))!!
        assertThat(person.proximity).isEqualTo(ProximityBand.NEAR)
        assertThat(person.severity).isEqualTo(SafetySeverity.WARNING)

        val bench = single(SafetyRiskEngine(), ObjectClass.BENCH, boxBottom(0.5f, bottom = 0.8f, w = 0.3f, h = 0.15f))!!
        assertThat(bench.proximity).isEqualTo(ProximityBand.MEDIUM)
        assertThat(bench.severity).isEqualTo(SafetySeverity.CAUTION)
    }

    @Test
    fun `traffic control objects are always INFO`() {
        val e = single(SafetyRiskEngine(), ObjectClass.TRAFFIC_LIGHT, boxBottom(0.5f, bottom = 0.9f, w = 0.3f, h = 0.6f), ApproachState.APPROACHING)!!
        assertThat(e.severity).isEqualTo(SafetySeverity.INFO)
    }

    @Test
    fun `tracks coasting too long produce no events`() {
        val t = trackWithBox(1, ObjectClass.CAR, veryNearCarInPath, missedFrames = 3)
        assertThat(SafetyRiskEngine().evaluate(listOf(t), emptyMap(), 1_000)).isEmpty()
    }

    @Test
    fun `missing motion estimate means approach UNKNOWN`() {
        val t = trackWithBox(1, ObjectClass.CAR, veryNearCarInPath)
        assertThat(SafetyRiskEngine().evaluate(listOf(t), emptyMap(), 1_000).single().approach).isEqualTo(ApproachState.UNKNOWN)
    }

    @Test
    fun `hysteresis escalates immediately and de-escalates only after frames and time`() {
        val engine = SafetyRiskEngine(SafetyRiskConfig(deEscalateAfterFrames = 3, deEscalateAfterMs = 1_000))
        fun at(ms: Long, box: NormBox) = engine.evaluate(listOf(trackWithBox(1, ObjectClass.CAR, box, ms)), emptyMap(), ms).single().severity

        assertThat(at(0, farCarOutsidePath)).isEqualTo(SafetySeverity.INFO)
        assertThat(at(100, veryNearCarInPath)).isEqualTo(SafetySeverity.CRITICAL) // immediate escalation
        // Raw drops to INFO from t=200; held at CRITICAL until both 3 frames and 1000 ms have passed.
        for (ms in 200L..1_100L step 100) assertThat(at(ms, farCarOutsidePath)).isEqualTo(SafetySeverity.CRITICAL)
        assertThat(at(1_200, farCarOutsidePath)).isEqualTo(SafetySeverity.INFO)
    }

    @Test
    fun `flickering detections do not make severity flicker`() {
        val engine = SafetyRiskEngine()
        val seen = (0 until 20).map { i ->
            val box = if (i % 2 == 0) veryNearCarInPath else farCarOutsidePath
            engine.evaluate(listOf(trackWithBox(1, ObjectClass.CAR, box, i * 100L)), emptyMap(), i * 100L).single().severity
        }
        assertThat(seen.toSet()).containsExactly(SafetySeverity.CRITICAL)
    }

    @Test
    fun `de-escalation goes to the highest level seen in the window`() {
        val engine = SafetyRiskEngine(SafetyRiskConfig(deEscalateAfterFrames = 2, deEscalateAfterMs = 200))
        val nearPersonInPath = boxBottom(0.5f, bottom = 0.8f, w = 0.15f, h = 0.5f) // WARNING
        val veryNear = boxBottom(0.5f, bottom = 0.98f, w = 0.3f, h = 0.8f) // CRITICAL
        val far = boxBottom(0.1f, bottom = 0.5f, w = 0.05f, h = 0.1f) // INFO
        fun at(ms: Long, box: NormBox) = engine.evaluate(listOf(trackWithBox(1, ObjectClass.PERSON, box, ms)), emptyMap(), ms).single().severity

        assertThat(at(0, veryNear)).isEqualTo(SafetySeverity.CRITICAL)
        assertThat(at(100, nearPersonInPath)).isEqualTo(SafetySeverity.CRITICAL)
        assertThat(at(200, far)).isEqualTo(SafetySeverity.CRITICAL)
        assertThat(at(300, far)).isEqualTo(SafetySeverity.WARNING)
    }

    @Test
    fun `state is dropped for tracks that disappear`() {
        val engine = SafetyRiskEngine()
        engine.evaluate(listOf(trackWithBox(1, ObjectClass.CAR, veryNearCarInPath, 0)), emptyMap(), 0)
        engine.evaluate(emptyList(), emptyMap(), 100)
        val e = engine.evaluate(listOf(trackWithBox(1, ObjectClass.CAR, farCarOutsidePath, 200)), emptyMap(), 200).single()
        assertThat(e.severity).isEqualTo(SafetySeverity.INFO)
    }

    @Test
    fun `events are sorted by severity`() {
        val tracks = listOf(
            trackWithBox(1, ObjectClass.CAR, farCarOutsidePath),
            trackWithBox(2, ObjectClass.CAR, veryNearCarInPath),
        )
        val events = SafetyRiskEngine().evaluate(tracks, emptyMap(), 1_000)
        assertThat(events.map { it.trackId }).containsExactly(2L, 1L).inOrder()
    }
}
