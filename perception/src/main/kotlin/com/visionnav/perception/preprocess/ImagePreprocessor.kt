package com.visionnav.perception.preprocess

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Framework-free view of a YUV_420_888 image: three planes with their strides, plus the region of
 * interest ([cropLeft], [cropTop], [width], [height]) in luma pixels.
 *
 * Android guarantees that the U and V planes share the same row and pixel strides.
 */
class YuvPlanes(
    val y: ByteBuffer,
    val yRowStride: Int,
    val yPixelStride: Int,
    val u: ByteBuffer,
    val v: ByteBuffer,
    val uvRowStride: Int,
    val uvPixelStride: Int,
    val width: Int,
    val height: Int,
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
)

/**
 * Output of [ImagePreprocessor] for one frame.
 *
 * **Buffers are reused**: [input] and [luma] are owned by the preprocessor and are only valid until
 * the next call to `process`. The camera session guarantees a frame is fully consumed (detector,
 * quality, crosswalk) before the next one is preprocessed.
 */
class PreprocessedFrame(
    /** Direct, native-order float32 NHWC tensor `[1, inputSize, inputSize, 3]`, RGB in [0, 1]. */
    val input: ByteBuffer,
    val inputSize: Int,
    /** Letterbox applied to the **upright** image; used to map model outputs back. */
    val letterbox: LetterboxParams,
    /** Upright luma thumbnail (row-major, stride == [lumaWidth]) for quality and crosswalk analysis. */
    val luma: ByteArray,
    val lumaWidth: Int,
    val lumaHeight: Int,
    /** Upright image size of the analysed region, in pixels. */
    val uprightWidth: Int,
    val uprightHeight: Int,
    val rotationDegrees: Int,
    val timestampNs: Long,
)

/**
 * Converts CameraX `YUV_420_888` frames into the YOLO input tensor and an upright luma thumbnail.
 *
 * Pipeline (single pass, nearest-neighbour, no Bitmaps): for each model-input pixel inside the
 * letterbox content area, a precomputed lookup table gives the source luma/chroma byte offsets
 * after **rotation into the upright frame** (see [ImageGeometry] for the convention) and
 * **downsampling** from the analysis resolution. Pixels outside the content are filled with the
 * Ultralytics letterbox grey (114/255). Lookup tables are rebuilt only when the geometry
 * (size, crop, strides, rotation) changes, so steady state performs no allocation.
 *
 * Colour conversion uses full-range BT.601 (JFIF), which is what Android camera HALs emit for
 * YUV_420_888.
 *
 * Not thread-safe; use from one thread at a time.
 */
