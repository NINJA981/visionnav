package com.visionnav.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.visionnav.app.MainActivity
import com.visionnav.app.R
import com.visionnav.app.engine.SessionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground service that keeps location, camera and speech running while the screen is off during
 * an active session (navigation, crossing assist, exploring). It owns no logic: the engine is a
 * process singleton; this service only keeps the process in the foreground and shows the ongoing
 * notification. It is stopped as soon as the session ends, so the camera never runs in the
 * background without a session.
 */
class VisionNavService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE)?.let { runCatching { SessionMode.valueOf(it) }.getOrNull() } ?: SessionMode.NAVIGATING
        ensureChannel()
        val notification = buildNotification(mode)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, foregroundTypes())
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            // E.g. ForegroundServiceStartNotAllowedException or missing permission for a declared type.
            Log.w(TAG, "Could not enter foreground; session continues while the app is visible", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Only claim the foreground types whose runtime permission is granted (required on API 34+). */
    private fun foregroundTypes(): Int {
        var types = 0
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && granted(Manifest.permission.CAMERA)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return if (types == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else types
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.service_channel_description)
                    setSound(null, null)
                },
            )
        }
    }

    private fun buildNotification(mode: SessionMode): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (mode) {
            SessionMode.NAVIGATING -> getString(R.string.service_text_navigating)
            SessionMode.CROSSING_ASSIST -> getString(R.string.service_text_crossing)
            SessionMode.EXPLORING -> getString(R.string.service_text_exploring)
            SessionMode.IDLE -> getString(R.string.service_text_navigating)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "VisionNavService"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 42
        const val EXTRA_MODE = "mode"
    }
}

/** Starts/stops [VisionNavService] in step with the engine's session mode. */
@Singleton
class SessionServiceController @Inject constructor(@ApplicationContext private val context: Context) {
    @Volatile private var running = false

    fun start(mode: SessionMode) {
        val intent = Intent(context, VisionNavService::class.java).putExtra(VisionNavService.EXTRA_MODE, mode.name)
        try {
            ContextCompat.startForegroundService(context, intent)
            running = true
        } catch (e: RuntimeException) {
            // Starting from the background is restricted on API 31+; the session still runs while visible.
            Log.w("SessionService", "Foreground service start refused", e)
        }
    }

    fun stop() {
        if (!running) return
        context.stopService(Intent(context, VisionNavService::class.java))
        running = false
    }
}
