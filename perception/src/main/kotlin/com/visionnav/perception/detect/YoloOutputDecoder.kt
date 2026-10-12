package com.visionnav.perception.detect

import com.visionnav.core.vision.Detection
import com.visionnav.core.vision.NormBox
import com.visionnav.core.vision.ObjectClass
import com.visionnav.perception.preprocess.LetterboxParams

/** Units of the box coordinates (cx, cy, w, h) in the raw YOLO head output. */
enum class BoxUnits {
    /**
     * Model-input pixels (0..inputSize). PyTorch / ONNX heads, and the shipped
     * `yolov8n_320_fp16.tflite` (Ultralytics 8.4.146 onnx2tf export — verified: box channels span
     * ≈ 3..317 on `bus.jpg`; see `tools/model/README.md`).
     */
    PIXELS,

    /**
     * Normalised to the model input (0..1). Older Ultralytics `format="tflite"` exports normalised
     * the box head; kept for models produced that way.
     */
    NORMALIZED,
}

/**
 * Decodes the raw YOLOv8 detection head (no built-in NMS) into [Detection]s in **normalised upright
 * image coordinates**.
 *
 * Accepted layouts (batch must be 1):
 * - `[1, 4 + C, N]` — Ultralytics default (channels first), e.g. `[1, 84, 2100]` for 320 px input;
 * - `[1, N, 4 + C]` — transposed. The layout is inferred from the shape: if exactly one non-batch
 *   dimension equals `4 + [expectedClasses]` it is the box/class axis; otherwise the smaller one is.
 *
 * Per anchor: class = argmax over the C class scores (already sigmoid-activated in YOLOv8),
 * candidate kept when that score ≥ [confThreshold]. Boxes are `cx, cy, w, h`, converted to corners,
 * mapped back through [LetterboxParams] (undoing scale + padding), normalised and clamped to [0, 1].
 * Classes that [classMapper] does not map (COCO classes the app does not reason about) are dropped
 * **before** NMS. Class-wise greedy NMS at [iouThreshold] then keeps at most [maxDetections],
 * highest confidence first.
 *
 * Pure Kotlin; allocation is limited to the candidate list.
 */
class YoloOutputDecoder(
    val confThreshold: Float = 0.25f,
    val iouThreshold: Float = 0.45f,
    val maxDetections: Int = 50,
    val boxUnits: BoxUnits = BoxUnits.PIXELS,
    /** Upper bound on candidates entering NMS (highest scores kept) to bound worst-case cost. */
    val maxCandidates: Int = 300,
    /** Expected class count (80 for COCO) used to disambiguate the layout; null = smaller axis. */
    val expectedClasses: Int? = 80,
    private val classMapper: (Int) -> ObjectClass? = ObjectClass::fromCocoId,
) {
    /** Resolved tensor layout. */
    data class Layout(val channelsFirst: Boolean, val numAnchors: Int, val numClasses: Int)

    fun layoutOf(shape: IntArray): Layout {
        require(shape.size == 3 && shape[0] == 1) { "expected [1, A, B] output, got ${shape.contentToString()}" }
        val a = shape[1]
        val b = shape[2]
        val expected = expectedClasses?.plus(4)
        val channelsFirst = when {
            expected != null && a == expected && b != expected -> true
            expected != null && b == expected && a != expected -> false
            else -> a <= b
        }
        val channels = if (channelsFirst) a else b
        require(channels > 4) { "need 4 box + ≥1 class channels, got $channels" }
        return Layout(channelsFirst, if (channelsFirst) b else a, channels - 4)
    }

    /**
     * @param output flat tensor data in row-major order matching [shape].
     * @param letterbox geometry used to build the model input for this frame.
     */
    fun decode(output: FloatArray, shape: IntArray, letterbox: LetterboxParams): List<Detection> {
        val layout = layoutOf(shape)
        val n = layout.numAnchors
        val c = layout.numClasses
        require(output.size >= n * (c + 4)) { "output too small: ${output.size} < ${n * (c + 4)}" }
        val unit = if (boxUnits == BoxUnits.NORMALIZED) letterbox.dstSize.toFloat() else 1f

        // Index helper: value of channel `ch` for anchor `i`.
        fun at(i: Int, ch: Int): Float =
            if (layout.channelsFirst) output[ch * n + i] else output[i * (c + 4) + ch]

        val candidates = ArrayList<Candidate>()
        for (i in 0 until n) {
            var best = -1
            var bestScore = confThreshold
            for (k in 0 until c) {
                val s = at(i, 4 + k)
                if (s >= bestScore) { bestScore = s; best = k }
            }
            if (best < 0) continue
            val cls = classMapper(best) ?: continue
            val cx = at(i, 0) * unit
            val cy = at(i, 1) * unit
            val hw = at(i, 2) * unit / 2f
            val hh = at(i, 3) * unit / 2f
            val box = NormBox(
                left = clamp01(letterbox.inputXToUprightNorm(cx - hw)),
                top = clamp01(letterbox.inputYToUprightNorm(cy - hh)),
                right = clamp01(letterbox.inputXToUprightNorm(cx + hw)),
                bottom = clamp01(letterbox.inputYToUprightNorm(cy + hh)),
            )
            if (box.width <= 0f || box.height <= 0f) continue
            candidates += Candidate(cls, bestScore, box)
        }
        candidates.sortByDescending { it.score }
        val limited = if (candidates.size > maxCandidates) candidates.subList(0, maxCandidates) else candidates
        return Nms.classWise(limited.map { Detection(it.cls, it.score, it.box) }, iouThreshold, maxDetections)
    }

    private class Candidate(val cls: ObjectClass, val score: Float, val box: NormBox)

    private companion object {
        fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
    }
}

/** Non-maximum suppression helpers. */
object Nms {
    fun iou(a: NormBox, b: NormBox): Float {
        val iw = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val ih = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * Greedy per-class NMS: within each [com.visionnav.core.vision.ObjectClass], a detection is
     * suppressed if it overlaps a higher-confidence kept detection with IoU > [iouThreshold].
     * Result sorted by confidence (descending) and truncated to [maxDetections].
     */
    fun classWise(detections: List<Detection>, iouThreshold: Float, maxDetections: Int): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }
        val kept = ArrayList<Detection>(minOf(sorted.size, maxDetections))
        for (d in sorted) {
            if (kept.size >= maxDetections) break
            val suppressed = kept.any { it.objectClass == d.objectClass && iou(it.box, d.box) > iouThreshold }
            if (!suppressed) kept += d
        }
        return kept
    }
}
