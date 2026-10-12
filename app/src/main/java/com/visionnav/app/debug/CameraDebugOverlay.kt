package com.visionnav.app.debug

import android.content.Context
import android.content.Intent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.visionnav.app.engine.SessionLog
import com.visionnav.app.engine.VisionNavEngine
import com.visionnav.core.tracking.ApproachState
import com.visionnav.core.tracking.ImageMotion
import com.visionnav.core.vision.CameraState
import com.visionnav.perception.camera.CameraSession
import java.io.File
import java.util.Locale

/**
 * Developer/field-test overlay: live camera preview with track boxes, IDs and motion labels.
 * Preview frames are attached only while this composable is visible. It never creates camera
 * demand on its own, so frames appear only during an active session.
 */
@Composable
fun BoxScope.CameraDebugOverlay(engine: VisionNavEngine, cameraSession: CameraSession, log: SessionLog) {
    val state by engine.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    DisposableEffect(previewView) {
        cameraSession.attachPreview(previewView.surfaceProvider)
        onDispose { cameraSession.detachPreview() }
    }
    val measurer = rememberTextMeasurer()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().semantics {
                contentDescription = "Debug overlay, ${state.debugTracks.size} tracked objects"
            },
        ) {
            for (t in state.debugTracks) {
                val color = when {
                    t.approach == ApproachState.APPROACHING -> Color(0xFFFF5252)
                    t.imageMotion == ImageMotion.MOVING_LEFT_IN_IMAGE || t.imageMotion == ImageMotion.MOVING_RIGHT_IN_IMAGE -> Color(0xFFFFC400)
                    else -> Color(0xFF69F0AE)
                }
                val tl = Offset(t.box.left * size.width, t.box.top * size.height)
                drawRect(color, tl, Size(t.box.width * size.width, t.box.height * size.height), style = Stroke(3.dp.toPx()))
                val motion = when (t.imageMotion) {
                    ImageMotion.MOVING_LEFT_IN_IMAGE -> "<-"
                    ImageMotion.MOVING_RIGHT_IN_IMAGE -> "->"
                    ImageMotion.STATIONARY_OR_LOW_MOTION -> "."
                    ImageMotion.UNKNOWN -> "?"
                }
                val label = String.format(Locale.US, "#%d %s %.2f %s %s", t.id, t.objectClass.label, t.confidence, motion, t.approach.name.lowercase())
                drawText(
                    measurer,
                    label,
                    topLeft = Offset(tl.x, (tl.y - 22.sp.toPx()).coerceAtLeast(0f)),
                    style = TextStyle(color = color, fontSize = 13.sp, background = Color(0xAA000000)),
                )
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xCC000000)).padding(8.dp)) {
            val cam = state.camera
            val caps = (cam as? CameraState.Running)?.capabilities
            val demo = if (caps?.isMock == true) " (DEMO)" else ""
            Text("Camera: ${cam::class.simpleName}  Detector: ${caps?.modelName ?: "-"} ${caps?.accelerator ?: ""}$demo", color = Color.White, fontSize = 14.sp)
            val fov = state.horizontalFovDeg?.let { String.format(Locale.US, "%.0f deg", it) } ?: "unknown"
            Text("HFOV: $fov  Quality: ${state.qualityIssues.joinToString().ifEmpty { "ok" }}", color = Color.White, fontSize = 14.sp)
            val o = state.orientation
            val bearing = o?.cameraBearingDeg?.let { String.format(Locale.US, "%.0f deg", it) } ?: ""
            Text("Orientation: ${o?.reliability ?: "-"} $bearing  Crossing: ${state.crossingState}", color = Color.White, fontSize = 14.sp)
            Button(onClick = { shareLog(context, log) }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Export session log")
            }
        }
    }
}

private fun shareLog(context: Context, log: SessionLog) {
    val file = log.exportTo(File(context.cacheDir, "logs"))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Export session log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
