package com.visionnav.core.navigation

import com.visionnav.core.announce.Announcement
import com.visionnav.core.announce.AnnouncementPriority
import com.visionnav.core.announce.Earcon
import com.visionnav.core.announce.HapticPattern
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.sensing.LocationFix
import kotlin.math.roundToInt

/** How turn directions are spoken. */
enum class DirectionStyle {
    /** "Turn right", "Bear slightly left". */
    LEFT_RIGHT,

    /** "Turn to 3 o'clock" (falls back to left/right when the engine gave no bearings). */
    CLOCK_FACE,
}

/**
 * Generates the spoken text for navigation (the InstructionGenerator).
 *
 * Wording rules:
 * - **Honest distances.** Under 15 m we say "in a few metres" (or "now" at execution); otherwise
 *   distances are rounded to 5 m below 50 m, 10 m below 200 m and 50 m beyond. If the GPS accuracy
 *   is worse than the distance itself, a number would be false precision, so we say "ahead".
 * - **Never imply safety.** Road crossings are announced as a place to stop and assess:
 *   "Road crossing ahead. Stop at the kerb and assess traffic." Nothing here may say a road is
 *   clear or that the user can cross; every output is covered by a SafetyPhraseGuard test.
 * - **Never invent location.** [whereAmI] says "Location unavailable." rather than guessing.
 * - Arrival side ("on your right") is only stated when the routing engine said so.
 *
 * All navigation announcements use [AnnouncementPriority.NAVIGATION], so CRITICAL/WARNING safety
 * alerts always preempt them, and they are never filtered by verbosity.
 */
