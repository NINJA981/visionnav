package com.visionnav.platform.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.visionnav.platform.R

/** User-facing features that need runtime permissions. */
enum class Feature {
    NAVIGATION,
    CAMERA,
    VOICE,
    ACTIVITY,
    SOS,
    NOTIFICATIONS,
}

/**
 * Which runtime permissions each [Feature] needs, the spoken rationale for asking, and which are
 * still missing. Permissions that do not exist on the running API level are omitted (e.g.
 * `ACTIVITY_RECOGNITION` below API 29, `POST_NOTIFICATIONS` below API 33).
 *
 * Declarations: location, `RECORD_AUDIO`, `ACTIVITY_RECOGNITION`, `SEND_SMS` and `VIBRATE` are in
 * the :platform manifest; `CAMERA` (perception) and `POST_NOTIFICATIONS` (app foreground service)
 * are declared by their owning modules.
 */
object PermissionCatalog {

    /**
     * Runtime permissions [feature] needs on [sdkInt]. For NAVIGATION both fine and coarse are
     * listed (Android requires requesting them together); navigation needs fine to be useful.
     */
    fun permissionsFor(feature: Feature, sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        when (feature) {
            Feature.NAVIGATION -> listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            Feature.CAMERA -> listOf(Manifest.permission.CAMERA)
            Feature.VOICE -> listOf(Manifest.permission.RECORD_AUDIO)
            Feature.ACTIVITY ->
                if (sdkInt >= Build.VERSION_CODES.Q) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()
            Feature.SOS -> listOf(Manifest.permission.SEND_SMS)
            Feature.NOTIFICATIONS ->
                if (sdkInt >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        }

    /** String resource of the short spoken rationale for [feature]. */
    @StringRes
    fun rationaleRes(feature: Feature): Int = when (feature) {
        Feature.NAVIGATION -> R.string.vn_rationale_navigation
        Feature.CAMERA -> R.string.vn_rationale_camera
        Feature.VOICE -> R.string.vn_rationale_voice
        Feature.ACTIVITY -> R.string.vn_rationale_activity
        Feature.SOS -> R.string.vn_rationale_sos
        Feature.NOTIFICATIONS -> R.string.vn_rationale_notifications
    }

    /** The spoken rationale for [feature], localised. */
    fun rationale(context: Context, feature: Feature): String =
        context.getString(rationaleRes(feature))

    /** Permissions for [feature] that are not yet granted (empty = feature fully permitted). */
    fun missing(context: Context, feature: Feature): List<String> {
        val required = permissionsFor(feature)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        // Approximate location alone is not enough to navigate a pedestrian: report fine missing,
        // but don't nag about coarse when fine is granted.
        return if (feature == Feature.NAVIGATION &&
            Manifest.permission.ACCESS_FINE_LOCATION !in missing
        ) {
            emptyList()
        } else {
            missing
        }
    }

    /** True if every permission [feature] needs is granted. */
    fun isGranted(context: Context, feature: Feature): Boolean = missing(context, feature).isEmpty()
}
