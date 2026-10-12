package com.visionnav.app.ui.kerb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visionnav.app.ui.theme.VisionNavTheme
import kotlin.math.min

/** Horizontal padding of screen content; [bleed] cancels it so road paint reaches the screen edge. */
val ScreenGutter: Dp = 16.dp

/** Lets a child ignore its parent's horizontal padding, so zebra bars run edge to edge like paint. */
fun Modifier.bleed(gutter: Dp = ScreenGutter): Modifier = layout { measurable, constraints ->
    val extra = gutter.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth + extra).coerceAtLeast(0),
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
        ),
    )
    val width = (placeable.width - extra).coerceAtLeast(0)
    layout(width, placeable.height) { placeable.place(-gutter.roundToPx(), 0) }
}

/**
 * The voice pad: a field of tactile-paving blisters, the one bold element on Home. Tapping
 * anywhere starts listening; while listening the blisters turn to solid paint so the change is
 * visible without relying on colour alone (the label also changes).
 */
@Composable
fun TactilePad(
    label: String,
    hint: String?,
    listening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stateText: String? = null,
) {
    val k = VisionNavTheme.kerb
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(20.dp)
    val fill = if (listening) k.bar else k.amber
    val ink = if (listening) k.onBar else k.onAmber
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp)
            .clip(shape)
            .background(fill)
            .then(if (focused) Modifier.border(6.dp, k.onGround, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = hint, onClick = onClick)
            .semantics { if (stateText != null) stateDescription = stateText },
    ) {
        Canvas(Modifier.matchParentSize()) {
            // Keep a clear strip for the label so no dome ever sits behind a letter.
            drawBlisters(ink.copy(alpha = if (pressed) 0.30f else 0.16f), solid = listening, reserveBottom = 104.dp.toPx())
        }
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            Text(label, style = MaterialTheme.typography.displayMedium, color = ink)
        }
    }
}

/** Square grid of whole domes, 8 per row like a standard blister tile, centred in the free area. */
private fun DrawScope.drawBlisters(color: Color, solid: Boolean, reserveBottom: Float) {
    val pitch = size.width / 8f
    val r = pitch * 0.28f
    val usable = size.height - reserveBottom
    val rows = (usable / pitch).toInt()
    if (rows <= 0) return
    val top = (usable - rows * pitch) / 2f
    for (row in 0 until rows) {
        val y = top + pitch * (row + 0.5f)
        for (col in 0 until 8) {
            val c = Offset(pitch * (col + 0.5f), y)
            if (solid) drawCircle(color, r, c) else drawCircle(color, r, c, style = Stroke(width = r * 0.42f))
        }
    }
}

enum class ArrowKind { STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, U_TURN, CROSSING, ARRIVE }

/**
 * A painted road arrow: long shaft, broad head, like the turn arrows on a carriageway. Purely
 * visual (the instruction text carries the meaning), so it has no semantics of its own.
 */
@Composable
fun RoadArrow(kind: ArrowKind, modifier: Modifier = Modifier, color: Color = VisionNavTheme.kerb.onGround) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val s = min(size.width, size.height)
        val cx = size.width / 2
        val shaft = s * 0.16f
        val head = s * 0.44f
        when (kind) {
            ArrowKind.ARRIVE -> {
                drawCircle(color, s * 0.30f, Offset(cx, size.height / 2), style = Stroke(s * 0.10f))
                drawCircle(color, s * 0.10f, Offset(cx, size.height / 2))
            }
            ArrowKind.CROSSING -> {
                // Zebra stripes seen from the kerb.
                val bars = 5
                val h = size.height / (bars * 2 - 1)
                for (i in 0 until bars) {
                    drawRect(color, Offset(size.width * 0.12f, i * 2 * h), androidx.compose.ui.geometry.Size(size.width * 0.76f, h))
                }
            }
            ArrowKind.U_TURN -> {
                val p = Path().apply {
                    moveTo(cx + s * 0.18f, size.height)
                    lineTo(cx + s * 0.18f, size.height * 0.35f)
                    cubicTo(cx + s * 0.18f, size.height * 0.05f, cx - s * 0.18f, size.height * 0.05f, cx - s * 0.18f, size.height * 0.35f)
                    lineTo(cx - s * 0.18f, size.height * 0.62f)
                }
                drawPath(p, color, style = Stroke(shaft))
                drawHead(Offset(cx - s * 0.18f, size.height * 0.88f), head * 0.8f, 180f, color)
            }
            else -> {
                val angle = when (kind) {
                    ArrowKind.SLIGHT_LEFT -> -35f
                    ArrowKind.LEFT -> -90f
                    ArrowKind.SHARP_LEFT -> -135f
                    ArrowKind.SLIGHT_RIGHT -> 35f
                    ArrowKind.RIGHT -> 90f
                    ArrowKind.SHARP_RIGHT -> 135f
                    else -> 0f
                }
                val base = Offset(cx, size.height)
                val bend = Offset(cx, size.height * 0.48f)
                drawLine(color, base, bend, strokeWidth = shaft)
                val rad = Math.toRadians(angle.toDouble())
                val len = size.height * 0.30f
                val tip = Offset(bend.x + (len * kotlin.math.sin(rad)).toFloat(), bend.y - (len * kotlin.math.cos(rad)).toFloat())
                if (angle != 0f) {
                    drawLine(color, bend, tip, strokeWidth = shaft)
                    drawCircle(color, shaft / 2, bend)
                }
                drawHead(if (angle == 0f) Offset(cx, size.height * 0.30f) else tip, head, angle, color)
            }
        }
    }
}

private fun DrawScope.drawHead(tip: Offset, size: Float, angleDeg: Float, color: Color) {
    rotate(angleDeg, pivot = tip) {
        val p = Path().apply {
            moveTo(tip.x, tip.y - size * 0.55f)
            lineTo(tip.x + size / 2, tip.y + size * 0.25f)
            lineTo(tip.x - size / 2, tip.y + size * 0.25f)
            close()
        }
        drawPath(p, color)
    }
}

/**
 * Route progress as a dashed centre line: walked dashes are solid paint, the rest are worn.
 * Exposed to TalkBack as a progress bar with a spoken description.
 */
@Composable
fun LaneProgress(fraction: Float, description: String, modifier: Modifier = Modifier) {
    val k = VisionNavTheme.kerb
    Canvas(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
            },
    ) {
        val dash = size.height * 3.2f
        val gap = size.height * 1.6f
        var x = 0f
        val done = size.width * fraction.coerceIn(0f, 1f)
        while (x < size.width) {
            val w = min(dash, size.width - x)
            drawRect(
                if (x < done) k.onGround else k.kerb,
                Offset(x, 0f),
                androidx.compose.ui.geometry.Size(w, size.height),
            )
            x += dash + gap
        }
    }
}

/** Chevron hazard edge (like a bend-warning sign), drawn down the leading side of alerts. */
@Composable
fun ChevronEdge(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val step = size.width * 1.1f
        var y = -step
        while (y < size.height + step) {
            val p = Path().apply {
                moveTo(size.width * 0.15f, y)
                lineTo(size.width * 0.85f, y + step / 2)
                lineTo(size.width * 0.15f, y + step)
            }
            drawPath(p, color, style = Stroke(width = size.width * 0.28f))
            y += step * 0.9f
        }
    }
}
