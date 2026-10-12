package com.visionnav.platform.location

import android.os.SystemClock
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.sensing.LocationFix
import com.visionnav.core.sensing.LocationSource
import com.visionnav.core.sensing.LocationState
import com.visionnav.platform.common.RefCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's single shared location subscription.
 *
 * - **Ref-counted**: any number of components may call [start]/[stop]; exactly one provider
 *   subscription is open while at least one caller holds it.
 * - **Honest state**: [state] is [LocationState.Available] only while a real, recent fix exists.
 *   It becomes `Unavailable(GPS_UNAVAILABLE)` when the permission is missing or revoked, when the
 *   provider reports it cannot deliver locations (location switched off, no signal), when no fix
 *   has arrived for more than [STALE_AFTER_MS], and whenever the source is stopped.
 * - **No accuracy gating**: a fix with poor accuracy (e.g. 80 m) is still `Available`; its
 *   `accuracyM` lets consumers decide (e.g. emit `GPS_LOW_ACCURACY`). Nothing is ever fabricated,
 *   interpolated or carried forward past [STALE_AFTER_MS].
 * - **Permission recovery**: while started, the watchdog re-checks the permission every
 *   [WATCHDOG_PERIOD_MS], so granting it later starts updates without a restart; revoking it
 *   closes the subscription.
 *
 * Timestamps (`LocationFix.elapsedMs`) are on the [SystemClock.elapsedRealtime] base.
 */
@Singleton
class FusedLocationSource internal constructor(
    private val client: LocationUpdatesClient,
    private val elapsedRealtimeMs: () -> Long,
    private val scope: CoroutineScope,
) : LocationSource {

    @Inject
    constructor(client: LocationUpdatesClient) : this(
        client = client,
        elapsedRealtimeMs = SystemClock::elapsedRealtime,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val _state = MutableStateFlow<LocationState>(UNAVAILABLE)
    override val state: StateFlow<LocationState> = _state.asStateFlow()

    private val lock = Any()
    private var active = false
    private var subscribed = false
    private var generation = 0
    private var lastFix: LocationFix? = null
    private var watchdog: Job? = null

    private val refCounter = RefCounter(onFirstAcquire = ::open, onLastRelease = ::close)

    /** Number of current holders (for diagnostics/tests). */
    val holders: Int get() = refCounter.count

    override fun start() = refCounter.acquire()

    override fun stop() {
        refCounter.release()
    }

    private fun open() {
        synchronized(lock) {
            active = true
            lastFix = null
            _state.value = UNAVAILABLE
            trySubscribeLocked()
        }
        watchdog = scope.launch {
            while (isActive) {
                delay(WATCHDOG_PERIOD_MS)
                checkHealth()
            }
        }
    }

    private fun close() {
        watchdog?.cancel()
        watchdog = null
        synchronized(lock) {
            active = false
            unsubscribeLocked()
            lastFix = null
            _state.value = UNAVAILABLE
        }
    }

    private fun trySubscribeLocked() {
        if (!active || subscribed) return
        if (!client.hasPermission()) {
            _state.value = UNAVAILABLE
            return
        }
        val myGeneration = ++generation
        try {
            client.requestUpdates(object : LocationUpdatesClient.Listener {
                override fun onFix(fix: LocationFix) = handleFix(myGeneration, fix)
                override fun onAvailabilityChanged(available: Boolean) =
                    handleAvailability(myGeneration, available)
            })
            subscribed = true
        } catch (_: SecurityException) {
            subscribed = false
            _state.value = UNAVAILABLE
        }
    }

    private fun unsubscribeLocked() {
        if (!subscribed) return
        subscribed = false
        generation++ // Late callbacks from the old subscription are ignored.
        client.removeUpdates()
    }

    private fun handleFix(fromGeneration: Int, fix: LocationFix) {
        synchronized(lock) {
            if (!active || fromGeneration != generation) return
            // A cached fix delivered at subscription time may already be old: never present it
            // as current.
            if (isStale(fix)) return
            lastFix = fix
            _state.value = LocationState.Available(fix)
        }
    }

    private fun handleAvailability(fromGeneration: Int, available: Boolean) {
        synchronized(lock) {
            if (!active || fromGeneration != generation) return
            if (!available) {
                lastFix = null
                _state.value = UNAVAILABLE
            }
            // available == true: wait for an actual fix before reporting Available.
        }
    }

    /** Runs every [WATCHDOG_PERIOD_MS] while started. Internal for tests. */
    internal fun checkHealth() {
        synchronized(lock) {
            if (!active) return
            if (subscribed && !client.hasPermission()) {
                unsubscribeLocked()
                lastFix = null
                _state.value = UNAVAILABLE
                return
            }
            if (!subscribed) trySubscribeLocked()
            val fix = lastFix
            if (fix != null && isStale(fix)) {
                lastFix = null
                _state.value = UNAVAILABLE
            }
        }
    }

    private fun isStale(fix: LocationFix): Boolean =
        elapsedRealtimeMs() - fix.elapsedMs > STALE_AFTER_MS

    companion object {
        /** No fix for longer than this → `Unavailable(GPS_UNAVAILABLE)`. */
        const val STALE_AFTER_MS = 10_000L

        /** Staleness / permission re-check period. */
        const val WATCHDOG_PERIOD_MS = 1_000L

        private val UNAVAILABLE = LocationState.Unavailable(UncertaintyReason.GPS_UNAVAILABLE)
    }
}