class ImagePreprocessor(
    val inputSize: Int = DEFAULT_INPUT_SIZE,
    private val lumaLongSide: Int = DEFAULT_LUMA_LONG_SIDE,
) {
    private val pixelCount = inputSize * inputSize
    val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(pixelCount * 3 * 4).order(ByteOrder.nativeOrder())
    private val inputFloats: FloatBuffer = inputBuffer.asFloatBuffer()
    private val staging = FloatArray(pixelCount * 3)

    private var geometryKey: GeometryKey? = null
    private var yLut = IntArray(pixelCount)
    private var uvLut = IntArray(pixelCount)
    private var letterbox: LetterboxParams? = null
    private var lumaLut = IntArray(0)
    private var luma = ByteArray(0)
    private var lumaW = 0
    private var lumaH = 0

    /** Converts a CameraX frame. Does **not** close [image]; the caller owns it. */
    fun process(image: ImageProxy): PreprocessedFrame {
        val planes = image.planes
        val crop = image.cropRect
        val yuv = YuvPlanes(
            y = planes[0].buffer,
            yRowStride = planes[0].rowStride,
            yPixelStride = planes[0].pixelStride,
            u = planes[1].buffer,
            v = planes[2].buffer,
            uvRowStride = planes[1].rowStride,
            uvPixelStride = planes[1].pixelStride,
            width = crop.width(),
            height = crop.height(),
            cropLeft = crop.left,
            cropTop = crop.top,
        )
        return process(yuv, image.imageInfo.rotationDegrees, image.imageInfo.timestamp)
    }

    /** Pure-JVM entry point (used by [process] and by unit tests). */
    fun process(planes: YuvPlanes, rotationDegrees: Int, timestampNs: Long = 0L): PreprocessedFrame {
        val rotation = ImageGeometry.normalizeRotation(rotationDegrees)
        val key = GeometryKey(
            planes.width, planes.height, planes.cropLeft, planes.cropTop,
            planes.yRowStride, planes.yPixelStride, planes.uvRowStride, planes.uvPixelStride, rotation,
        )
        if (key != geometryKey) rebuild(key)
        val lb = letterbox!!

        val y = planes.y
        val u = planes.u
        val v = planes.v
        val out = staging
        val yl = yLut
        val uvl = uvLut
        var o = 0
        for (i in 0 until pixelCount) {
            val yi = yl[i]
            if (yi < 0) {
                out[o] = PAD; out[o + 1] = PAD; out[o + 2] = PAD
            } else {
                val ci = uvl[i]
                val yy = (y.get(yi).toInt() and 0xFF).toFloat()
                val cb = (u.get(ci).toInt() and 0xFF) - 128f
                val cr = (v.get(ci).toInt() and 0xFF) - 128f
                out[o] = clamp01((yy + 1.402f * cr) * INV_255)
                out[o + 1] = clamp01((yy - 0.344136f * cb - 0.714136f * cr) * INV_255)
                out[o + 2] = clamp01((yy + 1.772f * cb) * INV_255)
            }
            o += 3
        }
        inputFloats.clear()
        inputFloats.put(out)
        inputBuffer.rewind()

        val ll = lumaLut
        val lum = luma
        for (i in ll.indices) lum[i] = y.get(ll[i])

        return PreprocessedFrame(
            input = inputBuffer,
            inputSize = inputSize,
            letterbox = lb,
            luma = lum,
            lumaWidth = lumaW,
            lumaHeight = lumaH,
            uprightWidth = lb.srcWidth,
            uprightHeight = lb.srcHeight,
            rotationDegrees = rotation,
            timestampNs = timestampNs,
        )
    }

    private fun rebuild(k: GeometryKey) {
        val (uw, uh) = ImageGeometry.uprightSize(k.width, k.height, k.rotation)
        val lb = LetterboxParams.compute(uw, uh, inputSize)
        val yl = yLut
        val uvl = uvLut
        for (dy in 0 until inputSize) {
            val inY = dy >= lb.padY && dy < lb.padY + lb.contentHeight
            val uy = if (inY) ((dy - lb.padY + 0.5f) / lb.scale).toInt().coerceIn(0, uh - 1) else 0
            for (dx in 0 until inputSize) {
                val idx = dy * inputSize + dx
                if (!inY || dx < lb.padX || dx >= lb.padX + lb.contentWidth) {
                    yl[idx] = -1; uvl[idx] = -1
                    continue
                }
                val ux = ((dx - lb.padX + 0.5f) / lb.scale).toInt().coerceIn(0, uw - 1)
                val p = ImageGeometry.uprightPixelToSensor(ux, uy, k.width, k.height, k.rotation)
                val sx = ImageGeometry.packedX(p) + k.cropLeft
                val sy = ImageGeometry.packedY(p) + k.cropTop
                yl[idx] = sy * k.yRowStride + sx * k.yPixelStride
                uvl[idx] = (sy shr 1) * k.uvRowStride + (sx shr 1) * k.uvPixelStride
            }
        }

        // Upright luma thumbnail: long side ≈ lumaLongSide (never upscaled).
        val f = minOf(1f, lumaLongSide.toFloat() / max(uw, uh))
        lumaW = max(1, (uw * f).roundToInt())
        lumaH = max(1, (uh * f).roundToInt())
        lumaLut = IntArray(lumaW * lumaH)
        luma = ByteArray(lumaW * lumaH)
        for (ty in 0 until lumaH) {
            val uy = ((ty + 0.5f) * uh / lumaH).toInt().coerceIn(0, uh - 1)
            for (tx in 0 until lumaW) {
                val ux = ((tx + 0.5f) * uw / lumaW).toInt().coerceIn(0, uw - 1)
                val p = ImageGeometry.uprightPixelToSensor(ux, uy, k.width, k.height, k.rotation)
                val sx = ImageGeometry.packedX(p) + k.cropLeft
                val sy = ImageGeometry.packedY(p) + k.cropTop
                lumaLut[ty * lumaW + tx] = sy * k.yRowStride + sx * k.yPixelStride
            }
        }
        letterbox = lb
        geometryKey = k
    }

    private data class GeometryKey(
        val width: Int, val height: Int, val cropLeft: Int, val cropTop: Int,
        val yRowStride: Int, val yPixelStride: Int, val uvRowStride: Int, val uvPixelStride: Int,
        val rotation: Int,
    )

    companion object {
        const val DEFAULT_INPUT_SIZE = 320
        const val DEFAULT_LUMA_LONG_SIDE = 240
        /** Ultralytics letterbox fill value (114/255). */
        const val PAD = 114f / 255f
        private const val INV_255 = 1f / 255f

        private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v
    }
}
