package com.visionnav.app

import android.app.Application
import com.visionnav.perception.camera.CameraSession
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VisionNavApp : Application() {

    @Inject lateinit var cameraSession: CameraSession

    override fun onCreate() {
        super.onCreate()
        // Load + warm up the object detector in the background (seconds of GPU init on some phones),
        // so the first "What's around" / traffic check is not eaten by it. Returns immediately;
        // does not open the camera or register camera demand.
        cameraSession.prewarm()
    }
}
