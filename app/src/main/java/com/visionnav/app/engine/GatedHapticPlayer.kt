package com.visionnav.app.engine

import com.visionnav.core.announce.HapticPattern
import com.visionnav.core.announce.HapticPlayer
import com.visionnav.platform.haptics.VibratorHapticPlayer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the user's "haptics on/off" setting. CRITICAL patterns always vibrate: a user who turned
 * routine haptics off must still feel an imminent-hazard alert.
 */
@Singleton
class GatedHapticPlayer @Inject constructor(private val delegate: VibratorHapticPlayer) : HapticPlayer {
    @Volatile var enabled: Boolean = true

    override fun play(pattern: HapticPattern) {
        if (enabled || pattern == HapticPattern.CRITICAL) delegate.play(pattern)
    }
}
