package com.visionnav.perception.quality

import com.visionnav.core.vision.FrameQuality
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Image-quality metrics from a luma (Y) plane, used for visibility assistance (Phase 5B).
 *
 * The plane is point-sampled onto a grid whose long side is ≈ [gridLongSide] cells (≈ 80×60 for
 * 4:3 frames), keeping cost to a few thousand samples per frame. Point sampling (not averaging)
 * preserves high-frequency content so the Laplacian still reflects focus/motion blur.
 *
 * Metrics (see [FrameQuality]):
 * - `meanLuma`: mean of samples (0..255);
 * - `darkRatio`: fraction of samples with luma < [DARK_LUMA];
 * - `glareRatio`: fraction of samples with luma > [GLARE_LUMA];
 * - `sharpness`: variance of the 4-neighbour Laplacian over the grid interior (grid-scale
 *   dependent; thresholds in `FrameQualityThresholds` assume the default grid);
 * - `lumaStdDev`: standard deviation of samples (near zero ⇒ covered lens / blank scene).
 *
 * Pure function; orientation does not affect any metric. Not thread-safe (reuses a scratch grid).
 */
class FrameQualityAnalyzer(private val gridLongSide: Int = 80) {

    private var grid = FloatArray(0)

    /**
     * @param luma luma bytes (unsigned).
     * @param width image width in pixels.
     * @param height image height in pixels.
     * @param rowStride bytes per row (≥ width).
     * @param pixelStride bytes between horizontally adjacent pixels.
     */
    fun analyze(luma: ByteArray, width: Int, height: Int, rowStride: Int = width, pixelStride: Int = 1): FrameQuality {
        require(width > 0 && height > 0) { "empty image" }
        require(rowStride >= width * pixelStride - (pixelStride - 1)) { "rowStride too small" }
        val step = max(1, ceil(max(width, height).toDouble() / gridLongSide).toInt())
        val gw = (width + step - 1) / step
        val gh = (height + step - 1) / step
        if (grid.size < gw * gh) grid = FloatArray(gw * gh)
        val g = grid

        var sum = 0.0
        var sumSq = 0.0
        var dark = 0
        var glare = 0
        for (gy in 0 until gh) {
            val row = gy * step * rowStride
            for (gx in 0 until gw) {
                val v = luma[row + gx * step * pixelStride].toInt() and 0xFF
                g[gy * gw + gx] = v.toFloat()
                sum += v
                sumSq += v.toDouble() * v
                if (v < DARK_LUMA) dark++
                if (v > GLARE_LUMA) glare++
            }
        }
        val n = gw * gh
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)

        return FrameQuality(
            meanLuma = mean.toFloat(),
            darkRatio = dark.toFloat() / n,
            glareRatio = glare.toFloat() / n,
            sharpness = laplacianVariance(g, gw, gh),
            lumaStdDev = sqrt(variance).toFloat(),
        )
    }

    companion object {
        const val DARK_LUMA = 40
        const val GLARE_LUMA = 245

        /** Variance of the 4-neighbour Laplacian over the interior of a [w]×[h] grid. 0 if too small. */
        fun laplacianVariance(g: FloatArray, w: Int, h: Int): Float {
            if (w < 3 || h < 3) return 0f
            var s = 0.0
            var s2 = 0.0
            var count = 0
            for (y in 1 until h - 1) {
                val r = y * w
                for (x in 1 until w - 1) {
                    val i = r + x
                    val lap = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4f * g[i]).toDouble()
                    s += lap
                    s2 += lap * lap
                    count++
                }
            }
            val m = s / count
            return (s2 / count - m * m).coerceAtLeast(0.0).toFloat()
        }
    }
}
