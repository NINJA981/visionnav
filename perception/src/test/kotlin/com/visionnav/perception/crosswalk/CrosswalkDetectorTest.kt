package com.visionnav.perception.crosswalk

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class CrosswalkDetectorTest {

    // Upright portrait luma thumbnail, as produced by ImagePreprocessor for a 640×480 stream.
    private val w = 180
    private val h = 240
    private val detector = CrosswalkDetector()

    private fun image(seed: Int = 1, noise: Int = 8, f: (x: Int, y: Int) -> Int): ByteArray {
        val rnd = Random(seed)
        return ByteArray(w * h) { i ->
            val n = if (noise > 0) rnd.nextInt(-noise, noise + 1) else 0
            (f(i % w, i / w) + n).coerceIn(0, 255).toByte()
        }
    }

    private val road = 70
    private val paint = 200

    @Test fun regularHorizontalStripes_detected() {
        val img = image { _, y -> if (y >= 135 && ((y - 135) / 10) % 2 == 0) paint else road }
        val obs = detector.detect(img, w, h)
        assertThat(obs).isNotNull()
        assertThat(obs!!.stripeCount).isAtLeast(3)
        assertThat(obs.score).isGreaterThan(0.6f)
    }

    @Test fun perspectiveTiltedStripes_detected() {
        // Bands thicken towards the bottom (closer) and are slightly tilted (~5°).
        val bands = buildList {
            var y = 134
            var width = 6
            var light = true
            while (y < h + 40) {
                add(Triple(y, y + width, light))
                y += width
                if (!light) width += 2
                light = !light
            }
        }
        val img = image(seed = 3) { x, y ->
            val yy = y + ((x - w / 2) * 0.09f).toInt()
            if (bands.any { (a, b, l) -> l && yy >= a && yy < b }) paint else road
        }
        val obs = detector.detect(img, w, h)
        assertThat(obs).isNotNull()
        assertThat(obs!!.stripeCount).isAtLeast(3)
    }

    @Test fun uniformRoad_rejected() {
        assertThat(detector.detect(image(noise = 6) { _, _ -> road }, w, h)).isNull()
    }

    @Test fun randomNoise_rejected() {
        for (seed in 1..5) {
            val rnd = Random(seed * 7919)
            val img = ByteArray(w * h) { rnd.nextInt(256).toByte() }
            assertThat(detector.detect(img, w, h)).isNull()
        }
    }

    @Test fun singleLine_rejected() {
        val img = image { _, y -> if (y in 180 until 192) paint else road }
        assertThat(detector.detect(img, w, h)).isNull()
    }

    @Test fun twoLines_rejected() {
        val img = image { _, y -> if (y in 160 until 170 || y in 200 until 210) paint else road }
        assertThat(detector.detect(img, w, h)).isNull()
    }

    @Test fun verticalLaneLines_rejected() {
        // Lines running away from the viewer appear vertical: no alternation along any scanline.
        val img = image { x, _ -> if ((x / 12) % 2 == 0) paint else road }
        assertThat(detector.detect(img, w, h)).isNull()
    }

    @Test fun stripesOnlyInUpperImage_ignored() {
        val img = image { _, y -> if (y < 100 && (y / 10) % 2 == 0) paint else road }
        assertThat(detector.detect(img, w, h)).isNull()
    }

    @Test fun lowContrastStripes_rejected() {
        val img = image(noise = 2) { _, y -> if (y >= 135 && ((y - 135) / 10) % 2 == 0) road + 20 else road }
        assertThat(detector.detect(img, w, h)).isNull()
    }

    @Test fun tooSmallImage_returnsNull() {
        assertThat(detector.detect(ByteArray(8 * 8), 8, 8)).isNull()
    }
}
