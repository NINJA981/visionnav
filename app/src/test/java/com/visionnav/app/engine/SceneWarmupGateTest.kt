package com.visionnav.app.engine

import com.google.common.truth.Truth.assertThat
import com.visionnav.app.engine.SceneWarmupGate.Verdict
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.vision.CameraState
import com.visionnav.core.vision.DetectorCapabilities
import org.junit.Test

class SceneWarmupGateTest {

    private val request = 10_000L
    private val gate = SceneWarmupGate(requestMs = request)
    private val running = CameraState.Running(caps(isMock = false), torchOn = false, hasTorch = true)

    private fun caps(isMock: Boolean) = DetectorCapabilities("yolo", emptySet(), isMock, 320, "gpu")

    @Test
    fun `device log case - detector loads 3 s after the tap - waits for frames instead of describing nothing`() {
        // 0..3 s: camera starting, detector loading on GPU, no frames analysed.
        for (t in 0L..3_000L step 100) assertThat(gate.verdict(request + t, CameraState.Starting)).isEqualTo(Verdict.WAIT)
        // 3.4 s: first frames from the ready detector arrive (the old 3 s warm-up had already given up).
        gate.onFrame(request + 3_400, detectorReady = true)
        gate.onFrame(request + 3_600, detectorReady = true)
        assertThat(gate.verdict(request + 3_600, running)).isEqualTo(Verdict.WAIT)
        gate.onFrame(request + 3_800, detectorReady = true)
        gate.onFrame(request + 4_000, detectorReady = true)
        assertThat(gate.verdict(request + 4_000, running)).isEqualTo(Verdict.WAIT) // 4 frames but only 0.6 s
        gate.onFrame(request + 4_600, detectorReady = true)
        assertThat(gate.verdict(request + 4_600, running)).isEqualTo(Verdict.READY) // ≥ 4 frames over ≥ 1.2 s
        assertThat(gate.analysedFrames).isEqualTo(5)
    }

    @Test
    fun `needs four frames even when they span more than the minimum time`() {
        gate.onFrame(request + 100, true)
        gate.onFrame(request + 1_000, true)
        gate.onFrame(request + 2_000, true)
        assertThat(gate.verdict(request + 2_000, running)).isEqualTo(Verdict.WAIT)
        gate.onFrame(request + 2_500, true)
        assertThat(gate.verdict(request + 2_500, running)).isEqualTo(Verdict.READY)
    }

    @Test
    fun `frames from before the request or before the detector is ready do not count`() {
        gate.onFrame(request - 500, true)
        repeat(10) { i -> gate.onFrame(request + i * 300L, detectorReady = false) }
        assertThat(gate.analysedFrames).isEqualTo(0)
        assertThat(gate.verdict(request + 3_000, CameraState.Starting)).isEqualTo(Verdict.WAIT)
    }

    @Test
    fun `cap with no analysed frame is NOT_READY, never an empty description`() {
        assertThat(gate.verdict(request + 5_999, CameraState.Starting)).isEqualTo(Verdict.WAIT)
        assertThat(gate.verdict(request + 6_000, CameraState.Starting)).isEqualTo(Verdict.NOT_READY)
        assertThat(gate.verdict(request + 6_000, running)).isEqualTo(Verdict.NOT_READY)
    }

    @Test
    fun `cap with a few analysed frames describes what was seen`() {
        gate.onFrame(request + 5_500, true)
        assertThat(gate.verdict(request + 6_000, running)).isEqualTo(Verdict.PARTIAL)
    }

    @Test
    fun `camera unavailable ends the wait after a short grace for a stale failure state`() {
        val unavailable = CameraState.Unavailable(UncertaintyReason.CAMERA_UNAVAILABLE)
        assertThat(gate.verdict(request, unavailable)).isEqualTo(Verdict.WAIT)
        assertThat(gate.verdict(request + SceneWarmupGate.UNAVAILABLE_GRACE_MS, unavailable)).isEqualTo(Verdict.CAMERA_UNAVAILABLE)
    }

    @Test
    fun `failed or mock detector is reported, not described as an empty scene`() {
        val failed = CameraState.Unavailable(UncertaintyReason.DETECTOR_UNAVAILABLE)
        assertThat(gate.verdict(request + 1_000, failed)).isEqualTo(Verdict.DETECTOR_UNAVAILABLE)
        val mock = CameraState.Running(caps(isMock = true), torchOn = false, hasTorch = false)
        assertThat(gate.verdict(request, mock)).isEqualTo(Verdict.DETECTOR_UNAVAILABLE)
        assertThat(SceneWarmupGate.isDetectorReady(mock)).isFalse()
        assertThat(SceneWarmupGate.isDetectorReady(CameraState.Starting)).isFalse()
        assertThat(SceneWarmupGate.isDetectorReady(running)).isTrue()
    }
}
