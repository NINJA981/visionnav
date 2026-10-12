package com.visionnav.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.visionnav.app.ui.VisionNavAppRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Integration: pass debugOverlayContent = { CameraDebugOverlay(...) } to fill the debug slot.
            VisionNavAppRoot()
        }
    }
}
