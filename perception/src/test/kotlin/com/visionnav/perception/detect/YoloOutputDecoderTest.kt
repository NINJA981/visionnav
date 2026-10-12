package com.visionnav.perception.detect

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.vision.Detection
import com.visionnav.core.vision.NormBox
import com.visionnav.core.vision.ObjectClass
import com.visionnav.perception.preprocess.LetterboxParams
import org.junit.Test

class YoloOutputDecoderTest {

    private val channels = 84
    private val anchors = 2100
    // Upright portrait 480×640 letterboxed into 320: scale 0.5, padX 40, padY 0.
    private val lb = LetterboxParams.compute(480, 640, 320)

    private class Anchor(val cx: Float, val cy: Float, val w: Float, val h: Float, val cls: Int, val score: Float)

    /** Builds a `[1, 84, N]` tensor with boxes in input pixels, like the shipped TFLite model. */
    private fun channelsFirst(vararg a: Anchor): FloatArray {
        val out = FloatArray(channels * anchors)
        a.forEachIndexed { i, an ->
            out[0 * anchors + i] = an.cx
            out[1 * anchors + i] = an.cy
            out[2 * anchors + i] = an.w
            out[3 * anchors + i] = an.h
            out[(4 + an.cls) * anchors + i] = an.score
        }
        return out
    }

    private fun transpose(cf: FloatArray): FloatArray {
        val t = FloatArray(cf.size)
        for (c in 0 until channels) for (i in 0 until anchors) t[i * channels + c] = cf[c * anchors + i]
        return t
    }

    private val cfShape = intArrayOf(1, channels, anchors)
    private val tShape = intArrayOf(1, anchors, channels)

    private fun assertBox(actual: NormBox, expected: NormBox, tol: Float = 1e-4f) {
        assertThat(actual.left).isWithin(tol).of(expected.left)
        assertThat(actual.top).isWithin(tol).of(expected.top)
        assertThat(actual.right).isWithin(tol).of(expected.right)
        assertThat(actual.bottom).isWithin(tol).of(expected.bottom)
    }

    // Upright box (0.25, 0.25)–(0.75, 0.5) ⇒ upright px x 120..360, y 160..320 ⇒ input x 100..220, y 80..160.
    private val car = Anchor(cx = 160f, cy = 120f, w = 120f, h = 80f, cls = 2, score = 0.9f)

    @Test fun decodesKnownBoxThroughLetterboxUndo() {
        val dets = YoloOutputDecoder().decode(channelsFirst(car), cfShape, lb)
        assertThat(dets).hasSize(1)
        assertThat(dets[0].objectClass).isEqualTo(ObjectClass.CAR)
        assertThat(dets[0].confidence).isWithin(1e-6f).of(0.9f)
        assertBox(dets[0].box, NormBox(0.25f, 0.25f, 0.75f, 0.5f))
    }

    @Test fun transposedLayoutGivesSameResult() {
        val decoder = YoloOutputDecoder()
        val cf = channelsFirst(car, Anchor(60f, 250f, 30f, 60f, 0, 0.7f))
        val a = decoder.decode(cf, cfShape, lb)
        val b = decoder.decode(transpose(cf), tShape, lb)
        assertThat(decoder.layoutOf(tShape).channelsFirst).isFalse()
        assertThat(b).isEqualTo(a)
        assertThat(a).hasSize(2)
    }

    @Test fun layoutInferredForSmallAnchorCounts() {
        val d = YoloOutputDecoder()
        assertThat(d.layoutOf(intArrayOf(1, 84, 10)).channelsFirst).isTrue()
        assertThat(d.layoutOf(intArrayOf(1, 10, 84)).channelsFirst).isFalse()
        assertThat(d.layoutOf(intArrayOf(1, 84, 2100)).numClasses).isEqualTo(80)
    }

    @Test fun normalizedUnitsSupported() {
        val out = channelsFirst(car)
        for (c in 0 until 4) out[c * anchors] /= 320f
        val dets = YoloOutputDecoder(boxUnits = BoxUnits.NORMALIZED).decode(out, cfShape, lb)
        assertBox(dets.single().box, NormBox(0.25f, 0.25f, 0.75f, 0.5f))
    }

