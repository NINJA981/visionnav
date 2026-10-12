package com.visionnav.platform.common

import android.os.SystemClock
import com.visionnav.core.common.TimeSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TimeSource] on the same clock base as every timestamp produced by :platform
 * (`LocationFix.elapsedMs`, `DeviceOrientation.elapsedMs`): [SystemClock.elapsedRealtime],
 * which is monotonic and keeps counting during deep sleep.
 *
 * `core.common.SystemTimeSource` uses `System.nanoTime()`, which on Android stops during deep
 * sleep, so ages computed by mixing the two clocks can be wrong. The app should bind
 * `TimeSource` to this class (it is intentionally not bound in `PlatformModule`, which does not
 * own the `TimeSource` binding).
 */
@Singleton
class ElapsedRealtimeTimeSource @Inject constructor() : TimeSource {
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
    override fun wallMs(): Long = System.currentTimeMillis()
}
