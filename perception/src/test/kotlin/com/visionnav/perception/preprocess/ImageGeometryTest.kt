package com.visionnav.perception.preprocess

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.vision.NormBox
import org.junit.Test

class ImageGeometryTest {

    /** A box in the top-right corner of the (landscape) sensor buffer. */
    private val sensorTopRight = NormBox(0.75f, 0.0f, 1.0f, 0.25f)

    private fun assertBox(actual: NormBox, expected: NormBox) {
        assertThat(actual.left).isWithin(1e-6f).of(expected.left)
        assertThat(actual.top).isWithin(1e-6f).of(expected.top)
        assertThat(actual.right).isWithin(1e-6f).of(expected.right)
        assertThat(actual.bottom).isWithin(1e-6f).of(expected.bottom)
    }

    @Test fun rotation0_isIdentity() =
        assertBox(ImageGeometry.sensorBoxToUpright(sensorTopRight, 0), NormBox(0.75f, 0f, 1f, 0.25f))

    @Test fun rotation90_clockwise_topRightGoesToBottomRight() =
        assertBox(ImageGeometry.sensorBoxToUpright(sensorTopRight, 90), NormBox(0.75f, 0.75f, 1f, 1f))

    @Test fun rotation180_topRightGoesToBottomLeft() =
        assertBox(ImageGeometry.sensorBoxToUpright(sensorTopRight, 180), NormBox(0f, 0.75f, 0.25f, 1f))

    @Test fun rotation270_topRightGoesToTopLeft() =
        assertBox(ImageGeometry.sensorBoxToUpright(sensorTopRight, 270), NormBox(0f, 0f, 0.25f, 0.25f))

    @Test fun rotation90_sensorTopLeftGoesToUprightTopRight() {
        val (x, y) = ImageGeometry.sensorPointToUpright(0f, 0f, 90)
        assertThat(x).isEqualTo(1f)
        assertThat(y).isEqualTo(0f)
    }

    @Test fun negativeAndLargeRotationsNormalise() {
        assertThat(ImageGeometry.normalizeRotation(-90)).isEqualTo(270)
        assertThat(ImageGeometry.normalizeRotation(450)).isEqualTo(90)
    }

    @Test fun pointInverseRoundTrips() {
        for (r in listOf(0, 90, 180, 270)) {
            val (ux, uy) = ImageGeometry.sensorPointToUpright(0.3f, 0.8f, r)
            val (sx, sy) = ImageGeometry.uprightPointToSensor(ux, uy, r)
            assertThat(sx).isWithin(1e-6f).of(0.3f)
            assertThat(sy).isWithin(1e-6f).of(0.8f)
        }
    }

    @Test fun uprightSizeSwapsFor90And270() {
        assertThat(ImageGeometry.uprightSize(640, 480, 0)).isEqualTo(640 to 480)
        assertThat(ImageGeometry.uprightSize(640, 480, 90)).isEqualTo(480 to 640)
        assertThat(ImageGeometry.uprightSize(640, 480, 180)).isEqualTo(640 to 480)
        assertThat(ImageGeometry.uprightSize(640, 480, 270)).isEqualTo(480 to 640)
    }

    @Test fun discretePixelMappingIsConsistentWithForwardRotation() {
        val w = 8
        val h = 6
        for (r in listOf(0, 90, 180, 270)) {
            val (uw, uh) = ImageGeometry.uprightSize(w, h, r)
            val seen = HashSet<Pair<Int, Int>>()
            for (uy in 0 until uh) for (ux in 0 until uw) {
                val p = ImageGeometry.uprightPixelToSensor(ux, uy, w, h, r)
                val sx = ImageGeometry.packedX(p)
                val sy = ImageGeometry.packedY(p)
                assertThat(sx).isIn(0 until w)
                assertThat(sy).isIn(0 until h)
                seen += sx to sy
                // Pixel centres must agree with the continuous mapping.
                val (cx, cy) = ImageGeometry.sensorPointToUpright((sx + 0.5f) / w, (sy + 0.5f) / h, r)
                assertThat(cx).isWithin(1e-5f).of((ux + 0.5f) / uw)
                assertThat(cy).isWithin(1e-5f).of((uy + 0.5f) / uh)
            }
            assertThat(seen).hasSize(w * h) // bijection
        }
    }

    @Test fun letterboxPortraitIntoSquare() {
        val lb = LetterboxParams.compute(480, 640, 320)
        assertThat(lb.scale).isEqualTo(0.5f)
        assertThat(lb.contentWidth).isEqualTo(240)
        assertThat(lb.contentHeight).isEqualTo(320)
        assertThat(lb.padX).isEqualTo(40)
        assertThat(lb.padY).isEqualTo(0)
        assertThat(lb.inputXToUprightNorm(40f)).isWithin(1e-6f).of(0f)
        assertThat(lb.inputXToUprightNorm(280f)).isWithin(1e-6f).of(1f)
        assertThat(lb.inputYToUprightNorm(160f)).isWithin(1e-6f).of(0.5f)
    }

    @Test fun letterboxLandscapeIntoSquare() {
        val lb = LetterboxParams.compute(640, 480, 320)
        assertThat(lb.contentWidth).isEqualTo(320)
        assertThat(lb.contentHeight).isEqualTo(240)
        assertThat(lb.padX).isEqualTo(0)
        assertThat(lb.padY).isEqualTo(40)
        assertThat(lb.uprightNormYToInput(lb.inputYToUprightNorm(123f))).isWithin(1e-4f).of(123f)
    }
}
