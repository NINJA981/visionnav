package com.visionnav.app.ui.aura

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visionnav.app.ui.theme.VisionNavTheme
import kotlin.math.min

/** Horizontal page margin (AURA §05: 16 dp default). */
val ScreenGutter: Dp = 16.dp

enum class ArrowKind { STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, U_TURN, CROSSING, ARRIVE }

/**
 * A direction arrow for the next maneuver: long shaft, broad head. Purely
 * visual (the instruction text carries the meaning), so it has no semantics of its own.
 */
@Composable
fun RoadArrow(kind: ArrowKind, modifier: Modifier = Modifier, color: Color = VisionNavTheme.aura.text) {
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
 * Route progress as a plain line: walked part in [AuraColors.primary] on a divider-coloured track.
 * Exposed to TalkBack as a progress bar with a spoken description.
 */
@Composable
fun ProgressLine(fraction: Float, description: String, modifier: Modifier = Modifier) {
    val aura = VisionNavTheme.aura
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(aura.border)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
            },
    ) {
        Box(Modifier.fillMaxWidth(f).height(6.dp).background(aura.primary))
    }
}

/**
 * "● Word" status marker from AURA §09. The word carries the meaning; the dot repeats it in colour.
 */
@Composable
fun StatusDot(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelLarge, color = VisionNavTheme.aura.text)
    }
}
