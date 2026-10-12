package com.visionnav.core.vision

import com.visionnav.core.common.UncertaintyReason

/**
 * Axis-aligned box in **normalised upright-image coordinates**: x grows left→right,
 * y grows top→bottom, both in [0, 1]. "Upright" means sensor rotation has already been
 * applied so the image matches what a person holding the phone in portrait would see.
 */
data class NormBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)
}

enum class ObjectCategory { VEHICLE, PERSON, ANIMAL, OBSTACLE, TRAFFIC_CONTROL, OTHER }

/**
 * Classes the app reasons about. [cocoId] is the index in the COCO-80 label list used by
 * YOLOv8. Presence here does NOT prove the shipped model detects it — see [DetectorCapabilities].
 */
enum class ObjectClass(val cocoId: Int, val label: String, val category: ObjectCategory) {
    PERSON(0, "person", ObjectCategory.PERSON),
    BICYCLE(1, "bicycle", ObjectCategory.VEHICLE),
    CAR(2, "car", ObjectCategory.VEHICLE),
    MOTORCYCLE(3, "motorcycle", ObjectCategory.VEHICLE),
    BUS(5, "bus", ObjectCategory.VEHICLE),
    TRUCK(7, "truck", ObjectCategory.VEHICLE),
    TRAFFIC_LIGHT(9, "traffic light", ObjectCategory.TRAFFIC_CONTROL),
    FIRE_HYDRANT(10, "fire hydrant", ObjectCategory.OBSTACLE),
    STOP_SIGN(11, "stop sign", ObjectCategory.TRAFFIC_CONTROL),
    PARKING_METER(12, "parking meter", ObjectCategory.OBSTACLE),
    BENCH(13, "bench", ObjectCategory.OBSTACLE),
    DOG(16, "dog", ObjectCategory.ANIMAL),
    COW(19, "cow", ObjectCategory.ANIMAL),
    CHAIR(56, "chair", ObjectCategory.OBSTACLE),
    POTTED_PLANT(58, "potted plant", ObjectCategory.OBSTACLE),
    DINING_TABLE(60, "table", ObjectCategory.OBSTACLE),
    SUITCASE(28, "suitcase", ObjectCategory.OBSTACLE),
    UNKNOWN(-1, "object", ObjectCategory.OTHER);

    val isVehicle: Boolean get() = category == ObjectCategory.VEHICLE

    companion object {
        private val byCoco = entries.filter { it.cocoId >= 0 }.associateBy { it.cocoId }
        fun fromCocoId(id: Int): ObjectClass? = byCoco[id]
    }
}

/** One detection in one frame. */
data class Detection(
    val objectClass: ObjectClass,
    val confidence: Float,
    val box: NormBox,
)

/** What the loaded model can actually do. Reported honestly to the user and in logs. */
data class DetectorCapabilities(
    val modelName: String,
    val supportedClasses: Set<ObjectClass>,
    val isMock: Boolean,
    val inputSize: Int,
    val accelerator: String,
) {
    fun supportsVehicles(): Boolean = supportedClasses.any { it.isVehicle }
}

/** Image quality metrics for one frame, computed from the luma plane. */
data class FrameQuality(
    /** Mean luma, 0..255. */
    val meanLuma: Float,
    /** Fraction of pixels with luma < 40. */
    val darkRatio: Float,
    /** Fraction of pixels with luma > 245. */
    val glareRatio: Float,
    /** Variance of Laplacian; low = blurry. Scale depends on downsampled size. */
    val sharpness: Float,
    /** Luma standard deviation; near-zero suggests the lens is covered. */
    val lumaStdDev: Float,
) {
    /** Problems ordered by severity. Empty means the frame is usable. */
    fun issues(thresholds: FrameQualityThresholds = FrameQualityThresholds()): List<UncertaintyReason> = buildList {
        if (lumaStdDev < thresholds.obstructedStdDev && meanLuma < thresholds.obstructedMaxLuma) add(UncertaintyReason.CAMERA_OBSTRUCTED)
        if (meanLuma < thresholds.lowLightMeanLuma || darkRatio > thresholds.lowLightDarkRatio) add(UncertaintyReason.LOW_LIGHT)
        if (glareRatio > thresholds.glareRatio) add(UncertaintyReason.GLARE)
        if (sharpness < thresholds.minSharpness) add(UncertaintyReason.BLUR)
    }
}

data class FrameQualityThresholds(
    val obstructedStdDev: Float = 6f,
    val obstructedMaxLuma: Float = 60f,
    val lowLightMeanLuma: Float = 45f,
    val lowLightDarkRatio: Float = 0.7f,
    val glareRatio: Float = 0.25f,
    val minSharpness: Float = 15f,
)

/** Output of the perception pipeline for one analysed frame. */
data class FrameObservation(
    val elapsedMs: Long,
    val detections: List<Detection>,
    val quality: FrameQuality?,
    /** Horizontal field of view of the analysed image in degrees, if known from camera characteristics. */
    val horizontalFovDeg: Float?,
    val crosswalk: CrosswalkObservation?,
)

/** Classical-CV zebra stripe evidence in the lower part of the frame. Single-frame; persistence is applied downstream. */
data class CrosswalkObservation(
    /** 0..1 evidence score. */
    val score: Float,
    val stripeCount: Int,
)

sealed interface CameraState {
    data object Inactive : CameraState
    data object Starting : CameraState
    data class Running(val capabilities: DetectorCapabilities?, val torchOn: Boolean, val hasTorch: Boolean) : CameraState
    data class Unavailable(val reason: UncertaintyReason) : CameraState
}

/**
 * Analysis rate requested by consumers. The single camera session uses the highest active
 * demand; when no consumer requests frames the camera is released.
 */
enum class AnalysisDemand(val targetFps: Int) { NONE(0), LOW(2), WALKING(5), OBSERVING(10) }
