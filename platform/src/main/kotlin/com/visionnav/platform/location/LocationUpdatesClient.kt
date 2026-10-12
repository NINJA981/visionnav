package com.visionnav.platform.location

import com.visionnav.core.sensing.LocationFix

/**
 * Thin seam over the platform location provider so that [FusedLocationSource]'s subscription,
 * ref-counting and staleness logic can be unit-tested on the JVM with a fake.
 *
 * The production implementation is [GmsLocationUpdatesClient] (Google Play services
 * `FusedLocationProviderClient`).
 */
interface LocationUpdatesClient {

    /** Receives already-mapped fixes and provider availability changes. */
    interface Listener {
        fun onFix(fix: LocationFix)

        /**
         * Provider-level availability (e.g. location switched off in system settings, no
         * satellites and no network). `false` means fixes are unlikely to arrive.
         */
        fun onAvailabilityChanged(available: Boolean)
    }

    /** True if fine or coarse location permission is currently granted. */
    fun hasPermission(): Boolean

    /**
     * Opens the single provider subscription. Must be idempotent-safe to call only once per
     * matching [removeUpdates].
     * @throws SecurityException if the location permission was revoked in the meantime.
     */
    fun requestUpdates(listener: Listener)

    /** Closes the subscription opened by [requestUpdates]. Safe to call when not subscribed. */
    fun removeUpdates()
}