class NavigationPhrases(
    var directionStyle: DirectionStyle = DirectionStyle.LEFT_RIGHT,
) {

    // --- distances -------------------------------------------------------------------------

    /** Rounds [distanceM] per the honest-rounding table; values under 15 m return 0 (meaning "a few metres"). */
    fun roundDistanceM(distanceM: Double): Int {
        val d = distanceM.coerceAtLeast(0.0)
        if (d < FEW_METRES_M) return 0
        val step = when {
            d < 50 -> 5
            d < 200 -> 10
            else -> 50
        }
        val rounded = ((d / step).roundToInt() * step)
        return rounded.coerceAtLeast(FEW_METRES_M.toInt())
    }

    /**
     * Distance clause: "in 40 metres", "in a few metres", or "ahead" when [accuracyM] is worse than
     * the distance (a number would claim precision we do not have).
     */
    fun distancePhrase(distanceM: Double, accuracyM: Float?): String {
        if (accuracyM != null && accuracyM > distanceM && distanceM >= FEW_METRES_M) return "ahead"
        val r = roundDistanceM(distanceM)
        return if (r == 0) "in a few metres" else "in $r metres"
    }

    // --- maneuvers -------------------------------------------------------------------------

    fun prepare(maneuverIndex: Int, maneuver: Maneuver, distanceM: Double, accuracyM: Float?): Announcement {
        val dist = distancePhrase(distanceM, accuracyM)
        val text = when {
            isCrossing(maneuver) -> {
                val road = maneuver.streetNames.firstOrNull()?.let { " on $it" } ?: ""
                if (dist == "ahead") "Road crossing$road ahead." else "Road crossing$road $dist."
            }
            maneuver.type.isArrive() ->
                if (dist == "ahead") "Your destination is ahead." else "Your destination is $dist."
            else -> sentence(action(maneuver), onto(maneuver), dist)
        }
        return navAnnouncement(text, "nav:prepare:$maneuverIndex", maneuver, ttlMs = PREPARE_TTL_MS)
    }

    fun execute(maneuverIndex: Int, maneuver: Maneuver): Announcement {
        val text = when {
            isCrossing(maneuver) -> CROSSING_EXECUTE
            maneuver.type.isArrive() -> "Your destination is near."
            maneuver.type == ManeuverType.STAIRS -> sentence("Stairs here", onto(maneuver), null)
            else -> sentence(action(maneuver), onto(maneuver), "now")
        }
        return navAnnouncement(text, "nav:execute:$maneuverIndex", maneuver, ttlMs = EXECUTE_TTL_MS)
    }

    /** Spoken once when guidance starts: "Head north on Anna Salai. 450 metres to Central Station." */
    fun routeStart(route: Route, destinationName: String?): Announcement {
        val first = route.maneuvers.firstOrNull()
        val head = first?.bearingAfterDeg?.let { "Head ${compass(it)}" } ?: "Start walking along the route"
        val street = first?.streetNames?.firstOrNull()?.let { " on $it" } ?: ""
        val total = roundDistanceM(route.lengthM)
        val to = destinationName?.takeIf { it.isNotBlank() }?.let { " to $it" } ?: ""
        val length = if (total == 0) "Destination is a few metres away" else "$total metres$to"
        return Announcement(
            text = "$head$street. $length.",
            priority = AnnouncementPriority.NAVIGATION,
            dedupeKey = "nav:start",
            ttlMs = PREPARE_TTL_MS,
            earcon = Earcon.CONFIRM,
        )
    }

    // --- events ----------------------------------------------------------------------------

    /**
     * Maps a tracker event to an announcement. Returns null for events that should not be spoken
     * (e.g. [NavigationEvent.RerouteNeeded], which is an instruction to the app, not the user).
     */
    fun forEvent(event: NavigationEvent, destinationName: String? = null): Announcement? = when (event) {
        is NavigationEvent.PrepareManeuver -> prepare(event.maneuverIndex, event.maneuver, event.distanceM, event.accuracyM)
        is NavigationEvent.ExecuteManeuver -> execute(event.maneuverIndex, event.maneuver)
        is NavigationEvent.OffRoute -> offRoute()
        NavigationEvent.BackOnRoute -> simple("Back on route.", "nav:back-on-route", Earcon.CONFIRM, HapticPattern.TICK)
        is NavigationEvent.RerouteNeeded -> null
        is NavigationEvent.Arrived -> arrived(destinationName, event.maneuver)
        is NavigationEvent.ArrivalUncertain -> arrivalUncertain()
        is NavigationEvent.GpsDegraded ->
            if (event.accuracyM == null) {
                simple("GPS signal lost. Directions unavailable until location returns.", "nav:gps", Earcon.ERROR, HapticPattern.TICK)
            } else {
                simple("GPS signal weak. Directions may be less precise.", "nav:gps", Earcon.ERROR, HapticPattern.TICK)
            }
        NavigationEvent.GpsRecovered -> simple("GPS signal restored.", "nav:gps-ok", Earcon.CONFIRM, HapticPattern.NONE)
    }

    fun offRoute(): Announcement =
        simple("You may be off route. Recalculating.", "nav:off-route", Earcon.WARNING, HapticPattern.TICK)

    /**
     * "You have arrived near <name>." plus the side **only** if the engine reported
     * ARRIVE_LEFT/ARRIVE_RIGHT. "Near" because GPS cannot confirm the exact entrance.
     */
    fun arrived(destinationName: String?, finalManeuver: Maneuver?): Announcement {
        val name = destinationName?.takeIf { it.isNotBlank() } ?: "your destination"
        val side = when (finalManeuver?.type) {
            ManeuverType.ARRIVE_RIGHT -> " The destination is on your right."
            ManeuverType.ARRIVE_LEFT -> " The destination is on your left."
            else -> ""
        }
        return Announcement(
            text = "You have arrived near $name.$side",
            priority = AnnouncementPriority.NAVIGATION,
            dedupeKey = "nav:arrived",
            ttlMs = PREPARE_TTL_MS,
            earcon = Earcon.ARRIVED,
            haptic = HapticPattern.ARRIVED,
        )
    }

    fun arrivalUncertain(): Announcement =
        simple("Arrival point near. Exact entrance not confirmed.", "nav:arrival-uncertain", Earcon.ARRIVED, HapticPattern.TICK)

    // --- where am I ------------------------------------------------------------------------

    /**
     * "Near 12 Main Street, facing north-east. Location accurate to about 10 metres."
     *
     * Never invents anything: no fix ⇒ "Location unavailable."; no reverse-geocoded place ⇒ says the
     * address is unavailable; no heading ⇒ facing is omitted.
     */
    fun whereAmI(place: Place?, fix: LocationFix?, headingDeg: Double?): String {
        if (fix == null) return "Location unavailable."
        val label = place?.let { placeLabel(it) }
        val facing = headingDeg?.let { "facing ${compass(it)}" }
        val first = when {
            label != null && facing != null -> "Near $label, $facing."
            label != null -> "Near $label."
            facing != null -> "Address unavailable. You are $facing."
            else -> "Address unavailable."
        }
        val acc = fix.accuracyM.toDouble()
        val accSentence = when {
            acc > POOR_ACCURACY_M -> "Location is approximate, accurate only to about ${roundAccuracy(acc)} metres."
            else -> "Location accurate to about ${roundAccuracy(acc)} metres."
        }
        return "$first $accSentence"
    }

    /** [whereAmI] as an announcement. NAVIGATION priority: the user asked, so verbosity never hides it. */
    fun whereAmIAnnouncement(place: Place?, fix: LocationFix?, headingDeg: Double?): Announcement =
        Announcement(
            text = whereAmI(place, fix, headingDeg),
            priority = AnnouncementPriority.NAVIGATION,
            dedupeKey = "nav:where-am-i",
            ttlMs = PREPARE_TTL_MS,
        )

    // --- helpers ---------------------------------------------------------------------------

    private fun placeLabel(p: Place): String? {
        val name = p.name.trim().takeIf { it.isNotEmpty() }
        val address = p.address?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            name != null && address != null && !address.contains(name, ignoreCase = true) &&
                !name.contains(address, ignoreCase = true) -> "$name, $address"
            address != null && (name == null || address.contains(name, ignoreCase = true)) -> address
            else -> name
        }
    }

    private fun roundAccuracy(acc: Double): Int = when {
        acc < 5 -> 5
        acc < 50 -> ((acc / 5).roundToInt() * 5).coerceAtLeast(5)
        acc < 200 -> (acc / 10).roundToInt() * 10
        else -> (acc / 50).roundToInt() * 50
    }

    private fun ManeuverType.isArrive() =
        this == ManeuverType.ARRIVE || this == ManeuverType.ARRIVE_LEFT || this == ManeuverType.ARRIVE_RIGHT

    private fun isCrossing(m: Maneuver) = m.isCrossing || m.type == ManeuverType.CROSS_ROAD

    private fun onto(m: Maneuver): String? = m.streetNames.firstOrNull()?.takeIf { it.isNotBlank() }?.let { name ->
        when (m.type) {
            ManeuverType.STAIRS, ManeuverType.ELEVATOR, ManeuverType.ESCALATOR,
            ManeuverType.ENTER_BUILDING, ManeuverType.EXIT_BUILDING, ManeuverType.ENTER_ROUNDABOUT -> null
            else -> "onto $name"
        }
    }

    private fun sentence(action: String, onto: String?, distance: String?): String =
        listOfNotNull(action, onto, distance).joinToString(" ") + "."

    private fun relativeTurnDeg(m: Maneuver): Double? {
        val before = m.bearingBeforeDeg ?: return null
        val after = m.bearingAfterDeg ?: return null
        return GeoMath.signedAngleDiff(before, after)
    }

    private fun action(m: Maneuver): String {
        val clock = if (directionStyle == DirectionStyle.CLOCK_FACE && m.type.isTurn()) {
            relativeTurnDeg(m)?.let { "Turn to ${GeoMath.clockHour(it)} o'clock" }
        } else {
            null
        }
        if (clock != null) return clock
        return when (m.type) {
            ManeuverType.START -> "Start walking"
            ManeuverType.CONTINUE -> "Continue straight"
            ManeuverType.SLIGHT_LEFT -> "Bear slightly left"
            ManeuverType.LEFT -> "Turn left"
            ManeuverType.SHARP_LEFT -> "Turn sharp left"
            ManeuverType.SLIGHT_RIGHT -> "Bear slightly right"
            ManeuverType.RIGHT -> "Turn right"
            ManeuverType.SHARP_RIGHT -> "Turn sharp right"
            ManeuverType.U_TURN -> "Turn around"
            ManeuverType.CROSS_ROAD -> "Road crossing"
            ManeuverType.ENTER_ROUNDABOUT -> "Walk around the roundabout"
            ManeuverType.EXIT_ROUNDABOUT -> "Leave the roundabout"
            ManeuverType.STAIRS -> "Stairs"
            ManeuverType.ELEVATOR -> "Take the lift"
            ManeuverType.ESCALATOR -> "Take the escalator"
            ManeuverType.ENTER_BUILDING -> "Enter the building"
            ManeuverType.EXIT_BUILDING -> "Exit the building"
            ManeuverType.ARRIVE, ManeuverType.ARRIVE_LEFT, ManeuverType.ARRIVE_RIGHT -> "Destination"
            ManeuverType.OTHER -> "Continue along the route"
        }
    }

    private fun ManeuverType.isTurn() = this in TURN_TYPES

    private fun hapticFor(m: Maneuver): HapticPattern {
        if (isCrossing(m)) return HapticPattern.TICK
        return when (m.type) {
            ManeuverType.SLIGHT_LEFT, ManeuverType.LEFT, ManeuverType.SHARP_LEFT -> HapticPattern.TURN_LEFT
            ManeuverType.SLIGHT_RIGHT, ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT -> HapticPattern.TURN_RIGHT
            ManeuverType.U_TURN -> when {
                (relativeTurnDeg(m) ?: -1.0) > 0 -> HapticPattern.TURN_RIGHT
                else -> HapticPattern.TURN_LEFT
            }
            ManeuverType.ARRIVE, ManeuverType.ARRIVE_LEFT, ManeuverType.ARRIVE_RIGHT -> HapticPattern.ARRIVED
            else -> HapticPattern.TICK
        }
    }

    private fun navAnnouncement(text: String, key: String, m: Maneuver, ttlMs: Long) = Announcement(
        text = text,
        priority = AnnouncementPriority.NAVIGATION,
        dedupeKey = key,
        ttlMs = ttlMs,
        earcon = Earcon.TURN,
        haptic = hapticFor(m),
    )

    private fun simple(text: String, key: String, earcon: Earcon, haptic: HapticPattern) = Announcement(
        text = text,
        priority = AnnouncementPriority.NAVIGATION,
        dedupeKey = key,
        ttlMs = PREPARE_TTL_MS,
        earcon = earcon,
        haptic = haptic,
    )

    companion object {
        const val CROSSING_EXECUTE = "Road crossing ahead. Stop at the kerb and assess traffic."
        private const val FEW_METRES_M = 15.0
        private const val POOR_ACCURACY_M = 50.0
        const val PREPARE_TTL_MS = 8_000L

        /** A "turn now" spoken late is wrong, so execution instructions go stale quickly. */
        const val EXECUTE_TTL_MS = 5_000L

        private val TURN_TYPES = setOf(
            ManeuverType.SLIGHT_LEFT, ManeuverType.LEFT, ManeuverType.SHARP_LEFT,
            ManeuverType.SLIGHT_RIGHT, ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT,
        )

        private val COMPASS = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

        /** 8-point compass word for a bearing in degrees. */
        fun compass(bearingDeg: Double): String =
            COMPASS[((GeoMath.normalizeDeg(bearingDeg) + 22.5) / 45.0).toInt() % 8]
    }
}
