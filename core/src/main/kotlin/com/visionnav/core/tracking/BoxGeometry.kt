package com.visionnav.core.tracking

import com.visionnav.core.vision.NormBox
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Small geometry helpers on [NormBox] shared by filtering, tracking and the safety layer. */
object BoxGeometry {

    /** Intersection-over-union in [0, 1]. Degenerate boxes yield 0. */
    fun iou(a: NormBox, b: NormBox): Float {
        val ix = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        val iy = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
        val inter = ix * iy
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else (inter / union).coerceIn(0f, 1f)
    }

    /** Euclidean distance between box centres, in normalised image units. */
    fun centerDistance(a: NormBox, b: NormBox): Float = hypot(a.centerX - b.centerX, a.centerY - b.centerY)

    fun diagonal(b: NormBox): Float = hypot(b.width.coerceAtLeast(0f), b.height.coerceAtLeast(0f))

    /** Translates [b] by ([dx], [dy]) without clamping (predictions may leave the frame). */
    fun shift(b: NormBox, dx: Float, dy: Float): NormBox = NormBox(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy)

    /** Clamps [b] to the unit square. */
    fun clip(b: NormBox): NormBox = NormBox(
        b.left.coerceIn(0f, 1f),
        b.top.coerceIn(0f, 1f),
        b.right.coerceIn(0f, 1f),
        b.bottom.coerceIn(0f, 1f),
    )

    fun isFinite(b: NormBox): Boolean = b.left.isFinite() && b.top.isFinite() && b.right.isFinite() && b.bottom.isFinite()
}
