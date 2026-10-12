package com.visionnav.core.navigation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.visionnav.core.announce.AnnouncementPriority
import com.visionnav.core.announce.Earcon
import com.visionnav.core.announce.HapticPattern
import com.visionnav.core.announce.SafetyPhraseGuard
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.navigation.RouteFixtures.maneuver
import com.visionnav.core.sensing.LocationFix
import org.junit.Test

class NavigationPhrasesTest {
    private val phrases = NavigationPhrases()
    private val guard = SafetyPhraseGuard()

    @Test
    fun `honest distance rounding table`() {
        val table = listOf(
            0.0 to 0, 5.0 to 0, 14.9 to 0,
            15.0 to 15, 17.0 to 15, 17.5 to 20, 23.0 to 25, 42.4 to 40, 47.0 to 45, 48.0 to 50, 49.9 to 50,
            50.0 to 50, 54.0 to 50, 55.0 to 60, 123.0 to 120, 194.0 to 190, 196.0 to 200, 199.9 to 200,
            200.0 to 200, 224.0 to 200, 225.0 to 250, 260.0 to 250, 480.0 to 500, 1_234.0 to 1_250,
        )
        for ((input, expected) in table) {
            assertWithMessage("round($input)").that(phrases.roundDistanceM(input)).isEqualTo(expected)
        }
    }

    @Test
    fun `distance phrase`() {
        assertThat(phrases.distancePhrase(8.0, 5f)).isEqualTo("in a few metres")
        assertThat(phrases.distancePhrase(14.0, null)).isEqualTo("in a few metres")
        assertThat(phrases.distancePhrase(41.0, 5f)).isEqualTo("in 40 metres")
        assertThat(phrases.distancePhrase(41.0, null)).isEqualTo("in 40 metres")
        assertThat(phrases.distancePhrase(130.0, 12f)).isEqualTo("in 130 metres")
        // Accuracy worse than the distance: a number would be false precision.
        assertThat(phrases.distancePhrase(40.0, 45f)).isEqualTo("ahead")
        assertThat(phrases.distancePhrase(20.0, 25f)).isEqualTo("ahead")
    }

    @Test
    fun `turn with street name`() {
        val m = maneuver(ManeuverType.RIGHT, 2, street = "Anna Salai", before = 0.0, after = 90.0)
        val a = phrases.prepare(1, m, 41.0, 5f)
        assertThat(a.text).isEqualTo("Turn right onto Anna Salai in 40 metres.")
        assertThat(a.priority).isEqualTo(AnnouncementPriority.NAVIGATION)
        assertThat(a.earcon).isEqualTo(Earcon.TURN)
        assertThat(a.haptic).isEqualTo(HapticPattern.TURN_RIGHT)
        assertThat(a.dedupeKey).isEqualTo("nav:prepare:1")
        assertThat(phrases.execute(1, m).text).isEqualTo("Turn right onto Anna Salai now.")
        assertThat(phrases.execute(1, m).dedupeKey).isEqualTo("nav:execute:1")
    }

    @Test
    fun `turn without street name and with poor accuracy`() {
        val m = maneuver(ManeuverType.LEFT, 2, before = 0.0, after = 270.0)
        val a = phrases.prepare(3, m, 35.0, 40f)
        assertThat(a.text).isEqualTo("Turn left ahead.")
        assertThat(a.haptic).isEqualTo(HapticPattern.TURN_LEFT)
    }

    @Test
    fun `clock face style`() {
        val clock = NavigationPhrases(DirectionStyle.CLOCK_FACE)
        val right = maneuver(ManeuverType.RIGHT, 2, street = "Anna Salai", before = 0.0, after = 90.0)
        assertThat(clock.prepare(1, right, 40.0, 5f).text).isEqualTo("Turn to 3 o'clock onto Anna Salai in 40 metres.")
        val slightLeft = maneuver(ManeuverType.SLIGHT_LEFT, 2, before = 350.0, after = 320.0)
        assertThat(clock.prepare(1, slightLeft, 22.0, 5f).text).isEqualTo("Turn to 11 o'clock in 20 metres.")
        // No bearings: falls back to left/right wording.
        val noBearing = maneuver(ManeuverType.SHARP_RIGHT, 2)
        assertThat(clock.prepare(1, noBearing, 60.0, 5f).text).isEqualTo("Turn sharp right in 60 metres.")
    }

