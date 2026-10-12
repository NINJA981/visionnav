package com.visionnav.perception.detect

import com.visionnav.core.vision.Detection
import com.visionnav.core.vision.DetectorCapabilities
import com.visionnav.core.vision.NormBox
import com.visionnav.core.vision.ObjectClass
import com.visionnav.perception.preprocess.PreprocessedFrame

/**
 * Scripted stand-in detector for tests, demos and as the visible fallback when the real model
 * cannot be loaded. [capabilities] always reports `isMock = true` so the UI and logs can say so.
 *
 * Each [detect] call returns the next entry of [script] (cycling when [loop] is true, otherwise
 * returning an empty list once exhausted). An empty script never produces detections — this is
 * the production fallback: no fabricated objects, and `isMock` tells downstream code that an
 * empty result means "no detector", not "nothing there".
 */
class MockYoloDetector(
    private val script: List<List<Detection>> = emptyList(),
    private val loop: Boolean = true,
    modelName: String = "mock",
    inputSize: Int = 320,
) : ObjectDetector {

    override val capabilities = DetectorCapabilities(
        modelName = modelName,
        supportedClasses = ObjectClass.entries.filter { it != ObjectClass.UNKNOWN }.toSet(),
        isMock = true,
        inputSize = inputSize,
        accelerator = "none",
    )

    /** Number of [detect] calls so far. */
    var calls: Int = 0
        private set

    @Volatile
    var closed: Boolean = false
        private set

    override fun detect(frame: PreprocessedFrame): List<Detection> {
        check(!closed) { "detector closed" }
        val i = calls++
        if (script.isEmpty()) return emptyList()
        return when {
            loop -> script[i % script.size]
            i < script.size -> script[i]
            else -> emptyList()
        }
    }

    override fun close() {
        closed = true
    }

    companion object {
        /**
         * Demo sequence: a car crossing the view from left to right while growing (approaching),
         * plus a stationary person on the right. 20 frames, looping.
         */
        fun demo(): MockYoloDetector {
            val frames = (0 until 20).map { t ->
                val cx = 0.15f + 0.03f * t
                val h = 0.12f + 0.006f * t
                listOf(
                    Detection(ObjectClass.CAR, 0.82f, NormBox(cx - h, 0.55f - h / 2, cx + h, 0.55f + h / 2)),
                    Detection(ObjectClass.PERSON, 0.74f, NormBox(0.78f, 0.35f, 0.88f, 0.75f)),
                )
            }
            return MockYoloDetector(frames, loop = true, modelName = "mock-demo")
        }
    }
}