    @Test fun nmsSuppressesSameClassDuplicates() {
        val dup = Anchor(cx = 162f, cy = 121f, w = 118f, h = 80f, cls = 2, score = 0.8f)
        val dets = YoloOutputDecoder().decode(channelsFirst(car, dup), cfShape, lb)
        assertThat(dets).hasSize(1)
        assertThat(dets[0].confidence).isWithin(1e-6f).of(0.9f)
    }

    @Test fun nmsIsClassWise() {
        val truckSameBox = Anchor(car.cx, car.cy, car.w, car.h, cls = 7, score = 0.6f)
        val dets = YoloOutputDecoder().decode(channelsFirst(car, truckSameBox), cfShape, lb)
        assertThat(dets.map { it.objectClass }).containsExactly(ObjectClass.CAR, ObjectClass.TRUCK).inOrder()
    }

    @Test fun lowConfidenceAndUnmappedClassesDropped() {
        val weak = Anchor(60f, 60f, 20f, 20f, cls = 0, score = 0.1f)
        val airplane = Anchor(200f, 200f, 40f, 40f, cls = 4, score = 0.95f) // COCO 4 not in ObjectClass
        val dets = YoloOutputDecoder().decode(channelsFirst(car, weak, airplane), cfShape, lb)
        assertThat(dets.map { it.objectClass }).containsExactly(ObjectClass.CAR)
    }

    @Test fun argmaxPicksHighestClass() {
        val out = channelsFirst(car)
        out[(4 + 0) * anchors] = 0.5f // person 0.5 < car 0.9
        out[(4 + 5) * anchors] = 0.95f // bus 0.95 wins
        assertThat(YoloOutputDecoder().decode(out, cfShape, lb).single().objectClass).isEqualTo(ObjectClass.BUS)
    }

    @Test fun boxesInPaddingAreClampedToImage() {
        // Box extending into the left pad (input x 20..80) ⇒ clamped at upright x = 0.
        val a = Anchor(cx = 50f, cy = 160f, w = 60f, h = 40f, cls = 0, score = 0.8f)
        val d = YoloOutputDecoder().decode(channelsFirst(a), cfShape, lb).single()
        assertThat(d.box.left).isEqualTo(0f)
        assertThat(d.box.right).isWithin(1e-4f).of((80f - 40f) / 0.5f / 480f)
    }

    @Test fun boxEntirelyInPaddingIsDropped() {
        val a = Anchor(cx = 15f, cy = 160f, w = 20f, h = 40f, cls = 0, score = 0.8f)
        assertThat(YoloOutputDecoder().decode(channelsFirst(a), cfShape, lb)).isEmpty()
    }

    @Test fun maxDetectionsRespected() {
        val many = (0 until 60).map { i ->
            Anchor(cx = 45f + (i % 10) * 23f, cy = 20f + (i / 10) * 50f, w = 10f, h = 10f, cls = 0, score = 0.5f + i * 0.001f)
        }
        val dets = YoloOutputDecoder(maxDetections = 50).decode(channelsFirst(*many.toTypedArray()), cfShape, lb)
        assertThat(dets).hasSize(50)
        assertThat(dets.map { it.confidence }).isInOrder(Comparator.reverseOrder<Float>())
    }

    @Test fun iouBasics() {
        val a = NormBox(0f, 0f, 1f, 1f)
        assertThat(Nms.iou(a, a)).isWithin(1e-6f).of(1f)
        assertThat(Nms.iou(a, NormBox(0.5f, 0f, 1.5f, 1f))).isWithin(1e-6f).of(1f / 3f)
        assertThat(Nms.iou(a, NormBox(2f, 2f, 3f, 3f))).isEqualTo(0f)
    }

    @Test fun nmsDirect() {
        val d1 = Detection(ObjectClass.PERSON, 0.9f, NormBox(0.1f, 0.1f, 0.3f, 0.5f))
        val d2 = Detection(ObjectClass.PERSON, 0.8f, NormBox(0.11f, 0.1f, 0.31f, 0.5f))
        val d3 = Detection(ObjectClass.PERSON, 0.7f, NormBox(0.6f, 0.1f, 0.8f, 0.5f))
        assertThat(Nms.classWise(listOf(d2, d3, d1), 0.45f, 50)).containsExactly(d1, d3).inOrder()
    }
}