    @Test
    fun `crossing wording never implies safety`() {
        val m = maneuver(ManeuverType.CROSS_ROAD, 2, street = "Mount Road", crossing = true)
        assertThat(phrases.prepare(1, m, 40.0, 5f).text).isEqualTo("Road crossing on Mount Road in 40 metres.")
        assertThat(phrases.execute(1, m).text).isEqualTo("Road crossing ahead. Stop at the kerb and assess traffic.")
        // A turn flagged as crossing by the engine is still treated as a crossing.
        val flagged = maneuver(ManeuverType.LEFT, 2, crossing = true)
        assertThat(phrases.execute(2, flagged).text).isEqualTo(NavigationPhrases.CROSSING_EXECUTE)
    }

    @Test
    fun `arrival wording`() {
        val right = maneuver(ManeuverType.ARRIVE_RIGHT, 4)
        val a = phrases.arrived("Central Station", right)
        assertThat(a.text).isEqualTo("You have arrived near Central Station. The destination is on your right.")
        assertThat(a.earcon).isEqualTo(Earcon.ARRIVED)
        assertThat(a.haptic).isEqualTo(HapticPattern.ARRIVED)
        assertThat(phrases.arrived("Central Station", maneuver(ManeuverType.ARRIVE, 4)).text)
            .isEqualTo("You have arrived near Central Station.")
        assertThat(phrases.arrived(null, null).text).isEqualTo("You have arrived near your destination.")
        assertThat(phrases.arrivalUncertain().text).isEqualTo("Arrival point near. Exact entrance not confirmed.")
        assertThat(phrases.offRoute().text).isEqualTo("You may be off route. Recalculating.")
    }

    @Test
    fun `events map to announcements`() {
        val m = maneuver(ManeuverType.RIGHT, 2, street = "Anna Salai", before = 0.0, after = 90.0)
        assertThat(phrases.forEvent(NavigationEvent.PrepareManeuver(1, m, 40.0, 5f))?.text)
            .isEqualTo("Turn right onto Anna Salai in 40 metres.")
        assertThat(phrases.forEvent(NavigationEvent.OffRoute(40.0, 5f))?.text).isEqualTo("You may be off route. Recalculating.")
        assertThat(phrases.forEvent(NavigationEvent.RerouteNeeded(GeoPoint(0.0, 0.0), null, 1))).isNull()
        assertThat(phrases.forEvent(NavigationEvent.ArrivalUncertain(20.0, 50f))?.text)
            .isEqualTo("Arrival point near. Exact entrance not confirmed.")
        assertThat(phrases.forEvent(NavigationEvent.Arrived(maneuver(ManeuverType.ARRIVE_RIGHT, 4), 3.0, 5f), "Central Station")?.text)
            .isEqualTo("You have arrived near Central Station. The destination is on your right.")
    }

