package com.visionnav.platform.common

/**
 * Thread-safe reference counter used by every shared hardware subscription in :platform
 * (location, sensors, power listeners).
 *
 * Several independent callers may call [acquire]/[release] (mirroring `start()`/`stop()`);
 * the underlying resource is opened exactly once on the first [acquire] ([onFirstAcquire]) and
 * closed exactly once when the last holder calls [release] ([onLastRelease]).
 *
 * Unbalanced [release] calls (more releases than acquires) are ignored rather than driving the
 * count negative, so a buggy caller can never close a subscription another caller still holds
 * more than once, nor prevent a later [acquire] from re-opening it.
 */
class RefCounter(
    private val onFirstAcquire: () -> Unit,
    private val onLastRelease: () -> Unit,
) {
    private val lock = Any()
    private var holders = 0

    /** Current number of holders. */
    val count: Int get() = synchronized(lock) { holders }

    /** Whether at least one holder is active. */
    val isActive: Boolean get() = count > 0

    /** Registers a holder; opens the resource if this is the first one. */
    fun acquire() {
        synchronized(lock) {
            holders++
            if (holders == 1) onFirstAcquire()
        }
    }

    /**
     * Releases a holder; closes the resource if this was the last one.
     * @return false if the call was unbalanced (no holders) and therefore ignored.
     */
    fun release(): Boolean {
        synchronized(lock) {
            if (holders == 0) return false
            holders--
            if (holders == 0) onLastRelease()
            return true
        }
    }
}
