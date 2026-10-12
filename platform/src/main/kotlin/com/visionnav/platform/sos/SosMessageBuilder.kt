package com.visionnav.platform.sos

import com.visionnav.core.sensing.LocationFix
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Builds SOS text and validates recipients. Pure Kotlin (JVM-testable).
 *
 * Coordinates are only ever taken from a real [LocationFix]; without one the message says
 * "location unavailable". Nothing is guessed or carried forward silently: an old fix is labelled
 * with its age.
 */
object SosMessageBuilder {

    private const val PREFIX = "VisionNav SOS: I need help."
    private const val NO_LOCATION = "$PREFIX My location: location unavailable."

    /** A fix older than this is labelled "last known … ago". */
    const val OLD_FIX_MS = 60_000L

    /**
     * @param fix the latest real fix, or null.
     * @param fixAgeMs how old [fix] is (now − `fix.elapsedMs` on the same clock), if known.
     *   When ≥ [OLD_FIX_MS] the message says it is a last-known position and how old it is.
     *
     * Example: `VisionNav SOS: I need help. My location:
     * https://www.openstreetmap.org/?mlat=13.082700&mlon=80.270700 (accuracy ~12 m)`
     */
    fun buildMessage(fix: LocationFix?, fixAgeMs: Long? = null): String {
        if (fix == null) return NO_LOCATION
        val lat = String.format(Locale.US, "%.6f", fix.point.lat)
        val lon = String.format(Locale.US, "%.6f", fix.point.lon)
        val accuracyM = ceil(fix.accuracyM.toDouble()).toLong().coerceAtLeast(1L)
        val url = "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon"
        val age = if (fixAgeMs != null && fixAgeMs >= OLD_FIX_MS) {
            val minutes = (fixAgeMs / 60_000.0).roundToLong().coerceAtLeast(1L)
            ", last known position about $minutes min ago"
        } else {
            ""
        }
        return "$PREFIX My location: $url (accuracy ~$accuracyM m$age)"
    }

    /**
     * Cleans a contact list: trims, strips visual separators (spaces, dashes, dots, parentheses),
     * drops entries that are not plausible phone numbers (3–20 digits, optional leading '+'),
     * and removes duplicates while keeping order.
     */
    fun normalizeNumbers(contacts: List<String>): List<String> =
        contacts.asSequence()
            .map { raw -> raw.trim().filterNot { it == ' ' || it == '-' || it == '.' || it == '(' || it == ')' } }
            .filter { PHONE.matches(it) }
            .distinct()
            .toList()

    private val PHONE = Regex("^\\+?[0-9]{3,20}$")
}
