package com.visionnav.perception.preprocess

import com.visionnav.core.vision.NormBox
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure orientation math shared by preprocessing and decoding.
 *
 * ## Orientation convention
 * CameraX reports `ImageInfo.rotationDegrees` = the **clockwise** rotation (0/90/180/270) that must
 * be applied to the sensor buffer so that it appears upright relative to the target rotation of the
 * use case. [com.visionnav.perception.camera.CameraSession] pins the analysis target rotation to
 * `Surface.ROTATION_0`, so "upright" always means **the device's natural (portrait) orientation**,
 * independent of any activity/display rotation (the session may run from a foreground service).
 *
 * Coordinates: x grows left→right, y grows top→bottom, in both the sensor buffer and the upright
 * image. Normalised coordinates are in [0, 1] (see [NormBox]).
 */
object ImageGeometry {

    /** Normalises any integer rotation to 0, 90, 180 or 270. Throws for non-multiples of 90. */
    fun normalizeRotation(degrees: Int): Int {
        require(degrees % 90 == 0) { "rotation must be a multiple of 90, was $degrees" }
        return ((degrees % 360) + 360) % 360
    }

    /** Size of the upright image for a sensor buffer of [width]×[height] rotated by [rotationDegrees]. */
    fun uprightSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        when (normalizeRotation(rotationDegrees)) {
            90, 270 -> height to width
            else -> width to height
        }

    /**
     * Maps a normalised point in the sensor buffer to the normalised upright image (clockwise
     * rotation by [rotationDegrees]). Returns (x, y).
     */
    fun sensorPointToUpright(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (normalizeRotation(rotationDegrees)) {
            90 -> (1f - y) to x
            180 -> (1f - x) to (1f - y)
            270 -> y to (1f - x)
            else -> x to y
        }

    /** Inverse of [sensorPointToUpright]: upright normalised point → sensor normalised point. */
    fun uprightPointToSensor(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (normalizeRotation(rotationDegrees)) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }

    /** Maps a normalised box from sensor coordinates into upright coordinates. */
    fun sensorBoxToUpright(box: NormBox, rotationDegrees: Int): NormBox {
        val (x1, y1) = sensorPointToUpright(box.left, box.top, rotationDegrees)
        val (x2, y2) = sensorPointToUpright(box.right, box.bottom, rotationDegrees)
        return NormBox(min(x1, x2), min(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    /**
     * Discrete inverse mapping used by the per-pixel sampling loop: upright pixel ([ux], [uy]) →
     * sensor pixel, for a sensor buffer of [sensorWidth]×[sensorHeight]. Returns `x` in the high
     * 32 bits and `y` in the low 32 bits of a Long to avoid allocation in hot loops; use
     * [packedX] / [packedY] to unpack.
     */
    fun uprightPixelToSensor(ux: Int, uy: Int, sensorWidth: Int, sensorHeight: Int, rotationDegrees: Int): Long {
        val sx: Int
        val sy: Int
        when (normalizeRotation(rotationDegrees)) {
            90 -> { sx = uy; sy = sensorHeight - 1 - ux }
            180 -> { sx = sensorWidth - 1 - ux; sy = sensorHeight - 1 - uy }
            270 -> { sx = sensorWidth - 1 - uy; sy = ux }
            else -> { sx = ux; sy = uy }
        }
        return (sx.toLong() shl 32) or (sy.toLong() and 0xFFFFFFFFL)
    }

    fun packedX(packed: Long): Int = (packed ushr 32).toInt()
    fun packedY(packed: Long): Int = packed.toInt()
}

/**
 * Parameters of an aspect-preserving "letterbox" resize of an upright image of
 * [srcWidth]×[srcHeight] into a square [dstSize]×[dstSize] model input, centred with padding.
 *
 * Model-input pixel `xin` maps back to upright pixel `(xin - padX) / scale`.
 */
data class LetterboxParams(
    val srcWidth: Int,
    val srcHeight: Int,
    val dstSize: Int,
    val scale: Float,
    val contentWidth: Int,
    val contentHeight: Int,
    val padX: Int,
    val padY: Int,
) {
    /** Model-input pixel x → normalised upright x (unclamped). */
    fun inputXToUprightNorm(xin: Float): Float = (xin - padX) / scale / srcWidth

    /** Model-input pixel y → normalised upright y (unclamped). */
    fun inputYToUprightNorm(yin: Float): Float = (yin - padY) / scale / srcHeight

    /** Normalised upright x → model-input pixel x (inverse of [inputXToUprightNorm]). */
    fun uprightNormXToInput(x: Float): Float = x * srcWidth * scale + padX

    /** Normalised upright y → model-input pixel y (inverse of [inputYToUprightNorm]). */
    fun uprightNormYToInput(y: Float): Float = y * srcHeight * scale + padY

    companion object {
        fun compute(srcWidth: Int, srcHeight: Int, dstSize: Int): LetterboxParams {
            require(srcWidth > 0 && srcHeight > 0 && dstSize > 0) { "invalid sizes $srcWidth×$srcHeight → $dstSize" }
            val scale = min(dstSize.toFloat() / srcWidth, dstSize.toFloat() / srcHeight)
            val cw = (srcWidth * scale).roundToInt().coerceIn(1, dstSize)
            val ch = (srcHeight * scale).roundToInt().coerceIn(1, dstSize)
            return LetterboxParams(
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                dstSize = dstSize,
                scale = scale,
                contentWidth = cw,
                contentHeight = ch,
                padX = (dstSize - cw) / 2,
                padY = (dstSize - ch) / 2,
            )
        }
    }
}
