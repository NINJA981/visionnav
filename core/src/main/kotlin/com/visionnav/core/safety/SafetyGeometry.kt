package com.visionnav.core.safety

import com.visionnav.core.vision.NormBox
import com.visionnav.core.vision.ObjectCategory
import com.visionnav.core.vision.ObjectClass

/**
 * Box-height-fraction thresholds for one class. A box at least [veryNear] of the image height is
 * VERY_NEAR, etc. These are **relative bands**, not distances: they encode "this object fills a lot
 * of the view", which depends on object size, lens and how the phone is held.
 */
data class ProximityThresholds(val veryNear: Float, val near: Float, val medium: Float) {
    init {
        require(veryNear > near && near > medium && medium > 0f) { "thresholds must be strictly decreasing" }
    }
}

/**
 * Image-geometry configuration shared by the safety engine and the scene/path describers.
 *
 * @param corridorLeft / [corridorRight] horizontal extent of the walking corridor (centre band of
 *   the upright image, which is where the user walks when holding the phone forward).
 * @param pathTopY the corridor only covers the lower part of the image (y ≥ this): objects whose box
 *   is entirely above it (signs, distant things on the horizon) are not in the walking path.
 * @param bottomEdgeNear / [bottomEdgeMedium] a ground object whose box reaches this low in the image
 *   is at least NEAR / MEDIUM regardless of its height (catches objects cut off by the frame edge).
 *   Not applied to elevated classes (traffic lights, stop signs).
 * @param thresholdsByClass class-specific height thresholds (buses are taller than people at the same
 *   distance, so the same height fraction means "further away" for a bus).
 * @param defaultThresholds used for classes without an entry.
 */
data class SafetyGeometryConfig(
    val corridorLeft: Float = 0.35f,
    val corridorRight: Float = 0.65f,
    val pathTopY: Float = 0.40f,
    val bottomEdgeNear: Float = 0.97f,
    val bottomEdgeMedium: Float = 0.85f,
    val thresholdsByClass: Map<ObjectClass, ProximityThresholds> = DEFAULT_THRESHOLDS,
    val defaultThresholds: ProximityThresholds = ProximityThresholds(veryNear = 0.45f, near = 0.28f, medium = 0.14f),
) {
    init {
        require(corridorLeft in 0f..1f && corridorRight in 0f..1f && corridorLeft < corridorRight) { "invalid corridor" }
        require(pathTopY in 0f..1f) { "invalid pathTopY" }
    }

    fun thresholdsFor(c: ObjectClass): ProximityThresholds = thresholdsByClass[c] ?: defaultThresholds

    companion object {
        private val PERSON = ProximityThresholds(veryNear = 0.75f, near = 0.45f, medium = 0.22f)
        private val CAR = ProximityThresholds(veryNear = 0.50f, near = 0.30f, medium = 0.14f)
        private val LARGE_VEHICLE = ProximityThresholds(veryNear = 0.70f, near = 0.45f, medium = 0.22f)
        private val TWO_WHEELER = ProximityThresholds(veryNear = 0.60f, near = 0.35f, medium = 0.18f)
        private val SMALL_ANIMAL = ProximityThresholds(veryNear = 0.35f, near = 0.20f, medium = 0.10f)
        private val LARGE_ANIMAL = ProximityThresholds(veryNear = 0.60f, near = 0.35f, medium = 0.18f)
        private val STREET_FURNITURE = ProximityThresholds(veryNear = 0.40f, near = 0.25f, medium = 0.12f)

        val DEFAULT_THRESHOLDS: Map<ObjectClass, ProximityThresholds> = mapOf(
            ObjectClass.PERSON to PERSON,
            ObjectClass.CAR to CAR,
            ObjectClass.BUS to LARGE_VEHICLE,
            ObjectClass.TRUCK to LARGE_VEHICLE,
            ObjectClass.BICYCLE to TWO_WHEELER,
            ObjectClass.MOTORCYCLE to TWO_WHEELER,
            ObjectClass.DOG to SMALL_ANIMAL,
            ObjectClass.COW to LARGE_ANIMAL,
            ObjectClass.BENCH to STREET_FURNITURE,
            ObjectClass.CHAIR to STREET_FURNITURE,
            ObjectClass.FIRE_HYDRANT to STREET_FURNITURE,
            ObjectClass.PARKING_METER to STREET_FURNITURE,
            ObjectClass.POTTED_PLANT to STREET_FURNITURE,
            ObjectClass.DINING_TABLE to STREET_FURNITURE,
            ObjectClass.SUITCASE to STREET_FURNITURE,
        )
    }
}

/**
 * Pure image-geometry classifications: sector, relative proximity band and walking-path overlap.
 *
 * Nothing here is a distance. [proximity] is a coarse, class-aware ordering of "how much of the view
 * this object fills / how low it reaches", which correlates with nearness for ground objects when
 * the phone is held roughly upright, and is only ever spoken as relative words ("close").
 */
class SafetyGeometry(val config: SafetyGeometryConfig = SafetyGeometryConfig()) {

    /** Horizontal quintile of the box centre. */
    fun sector(box: NormBox): ViewSector {
        val x = box.centerX
        return when {
            x < 0.2f -> ViewSector.FAR_LEFT
            x < 0.4f -> ViewSector.LEFT
            x < 0.6f -> ViewSector.CENTER
            x < 0.8f -> ViewSector.RIGHT
            else -> ViewSector.FAR_RIGHT
        }
    }

    /**
     * Relative proximity band. The more conservative (nearer) of the height-based and the
     * bottom-edge-based band is used. Degenerate boxes yield [ProximityBand.UNKNOWN].
     */
    fun proximity(objectClass: ObjectClass, box: NormBox): ProximityBand {
        val h = box.height
        if (!h.isFinite() || h <= 0f || box.width <= 0f) return ProximityBand.UNKNOWN
        val t = config.thresholdsFor(objectClass)
        val byHeight = when {
            h >= t.veryNear -> ProximityBand.VERY_NEAR
            h >= t.near -> ProximityBand.NEAR
            h >= t.medium -> ProximityBand.MEDIUM
            else -> ProximityBand.FAR
        }
        if (objectClass.category == ObjectCategory.TRAFFIC_CONTROL) return byHeight
        val byBottom = when {
            box.bottom >= config.bottomEdgeNear -> ProximityBand.NEAR
            box.bottom >= config.bottomEdgeMedium -> ProximityBand.MEDIUM
            else -> ProximityBand.FAR
        }
        return if (byBottom.rank > byHeight.rank) byBottom else byHeight
    }

    /** True if the box overlaps the central corridor in the lower part of the image. */
    fun inWalkingPath(box: NormBox): Boolean =
        box.left < config.corridorRight && box.right > config.corridorLeft && box.bottom > config.pathTopY

    companion object {
        /** Ordering for comparisons; UNKNOWN sorts below FAR. */
        val ProximityBand.rank: Int
            get() = when (this) {
                ProximityBand.UNKNOWN -> -1
                ProximityBand.FAR -> 0
                ProximityBand.MEDIUM -> 1
                ProximityBand.NEAR -> 2
                ProximityBand.VERY_NEAR -> 3
            }
    }
}
