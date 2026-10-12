package com.visionnav.app.ui.contract

import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.navigation.Place
import kotlin.math.roundToInt

/**
 * Realistic sample content (central Chennai) for [FakeAppController] and Compose previews.
 * Every sentence here must pass SafetyPhraseGuard — see FakeAppControllerPhraseTest.
 */
object FakeSampleData {

    /** Simulated user position: Anna Salai near Thousand Lights. */
    val userLocation = GeoPoint(13.0569, 80.2537)
    const val USER_LOCATION_NAME = "Near Anna Salai, Thousand Lights, Chennai"
    const val USER_ACCURACY_M = 8f

    private fun place(name: String, lat: Double, lon: Double, address: String, category: String): Place {
        val p = GeoPoint(lat, lon)
        return Place(name, p, address, category, GeoMath.distanceM(userLocation, p))
    }

    val places: List<Place> = listOf(
        place("Apollo Hospital", 13.0631, 80.2517, "Greams Road, Thousand Lights, Chennai", "hospital"),
        place("Spencer Plaza", 13.0617, 80.2614, "Anna Salai, Chennai", "shopping mall"),
        place("Government Museum", 13.0697, 80.2564, "Pantheon Road, Egmore, Chennai", "museum"),
        place("Chennai Central Railway Station", 13.0827, 80.2752, "Park Town, Chennai", "railway station"),
        place("Marina Beach", 13.0418, 80.2797, "Kamarajar Salai, Mylapore, Chennai", "beach"),
        place("Kapaleeshwarar Temple", 13.0339, 80.2697, "Mylapore, Chennai", "temple"),
        place("Pondy Bazaar", 13.0418, 80.2341, "Thyagaraya Road, T. Nagar, Chennai", "market"),
        place("Anna Centenary Library", 13.0186, 80.2402, "Kotturpuram, Chennai", "library"),
    ).sortedBy { it.distanceM }

    val savedPlaces: List<SavedPlaceUi> = listOf(
        SavedPlaceUi("saved-home", "Home", place("Home", 13.0350, 80.2680, "Luz Church Road, Mylapore, Chennai", "home")),
        SavedPlaceUi("saved-work", "Work", place("Work", 12.9894, 80.2486, "Tidel Park, Taramani, Chennai", "office")),
    )

    /** Traffic observation lines, cycled by the fake while observing. Vocabulary from PLAN.md §4.7. */
    val observationSamples: List<String> = listOf(
        "Vehicle moving left to right in the camera view.",
        "Vehicle approaching.",
        "Several vehicles moving in the camera view.",
        "Vehicle moving away.",
        "Traffic view unclear. Low light.",
        "Vehicle detected, direction unclear.",
        "Camera sees ahead only.",
    )

    const val LOW_LIGHT_OBSERVATION = "Traffic view unclear. Low light."

    const val WARNING_ALERT = "Person close ahead, slightly right."
    const val CRITICAL_ALERT = "Stop. Bicycle very close, straight ahead."

    const val WHATS_AROUND =
        "Demo detector, not real detection. Person at 12 o'clock, near. Car at 2 o'clock, far. Bench at 10 o'clock."

    const val CROSSING_CONTEXT_MAPPED = "Mapped crossing with signals ahead. Traffic island mapped."
    const val CROSSING_CONTEXT_UNKNOWN = "No mapped crossing found nearby. Road direction unavailable."

    /** Every fixed sentence the fake can speak or show. Used by the phrase-guard test. */
    val fixedTexts: List<String> by lazy {
        observationSamples + listOf(
            WARNING_ALERT, CRITICAL_ALERT, WHATS_AROUND, CROSSING_CONTEXT_MAPPED, CROSSING_CONTEXT_UNKNOWN,
            USER_LOCATION_NAME,
        )
    }

    fun distanceText(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return if (m < 1000) {
            val rounded = ((m / 10).roundToInt() * 10).coerceAtLeast(10)
            "$rounded metres"
        } else {
            val km = (m / 100).roundToInt() / 10.0
            "$km kilometres"
        }
    }

    /** Typical white-cane walking pace is slower than the sighted 1.4 m/s average. */
    const val WALKING_SPEED_MPS = 1.0

    fun minutesText(meters: Double): String {
        val minutes = (meters / WALKING_SPEED_MPS / 60.0).roundToInt().coerceAtLeast(1)
        return if (minutes == 1) "about 1 minute" else "about $minutes minutes"
    }
}
