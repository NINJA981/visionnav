package com.visionnav.core.tracking

import com.visionnav.core.vision.Detection
import com.visionnav.core.vision.ObjectClass

/**
 * Configuration for [DetectionFilter].
 *
 * @param minConfidenceByClass per-class confidence floor. Safety-relevant classes (people and
 *   vehicles) use a slightly lower floor so that weak-but-real detections can still extend an
 *   existing track (the tracker's low-confidence pass); spurious single detections are kept out of
 *   the output by the tracker's confirmation requirement, not by an aggressive floor here.
 * @param defaultMinConfidence floor for classes not in [minConfidenceByClass].
 * @param minBoxArea boxes smaller than this fraction of the image are dropped (tiny boxes from a
 *   320 px model are unreliable in both class and geometry).
 * @param nmsIouThreshold class-aware NMS IoU threshold (safety net if upstream skipped NMS).
 * @param familyNmsIouThreshold optional second NMS across a class family (e.g. the model emitting
 *   both "car" and "truck" for one vehicle). Only near-identical boxes are merged. Null disables.
 * @param classFamilies groups of classes the detector commonly confuses.
 */
data class DetectionFilterConfig(
    val minConfidenceByClass: Map<ObjectClass, Float> = DEFAULT_MIN_CONFIDENCE,
    val defaultMinConfidence: Float = 0.45f,
    val minBoxArea: Float = 0.001f,
    val nmsIouThreshold: Float = 0.5f,
    val familyNmsIouThreshold: Float? = 0.8f,
    val classFamilies: List<Set<ObjectClass>> = DEFAULT_CLASS_FAMILIES,
) {
    fun minConfidenceFor(c: ObjectClass): Float = minConfidenceByClass[c] ?: defaultMinConfidence

    companion object {
        val DEFAULT_MIN_CONFIDENCE: Map<ObjectClass, Float> = mapOf(
            ObjectClass.PERSON to 0.40f,
            ObjectClass.BICYCLE to 0.40f,
            ObjectClass.CAR to 0.40f,
            ObjectClass.MOTORCYCLE to 0.40f,
            ObjectClass.BUS to 0.40f,
            ObjectClass.TRUCK to 0.40f,
        )

        /** car/truck/bus are often confused by COCO models, as are bicycle/motorcycle. */
        val DEFAULT_CLASS_FAMILIES: List<Set<ObjectClass>> = listOf(
            setOf(ObjectClass.CAR, ObjectClass.TRUCK, ObjectClass.BUS),
            setOf(ObjectClass.BICYCLE, ObjectClass.MOTORCYCLE),
        )
    }
}

/**
 * First stage after the detector: drops low-confidence, tiny, malformed and [ObjectClass.UNKNOWN]
 * detections, clips boxes to the image, and runs a class-aware NMS safety net.
 *
 * Output is sorted by descending confidence. The filter is stateless and thread-safe.
 */
class DetectionFilter(val config: DetectionFilterConfig = DetectionFilterConfig()) {

    fun filter(detections: List<Detection>): List<Detection> {
        val candidates = detections.asSequence()
            .filter { it.objectClass != ObjectClass.UNKNOWN }
            .filter { it.confidence.isFinite() && it.confidence >= config.minConfidenceFor(it.objectClass) }
            .filter { BoxGeometry.isFinite(it.box) }
            .map { it.copy(box = BoxGeometry.clip(it.box)) }
            .filter { it.box.width > 0f && it.box.height > 0f && it.box.area >= config.minBoxArea }
            .sortedByDescending { it.confidence }
            .toList()

        val perClass = nms(candidates, config.nmsIouThreshold) { a, b -> a.objectClass == b.objectClass }
        val familyIou = config.familyNmsIouThreshold ?: return perClass
        return nms(perClass, familyIou) { a, b -> sameFamily(a.objectClass, b.objectClass) }
    }

    private fun sameFamily(a: ObjectClass, b: ObjectClass): Boolean =
        a == b || config.classFamilies.any { a in it && b in it }

    /** Greedy NMS over a confidence-sorted list; [comparable] decides which pairs may suppress each other. */
    private fun nms(sorted: List<Detection>, iouThreshold: Float, comparable: (Detection, Detection) -> Boolean): List<Detection> {
        val kept = ArrayList<Detection>(sorted.size)
        for (d in sorted) {
            val suppressed = kept.any { k -> comparable(k, d) && BoxGeometry.iou(k.box, d.box) > iouThreshold }
            if (!suppressed) kept += d
        }
        return kept
    }
}
