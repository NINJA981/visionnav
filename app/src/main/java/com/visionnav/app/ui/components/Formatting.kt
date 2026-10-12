package com.visionnav.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.visionnav.app.R
import com.visionnav.app.settings.DistanceUnits
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Honest, rounded distance for display (e.g. place search results). Short distances are rounded
 * to 10 m / 25 ft because GPS cannot support more precision.
 */
@Composable
fun distanceText(meters: Double, units: DistanceUnits): String {
    val m = meters.coerceAtLeast(0.0)
    return when (units) {
        DistanceUnits.METRIC -> if (m < 1000) {
            stringResource(R.string.dist_metres, ((m / 10).roundToInt() * 10).coerceAtLeast(10))
        } else {
            stringResource(R.string.dist_kilometres, String.format(Locale.getDefault(), "%.1f", m / 1000))
        }
        DistanceUnits.IMPERIAL -> {
            val feet = m * 3.28084
            if (feet < 1000) {
                stringResource(R.string.dist_feet, ((feet / 25).roundToInt() * 25).coerceAtLeast(25))
            } else {
                stringResource(R.string.dist_miles, String.format(Locale.getDefault(), "%.1f", m / 1609.344))
            }
        }
    }
}
