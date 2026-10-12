package com.visionnav.platform.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LocationUpdatesClient] backed by Google Play services' [FusedLocationProviderClient].
 *
 * Request: [Priority.PRIORITY_HIGH_ACCURACY], 1 s interval, 500 ms minimum update interval,
 * `setWaitForAccurateLocation(false)` (we publish whatever accuracy we get; consumers judge).
 * Callbacks are delivered on the main looper (they are cheap: map + StateFlow update).
 */
@Singleton
class GmsLocationUpdatesClient @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationUpdatesClient {

    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val lock = Any()
    private var callback: LocationCallback? = null

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Checked by hasPermission(); SecurityException propagates.
    override fun requestUpdates(listener: LocationUpdatesClient.Listener) {
        if (!hasPermission()) throw SecurityException("Location permission not granted")
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // Deliver every location in the batch in order; the last one wins in the state.
                for (location in result.locations) {
                    LocationMapper.toFix(location, SystemClock.elapsedRealtime())
                        ?.let(listener::onFix)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                listener.onAvailabilityChanged(availability.isLocationAvailable)
            }
        }
        synchronized(lock) {
            callback?.let { client.removeLocationUpdates(it) }
            callback = cb
        }
        client.requestLocationUpdates(REQUEST, cb, Looper.getMainLooper())
    }

    override fun removeUpdates() {
        val cb = synchronized(lock) { callback.also { callback = null } } ?: return
        client.removeLocationUpdates(cb)
    }

    private companion object {
        val REQUEST: LocationRequest =
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                .setMinUpdateIntervalMillis(500L)
                .setWaitForAccurateLocation(false)
                .build()
    }
}
