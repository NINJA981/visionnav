package com.visionnav.app

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.visionnav.app.debug.CameraDebugOverlay
import com.visionnav.app.engine.EngineEffect
import com.visionnav.app.engine.SessionLog
import com.visionnav.app.engine.VisionNavEngine
import com.visionnav.app.ui.VisionNavAppRoot
import com.visionnav.perception.camera.CameraSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var engine: VisionNavEngine
    @Inject lateinit var cameraSession: CameraSession
    @Inject lateinit var sessionLog: SessionLog

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VisionNavAppRoot(debugOverlayContent = { CameraDebugOverlay(engine, cameraSession, sessionLog) })
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.effects.collect { effect ->
                    if (effect is EngineEffect.LaunchIntent) {
                        try {
                            startActivity(effect.intent)
                        } catch (e: ActivityNotFoundException) {
                            Log.w("MainActivity", "No activity for engine intent", e)
                        }
                    }
                }
            }
        }
    }
}