    @Test
    fun `where am I`() {
        val fix = LocationFix(GeoPoint(13.05, 80.25), 9f, null, null, null, 0)
        val place = Place("12 Main Street", GeoPoint(13.05, 80.25), null, null, null)
        assertThat(phrases.whereAmI(place, fix, 45.0))
            .isEqualTo("Near 12 Main Street, facing north-east. Location accurate to about 10 metres.")
        assertThat(phrases.whereAmI(place, null, 45.0)).isEqualTo("Location unavailable.")
        assertThat(phrases.whereAmI(null, null, null)).isEqualTo("Location unavailable.")
        assertThat(phrases.whereAmI(place, fix, null)).isEqualTo("Near 12 Main Street. Location accurate to about 10 metres.")
        assertThat(phrases.whereAmI(null, fix, 180.0))
            .isEqualTo("Address unavailable. You are facing south. Location accurate to about 10 metres.")
        val poi = Place("Central Station", GeoPoint(13.05, 80.25), "Poonamallee High Road", null, null)
        assertThat(phrases.whereAmI(poi, fix.copy(accuracyM = 120f), 300.0))
            .isEqualTo("Near Central Station, Poonamallee High Road, facing north-west. Location is approximate, accurate only to about 120 metres.")
        val sameName = Place("Anna Salai", GeoPoint(13.05, 80.25), "Anna Salai, Chennai", null, null)
        assertThat(phrases.whereAmI(sameName, fix, null)).startsWith("Near Anna Salai, Chennai.")
        assertThat(phrases.whereAmIAnnouncement(place, fix, 45.0).priority).isEqualTo(AnnouncementPriority.NAVIGATION)
    }

    @Test
    fun `compass words`() {
        val expected = mapOf(0.0 to "north", 22.0 to "north", 23.0 to "north-east", 90.0 to "east", 200.0 to "south",
            248.0 to "west", 247.0 to "south-west", 359.0 to "north", -45.0 to "north-west")
        for ((deg, word) in expected) assertWithMessage("$deg").that(NavigationPhrases.compass(deg)).isEqualTo(word)
    }

    @Test
    fun `route start`() {
        val a = phrases.routeStart(RouteFixtures.lShape(), "Central Station")
        assertThat(a.text).isEqualTo("Head north. 350 metres to Central Station.")
    }

    @Test
    fun `every generated phrase passes the safety guard`() {
        val streets = listOf(emptyList(), listOf("Anna Salai"), listOf("Mount Road", "NH 48"))
        val distances = listOf(0.0, 3.0, 9.0, 14.0, 15.0, 27.0, 40.0, 63.0, 140.0, 199.0, 420.0, 2_000.0)
        val accuracies = listOf(null, 3f, 12f, 30f, 60f, 150f)
        val outputs = mutableListOf<String>()
        for (style in DirectionStyle.entries) {
            val p = NavigationPhrases(style)
            for (type in ManeuverType.entries) for (names in streets) for (crossing in listOf(false, true)) {
                val m = Maneuver(type, "Cross now", names, 1, 10.0, 10.0, 100.0, crossing)
                outputs += p.execute(1, m).text
                for (d in distances) for (acc in accuracies) outputs += p.prepare(1, m, d, acc).text
            }
            outputs += p.routeStart(RouteFixtures.straight(), "Central Station").text
            outputs += p.routeStart(RouteFixtures.lShape(), null).text
        }
        val m = maneuver(ManeuverType.ARRIVE_LEFT, 3)
        val events = listOf(
            NavigationEvent.OffRoute(30.0, 5f), NavigationEvent.BackOnRoute, NavigationEvent.Arrived(m, 3.0, 5f),
            NavigationEvent.Arrived(null, 3.0, 5f), NavigationEvent.ArrivalUncertain(30.0, 60f),
            NavigationEvent.GpsDegraded(45f), NavigationEvent.GpsDegraded(null), NavigationEvent.GpsRecovered,
        )
        events.mapNotNullTo(outputs) { phrases.forEvent(it, "Central Station")?.text }
        val fix = LocationFix(GeoPoint(13.05, 80.25), 9f, null, null, null, 0)
        val place = Place("12 Main Street", GeoPoint(13.05, 80.25), null, null, null)
        for (h in listOf(null, 0.0, 135.0, 290.0)) {
            outputs += phrases.whereAmI(place, fix, h)
            outputs += phrases.whereAmI(null, fix, h)
            outputs += phrases.whereAmI(place, null, h)
        }
        assertThat(outputs.size).isGreaterThan(5_000)
        for (text in outputs.toSet()) {
            assertWithMessage(text).that(guard.isAllowed(text)).isTrue()
            assertWithMessage("blank output").that(text.isNotBlank()).isTrue()
        }
    }
}
