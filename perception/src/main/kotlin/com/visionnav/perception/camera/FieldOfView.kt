package com.visionnav.perception.camera

import kotlin.math.atan

/**
 * Horizontal field of view of the **upright analysis image**, from Camera2 characteristics.
 *
 * - The analysis stream (e.g. 640×480) is assumed to be the largest centred crop of the active
 *   sensor area with the stream's aspect ratio, at 1× zoom (standard HAL behaviour).
 * - Image width/height are in sensor orientation. With rotation 90/270 (portrait phone, landscape sensor)
 *   the upright horizontal axis is the **sensor's short side**, so the FOV is narrower.
 * - `physicalSize` refers to the full pixel array; when the active array is smaller it is scaled
 *   by `activeArray / pixelArray`.
 *
 * This is the lens/camera the session is bound to (default back camera). No wide-angle claim is
 * made; null is returned when inputs are missing or implausible.
 */
object FieldOfView {

    fun horizontalFovDeg(
        focalLengthMm: Float?,
        physicalWidthMm: Float?,
        physicalHeightMm: Float?,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
        activeToPixelArrayScaleX: Float = 1f,
        activeToPixelArrayScaleY: Float = 1f,
    ): Float? {
        if (focalLengthMm == null || physicalWidthMm == null || physicalHeightMm == null) return null
        if (focalLengthMm <= 0f || physicalWidthMm <= 0f || physicalHeightMm <= 0f) return null
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val sw = physicalWidthMm * activeToPixelArrayScaleX
        val sh = physicalHeightMm * activeToPixelArrayScaleY
        val sensorAspect = sw / sh
        // ImageProxy width/height are in sensor orientation: image x lies along the sensor x axis.
        val streamAspect = imageWidth.toFloat() / imageHeight
        val (coveredW, coveredH) = if (streamAspect >= sensorAspect) sw to sw / streamAspect else sh * streamAspect to sh
        val rot = ((rotationDegrees % 360) + 360) % 360
        val extent = if (rot == 90 || rot == 270) coveredH else coveredW
        val fov = Math.toDegrees(2.0 * atan(extent / (2.0 * focalLengthMm))).toFloat()
        return fov.takeIf { it in 1f..179f }
    }
}
