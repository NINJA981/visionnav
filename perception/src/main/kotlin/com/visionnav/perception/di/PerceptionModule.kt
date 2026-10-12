package com.visionnav.perception.di

import android.content.Context
import android.util.Log
import com.visionnav.core.vision.FrameFeed
import com.visionnav.perception.camera.CameraSession
import com.visionnav.perception.detect.MockYoloDetector
import com.visionnav.perception.detect.ObjectDetector
import com.visionnav.perception.detect.YoloDetector
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the perception module.
 *
 * - [FrameFeed] → [CameraSession] (the single camera session; inject [CameraSession] directly for
 *   `attachPreview`, `detachPreview`, `setThrottleFactor`).
 * - [ObjectDetector]: the real [YoloDetector] when the model asset is packaged and loads; otherwise
 *   a [MockYoloDetector] with an **empty script** (never fabricates detections) whose
 *   `capabilities.isMock == true` makes the degraded mode visible to UI and logs.
 *
 * The detector is created lazily by [CameraSession] on its inference thread (it injects
 * `dagger.Lazy<ObjectDetector>`), so model loading and GPU warm-up never run on the main thread.
 * The app calls [CameraSession.prewarm] at startup so this happens in the background before the
 * first camera request, without opening the camera.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PerceptionModule {

    @Binds
    abstract fun bindFrameFeed(impl: CameraSession): FrameFeed

    companion object {
        private const val TAG = "PerceptionModule"

        @Provides
        @Singleton
        fun provideObjectDetector(@ApplicationContext context: Context): ObjectDetector = createDetector(context)

        /** Real detector if possible, otherwise the visible, empty mock. Never throws. */
        fun createDetector(context: Context): ObjectDetector {
            if (!YoloDetector.isModelPackaged(context)) {
                Log.w(TAG, "model asset ${YoloDetector.MODEL_ASSET} missing — using MOCK detector (no detections)")
                return MockYoloDetector(modelName = "mock (model missing)")
            }
            return try {
                YoloDetector.create(context)
            } catch (t: Throwable) {
                Log.w(TAG, "YOLO detector failed to load — using MOCK detector (no detections)", t)
                MockYoloDetector(modelName = "mock (model failed to load)")
            }
        }
    }
}
