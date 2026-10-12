package com.visionnav.core.tracking

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.tracking.TrackFixtures.boxAt
import com.visionnav.core.tracking.TrackFixtures.det
import com.visionnav.core.vision.NormBox
import com.visionnav.core.vision.ObjectClass
import org.junit.Test

class DetectionFilterTest {

    private val filter = DetectionFilter()

    @Test
    fun `drops unknown class`() {
        val out = filter.filter(listOf(det(ObjectClass.UNKNOWN, boxAt(0.5f), 0.99f)))
        assertThat(out).isEmpty()
    }

    @Test
    fun `per-class confidence floors apply`() {
        val out = filter.filter(
            listOf(
                det(ObjectClass.CAR, boxAt(0.2f), 0.41f), // vehicle floor 0.40 -> kept
                det(ObjectClass.PERSON, boxAt(0.5f), 0.39f), // person floor 0.40 -> dropped
                det(ObjectClass.BENCH, boxAt(0.8f), 0.44f), // default 0.45 -> dropped
                det(ObjectClass.CHAIR, boxAt(0.8f, cy = 0.2f), 0.46f), // default -> kept
            ),
        )
        assertThat(out.map { it.objectClass }).containsExactly(ObjectClass.CAR, ObjectClass.CHAIR)
    }

    @Test
    fun `custom confidence map is honoured`() {
        val f = DetectionFilter(DetectionFilterConfig(minConfidenceByClass = mapOf(ObjectClass.CAR to 0.9f)))
        assertThat(f.filter(listOf(det(ObjectClass.CAR, boxAt(0.5f), 0.85f)))).isEmpty()
    }

    @Test
    fun `drops tiny, degenerate and non-finite boxes`() {
        val out = filter.filter(
            listOf(
                det(ObjectClass.CAR, NormBox(0.5f, 0.5f, 0.51f, 0.51f)), // area 1e-4
                det(ObjectClass.CAR, NormBox(0.5f, 0.5f, 0.4f, 0.7f)), // negative width
                det(ObjectClass.CAR, NormBox(Float.NaN, 0.1f, 0.3f, 0.3f)),
                det(ObjectClass.CAR, NormBox(0.1f, 0.1f, 0.3f, 0.3f), Float.NaN),
            ),
        )
        assertThat(out).isEmpty()
    }

    @Test
    fun `clips boxes to the image`() {
        val out = filter.filter(listOf(det(ObjectClass.CAR, NormBox(-0.2f, 0.5f, 0.3f, 1.3f))))
        assertThat(out.single().box).isEqualTo(NormBox(0f, 0.5f, 0.3f, 1f))
    }

    @Test
    fun `class-aware NMS keeps the best of overlapping same-class boxes`() {
        val a = det(ObjectClass.PERSON, NormBox(0.40f, 0.3f, 0.50f, 0.8f), 0.9f)
        val b = det(ObjectClass.PERSON, NormBox(0.41f, 0.3f, 0.51f, 0.8f), 0.7f)
        val out = filter.filter(listOf(b, a))
        assertThat(out).containsExactly(a)
    }

    @Test
    fun `NMS does not suppress different unrelated classes`() {
        val person = det(ObjectClass.PERSON, NormBox(0.40f, 0.3f, 0.50f, 0.8f), 0.9f)
        val bike = det(ObjectClass.BICYCLE, NormBox(0.40f, 0.3f, 0.50f, 0.8f), 0.7f)
        assertThat(filter.filter(listOf(person, bike))).hasSize(2)
    }

    @Test
    fun `family NMS merges near-identical car and truck boxes but not merely overlapping ones`() {
        val car = det(ObjectClass.CAR, NormBox(0.2f, 0.4f, 0.6f, 0.8f), 0.8f)
        val truckSame = det(ObjectClass.TRUCK, NormBox(0.21f, 0.4f, 0.6f, 0.8f), 0.6f)
        assertThat(filter.filter(listOf(car, truckSame))).containsExactly(car)

        val truckOverlap = det(ObjectClass.TRUCK, NormBox(0.4f, 0.4f, 0.8f, 0.8f), 0.6f)
        assertThat(filter.filter(listOf(car, truckOverlap))).hasSize(2)
    }

    @Test
    fun `output is sorted by confidence`() {
        val out = filter.filter(
            listOf(
                det(ObjectClass.CAR, boxAt(0.1f), 0.5f),
                det(ObjectClass.CAR, boxAt(0.5f), 0.9f),
                det(ObjectClass.CAR, boxAt(0.8f), 0.7f),
            ),
        )
        assertThat(out.map { it.confidence }).containsExactly(0.9f, 0.7f, 0.5f).inOrder()
    }
}
