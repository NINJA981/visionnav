package com.visionnav.perception.quality

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.UncertaintyReason
import org.junit.Test
import kotlin.random.Random

class FrameQualityAnalyzerTest {

    private val w = 160
    private val h = 120
    private val analyzer = FrameQualityAnalyzer()

    private fun image(f: (x: Int, y: Int) -> Int) = ByteArray(w * h) { i -> f(i % w, i / w).coerceIn(0, 255).toByte() }

    @Test fun black_isDarkAndObstructed() {
        val q = analyzer.analyze(image { _, _ -> 0 }, w, h)
        assertThat(q.meanLuma).isEqualTo(0f)
        assertThat(q.darkRatio).isEqualTo(1f)
        assertThat(q.glareRatio).isEqualTo(0f)
        assertThat(q.lumaStdDev).isEqualTo(0f)
        assertThat(q.sharpness).isEqualTo(0f)
        assertThat(q.issues()).containsAtLeast(UncertaintyReason.CAMERA_OBSTRUCTED, UncertaintyReason.LOW_LIGHT)
    }

    @Test fun bright_isGlare() {
        val q = analyzer.analyze(image { _, _ -> 255 }, w, h)
        assertThat(q.meanLuma).isEqualTo(255f)
        assertThat(q.glareRatio).isEqualTo(1f)
        assertThat(q.darkRatio).isEqualTo(0f)
        assertThat(q.issues()).contains(UncertaintyReason.GLARE)
        assertThat(q.issues()).doesNotContain(UncertaintyReason.LOW_LIGHT)
    }

    @Test fun uniformGrey_hasNoTextureButIsNotDarkOrObstructed() {
        val q = analyzer.analyze(image { _, _ -> 128 }, w, h)
        assertThat(q.meanLuma).isEqualTo(128f)
        assertThat(q.lumaStdDev).isEqualTo(0f)
        assertThat(q.sharpness).isEqualTo(0f)
        assertThat(q.darkRatio).isEqualTo(0f)
        assertThat(q.glareRatio).isEqualTo(0f)
        assertThat(q.issues()).containsExactly(UncertaintyReason.BLUR)
    }

    @Test fun checkerboard_isSharpWithHighContrast() {
        // Cell = 2 px = the sampling step for a 160-wide image (80-cell grid).
        val q = analyzer.analyze(image { x, y -> if ((x / 2 + y / 2) % 2 == 0) 30 else 230 }, w, h)
        assertThat(q.meanLuma).isWithin(1f).of(130f)
        assertThat(q.lumaStdDev).isWithin(1f).of(100f)
        assertThat(q.darkRatio).isWithin(0.01f).of(0.5f)
        assertThat(q.sharpness).isGreaterThan(10_000f)
        assertThat(q.issues()).isEmpty()
    }

    @Test fun smoothGradient_isLessSharpThanNoise() {
        val gradient = analyzer.analyze(image { x, _ -> 60 + x }, w, h)
        val rnd = Random(7)
        val noise = analyzer.analyze(image { _, _ -> 60 + rnd.nextInt(140) }, w, h)
        assertThat(gradient.sharpness).isLessThan(1f)
        assertThat(noise.sharpness).isGreaterThan(gradient.sharpness * 100 + 100)
    }

    @Test fun respectsRowStride() {
        val stride = w + 32
        val buf = ByteArray(stride * h) { 255.toByte() } // padding is bright
        for (y in 0 until h) for (x in 0 until w) buf[y * stride + x] = 10
        val q = analyzer.analyze(buf, w, h, rowStride = stride)
        assertThat(q.meanLuma).isEqualTo(10f)
        assertThat(q.glareRatio).isEqualTo(0f)
    }
}
