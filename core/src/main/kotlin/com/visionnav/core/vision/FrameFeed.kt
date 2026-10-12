package com.visionnav.core.vision

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The ONE camera session in the app, exposed as a stream of analysed frames.
 * Consumers register demand by client id; the session runs at the highest demand and
 * is released when every client requests [AnalysisDemand.NONE]. Implemented in :perception.
 */
interface FrameFeed {
    val frames: SharedFlow<FrameObservation>
    val cameraState: StateFlow<CameraState>
    fun setDemand(clientId: String, demand: AnalysisDemand)
    fun setTorch(enabled: Boolean)
}
