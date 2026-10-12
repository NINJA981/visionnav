package com.visionnav.core.navigation

import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.sensing.LocationFix
import kotlin.math.max
import kotlin.math.min

/**
 * Tunables for [RouteProgressTracker]. All distances in metres, durations in milliseconds.
 *
 * Distance thresholds grow with GPS error so that announcements stay honest: with 25 m accuracy
 * a "turn in 10 metres" trigger would fire anywhere within a 50 m blob, so prepare/execute are
 * announced earlier and the phrase generator switches to "ahead" instead of a number.
 */
data class RouteProgressConfig(
    val prepareDistanceM: Double = 40.0,
    val maxPrepareDistanceM: Double = 80.0,
    val executeDistanceM: Double = 10.0,
    val maxExecuteDistanceM: Double = 25.0,
    /** A maneuver stays "current" until the user is this far past it (lets a late fix still execute it). */
    val passedToleranceM: Double = 5.0,
    val offRouteMinDistanceM: Double = 25.0,
    val offRouteAccuracyFactor: Double = 1.5,
    val offRouteMinDurationMs: Long = 8_000,
    val offRouteMinFixes: Int = 3,
    /** Back on route requires distance ≤ this fraction of the off-route threshold (hysteresis). */
    val backOnRouteFactor: Double = 0.7,
    val backOnRouteFixes: Int = 2,
    val rerouteInitialBackoffMs: Long = 10_000,
    val rerouteMaxBackoffMs: Long = 60_000,
    val arrivalMinRadiusM: Double = 10.0,
    val arrivalEndToleranceM: Double = 15.0,
    /** Arrival is never claimed when the fix is worse than this; [NavigationEvent.ArrivalUncertain] instead. */
    val arrivalMaxAccuracyM: Float = 40f,
    val arrivalFixes: Int = 2,
    val gpsDegradedAccuracyM: Float = 30f,
    val gpsRecoveredAccuracyM: Float = 20f,
    val gpsDegradedFixes: Int = 2,
    /** No fix for this long ⇒ [NavigationEvent.GpsDegraded] (checked by [RouteProgressTracker.tick]). */
    val gpsStaleMs: Long = 10_000,
    /** Snapping search window behind the last along-distance (prevents snapping back). */
    val snapBackWindowM: Double = 30.0,
    /**
     * Minimum search window ahead of the last along-distance. The window grows with elapsed time
     * ([maxWalkingSpeedMps] × Δt + 2 × accuracy), so after a GPS gap progress can jump forward,
     * but between regular fixes a parallel leg further along the route is out of reach.
     */
    val snapForwardWindowM: Double = 30.0,
    val maxWalkingSpeedMps: Double = 3.0,
)

/**
 * Tracks the user's progress along one [Route] from a stream of [LocationFix]es.
 *
 * - **Snapping** uses [GeoMath.projectOntoPolyline] restricted to a window around the previous
 *   along-distance, so a fix near an earlier, parallel part of the route (U-shaped routes, both
 *   sides of a street) does not yank progress backwards. A global projection is used only for the
 *   first fix or when the window no longer contains a plausible match.
 * - **Maneuvers**: [NavigationEvent.PrepareManeuver] at ~40 m and [NavigationEvent.ExecuteManeuver]
 *   at ~10 m (both scaled up with poor accuracy), each at most once per maneuver, suppressed while
 *   off route. A maneuver passed between two fixes without being announced is skipped silently:
 *   a late "turn now" after the turn is more confusing than none.
 * - **Off route** requires distance > max(25 m, 1.5 × accuracy) sustained for ≥ 8 s *and* ≥ 3
 *   fixes, so one noisy fix never triggers it; back on route requires 2 fixes within 70 % of the
 *   threshold. [NavigationEvent.RerouteNeeded] follows with exponential backoff.
 * - **Arrival** requires good accuracy (≤ 40 m) and 2 consecutive qualifying fixes; with worse
 *   accuracy only [NavigationEvent.ArrivalUncertain] is emitted (once).
 *
 * Time is taken from [LocationFix.elapsedMs] (monotonic), so the tracker is deterministic.
 * Not thread-safe: feed it from a single coroutine/thread.
 */
class RouteProgressTracker(
    route: Route,
    private val config: RouteProgressConfig = RouteProgressConfig(),
) {
    var route: Route = route
        private set

    private lateinit var cumulative: DoubleArray
    private lateinit var maneuverAlong: DoubleArray
    private lateinit var prepared: BooleanArray
    private lateinit var executed: BooleanArray
    private var totalLengthM = 0.0

    private var lastAlong: Double? = null
    private var lastSnapMs: Long? = null
    private var cursor = 0

    private var offRoute = false
    private var offCandidateSinceMs: Long? = null
    private var offCandidateFixes = 0
    private var backOnCandidateFixes = 0

    // Reroute backoff survives setRoute(): a new route that is immediately off again must not
    // reset the request rate. It resets once the user is confirmed on a route.
    private var rerouteAttempts = 0
    private var lastRerouteMs: Long? = null

    private var arrived = false
    private var arrivalCandidateFixes = 0
    private var arrivalUncertainEmitted = false

    private var gpsDegraded = false
    private var badAccuracyFixes = 0
    private var goodAccuracyFixes = 0
    private var lastFix: LocationFix? = null

    init {
        setRoute(route)
    }

    /** Replaces the active route (e.g. after a reroute). Resets per-route state. */
    fun setRoute(newRoute: Route) {
        route = newRoute
        val geom = newRoute.geometry
        cumulative = DoubleArray(geom.size)
        for (i in 1 until geom.size) cumulative[i] = cumulative[i - 1] + GeoMath.distanceM(geom[i - 1], geom[i])
        totalLengthM = if (geom.isEmpty()) 0.0 else cumulative.last()
        maneuverAlong = DoubleArray(newRoute.maneuvers.size) { i ->
            val idx = newRoute.maneuvers[i].beginShapeIndex.coerceIn(0, max(0, geom.size - 1))
            if (geom.isEmpty()) 0.0 else cumulative[idx]
        }
        prepared = BooleanArray(newRoute.maneuvers.size)
        executed = BooleanArray(newRoute.maneuvers.size)
        lastAlong = null
        lastSnapMs = null
        cursor = 0
        offRoute = false
        offCandidateSinceMs = null
        offCandidateFixes = 0
        backOnCandidateFixes = 0
        arrived = false
        arrivalCandidateFixes = 0
        arrivalUncertainEmitted = false
    }

    /** Feeds one fix. Returns the progress snapshot and any events triggered by it. */
    fun update(fix: LocationFix): RouteUpdate {
        val events = ArrayList<NavigationEvent>()
        val now = fix.elapsedMs
        lastFix = fix
        updateGpsQuality(fix, events)

        val snap = snap(fix.point, fix.accuracyM, now)
        val along = snap.alongM
        lastAlong = along
        lastSnapMs = now
        val remaining = max(0.0, totalLengthM - along)
        val acc = fix.accuracyM

        if (!arrived) {
            updateArrival(fix, remaining, events)
        }
        if (!arrived) {
            updateOffRoute(fix, snap.distanceM, events)
            advanceCursor(along)
            if (!offRoute) emitManeuverEvents(along, acc, events)
        } else {
            advanceCursor(along)
        }
        if (offRoute) maybeReroute(now, events)

        val next = route.maneuvers.getOrNull(cursor)
        val progress = RouteProgress(
            alongM = along,
            remainingM = remaining,
            currentManeuverIndex = cursor,
            nextManeuver = next,
            distanceToNextManeuverM = if (next != null) max(0.0, maneuverAlong[cursor] - along) else null,
            offRouteDistanceM = snap.distanceM,
            gpsAccuracyM = acc,
            snappedPoint = snap.point,
            isOffRoute = offRoute,
            arrived = arrived,
            fixElapsedMs = now,
        )
        return RouteUpdate(progress, events)
    }

    /**
     * Time-based checks for when fixes stop arriving: GPS staleness and reroute retries.
     * Call periodically (e.g. every second) with the monotonic clock.
     */
    fun tick(nowMs: Long): List<NavigationEvent> {
        val events = ArrayList<NavigationEvent>()
        val last = lastFix
        if (last != null && !gpsDegraded && nowMs - last.elapsedMs > config.gpsStaleMs) {
            gpsDegraded = true
            goodAccuracyFixes = 0
            events += NavigationEvent.GpsDegraded(null)
        }
        if (offRoute && !arrived) maybeReroute(nowMs, events)
        return events
    }

    // --- snapping --------------------------------------------------------------------------

    private class Snap(val point: GeoPoint, val distanceM: Double, val alongM: Double)

    private fun snap(p: GeoPoint, accuracyM: Float, nowMs: Long): Snap {
        val geom = route.geometry
        if (geom.size < 2) {
            val only = geom.firstOrNull() ?: p
            return Snap(only, GeoMath.distanceM(p, only), 0.0)
        }
        val global = projectRange(p, 0, geom.size - 2, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        val prev = lastAlong ?: return global
        val prevMs = lastSnapMs ?: return global
        // How far the user can plausibly have walked since the previous fix, plus GPS slack.
        val dtS = (nowMs - prevMs).coerceAtLeast(0) / 1000.0
        val reach = max(config.snapForwardWindowM, config.maxWalkingSpeedMps * dtS + 2.0 * accuracyM)
        val lo = prev - config.snapBackWindowM
        val hi = prev + reach
        val windowed = projectRange(p, segmentAt(lo), segmentAt(hi), lo, hi)
        val threshold = offRouteThreshold(accuracyM)
        return when {
            windowed.distanceM <= threshold -> windowed
            // Window lost the user: accept a forward jump (e.g. a shortcut) …
            global.alongM >= lo && global.distanceM < windowed.distanceM -> global
            // … or a backward match only if it is genuinely on the route (user walked back).
            global.distanceM <= threshold -> global
            else -> windowed
        }
    }

    /**
     * Best projection onto segments [fromSeg]..[toSeg]. Candidates whose along-distance falls
     * outside [lo, hi] are penalised metre-for-metre, so a nearby but implausible jump along the
     * route (a parallel leg further back or further ahead) loses to the continuous match.
     */
    private fun projectRange(p: GeoPoint, fromSeg: Int, toSeg: Int, lo: Double, hi: Double): Snap {
        val geom = route.geometry
        var best: Snap? = null
        var bestScore = Double.POSITIVE_INFINITY
        for (seg in fromSeg..max(fromSeg, toSeg)) {
            val proj = GeoMath.projectOntoPolyline(p, geom.subList(seg, seg + 2)) ?: continue
            val segLen = cumulative[seg + 1] - cumulative[seg]
            val along = cumulative[seg] + proj.fraction * segLen
            val score = proj.distanceM + max(0.0, along - hi) + max(0.0, lo - along)
            if (score < bestScore) {
                bestScore = score
                best = Snap(proj.point, proj.distanceM, along)
            }
        }
        return best!!
    }

    /** Index of the segment containing along-distance [alongM], clamped. */
    private fun segmentAt(alongM: Double): Int {
        val last = route.geometry.size - 2
        if (alongM <= 0) return 0
        // cumulative is sorted: binary search for the last vertex with cumulative ≤ alongM.
        var lo = 0
        var hi = cumulative.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cumulative[mid] <= alongM) lo = mid else hi = mid - 1
        }
        return lo.coerceIn(0, last)
    }

    // --- maneuvers -------------------------------------------------------------------------

    private fun ManeuverType.isArrive() =
        this == ManeuverType.ARRIVE || this == ManeuverType.ARRIVE_LEFT || this == ManeuverType.ARRIVE_RIGHT

    private fun advanceCursor(along: Double) {
        val ms = route.maneuvers
        while (cursor < ms.size) {
            val m = ms[cursor]
            val skippable = m.type == ManeuverType.START ||
                (!m.type.isArrive() && along > maneuverAlong[cursor] + config.passedToleranceM)
            if (!skippable) return
            cursor++
        }
    }

    private fun emitManeuverEvents(along: Double, accuracyM: Float, events: MutableList<NavigationEvent>) {
        val idx = cursor
        val m = route.maneuvers.getOrNull(idx) ?: return
        val d = max(0.0, maneuverAlong[idx] - along)
        val extra = max(0.0, accuracyM - 10.0)
        val prepareM = min(config.maxPrepareDistanceM, config.prepareDistanceM + extra)
        val executeM = min(config.maxExecuteDistanceM, config.executeDistanceM + extra / 2)
        if (m.type.isArrive()) {
            // Arrival itself is decided by the accuracy-gated arrival logic; only pre-announce it.
            if (!prepared[idx] && d <= prepareM && d > executeM) {
                prepared[idx] = true
                events += NavigationEvent.PrepareManeuver(idx, m, d, accuracyM)
            }
            return
        }
        if (!executed[idx] && d <= executeM) {
            executed[idx] = true
            prepared[idx] = true
            events += NavigationEvent.ExecuteManeuver(idx, m, d, accuracyM)
        } else if (!prepared[idx] && d <= prepareM) {
            prepared[idx] = true
            events += NavigationEvent.PrepareManeuver(idx, m, d, accuracyM)
        }
    }

    // --- off route / reroute ---------------------------------------------------------------

    private fun offRouteThreshold(accuracyM: Float): Double =
        max(config.offRouteMinDistanceM, config.offRouteAccuracyFactor * accuracyM)

    private fun updateOffRoute(fix: LocationFix, distanceM: Double, events: MutableList<NavigationEvent>) {
        val threshold = offRouteThreshold(fix.accuracyM)
        if (!offRoute) {
            if (distanceM > threshold) {
                if (offCandidateSinceMs == null) offCandidateSinceMs = fix.elapsedMs
                offCandidateFixes++
                val sustained = fix.elapsedMs - offCandidateSinceMs!! >= config.offRouteMinDurationMs
                if (sustained && offCandidateFixes >= config.offRouteMinFixes) {
                    offRoute = true
                    backOnCandidateFixes = 0
                    events += NavigationEvent.OffRoute(distanceM, fix.accuracyM)
                }
            } else {
                offCandidateSinceMs = null
                offCandidateFixes = 0
                // Confirmed on route: a previous reroute succeeded, so reset the backoff.
                rerouteAttempts = 0
                lastRerouteMs = null
            }
        } else {
            if (distanceM <= threshold * config.backOnRouteFactor) {
                backOnCandidateFixes++
                if (backOnCandidateFixes >= config.backOnRouteFixes) {
                    offRoute = false
                    offCandidateSinceMs = null
                    offCandidateFixes = 0
                    backOnCandidateFixes = 0
                    rerouteAttempts = 0
                    lastRerouteMs = null
                    events += NavigationEvent.BackOnRoute
                }
            } else {
                backOnCandidateFixes = 0
            }
        }
    }

    private fun maybeReroute(nowMs: Long, events: MutableList<NavigationEvent>) {
        val fix = lastFix ?: return
        val last = lastRerouteMs
        val due = last == null || nowMs - last >= backoffAfter(rerouteAttempts)
        if (!due) return
        rerouteAttempts++
        lastRerouteMs = nowMs
        events += NavigationEvent.RerouteNeeded(fix.point, fix.courseDeg?.toDouble(), rerouteAttempts)
    }

    /** Wait after the [attempts]-th request: 10 s, 20 s, 40 s, then 60 s. */
    private fun backoffAfter(attempts: Int): Long {
        if (attempts <= 0) return 0
        var b = config.rerouteInitialBackoffMs
        repeat(attempts - 1) { b = min(config.rerouteMaxBackoffMs, b * 2) }
        return min(config.rerouteMaxBackoffMs, b)
    }

    // --- arrival ---------------------------------------------------------------------------

    private fun updateArrival(fix: LocationFix, remainingM: Double, events: MutableList<NavigationEvent>) {
        val dest = route.geometry.lastOrNull() ?: return
        val acc = fix.accuracyM
        val dist = GeoMath.distanceM(fix.point, dest)
        val radius = max(config.arrivalMinRadiusM, acc.toDouble())
        val nearEnd = remainingM <= max(config.arrivalEndToleranceM, acc.toDouble())
        if (dist > radius || !nearEnd) {
            arrivalCandidateFixes = 0
            return
        }
        if (acc > config.arrivalMaxAccuracyM) {
            arrivalCandidateFixes = 0
            if (!arrivalUncertainEmitted) {
                arrivalUncertainEmitted = true
                events += NavigationEvent.ArrivalUncertain(dist, acc)
            }
            return
        }
        arrivalCandidateFixes++
        if (arrivalCandidateFixes >= config.arrivalFixes) {
            arrived = true
            offRoute = false
            val final = route.maneuvers.lastOrNull()?.takeIf { it.type.isArrive() }
            events += NavigationEvent.Arrived(final, dist, acc)
        }
    }

    // --- GPS quality -----------------------------------------------------------------------

    private fun updateGpsQuality(fix: LocationFix, events: MutableList<NavigationEvent>) {
        val acc = fix.accuracyM
        if (acc > config.gpsDegradedAccuracyM) {
            badAccuracyFixes++
            goodAccuracyFixes = 0
            if (!gpsDegraded && badAccuracyFixes >= config.gpsDegradedFixes) {
                gpsDegraded = true
                events += NavigationEvent.GpsDegraded(acc)
            }
        } else {
            badAccuracyFixes = 0
            if (acc <= config.gpsRecoveredAccuracyM) goodAccuracyFixes++ else goodAccuracyFixes = 0
            if (gpsDegraded && goodAccuracyFixes >= config.gpsDegradedFixes) {
                gpsDegraded = false
                goodAccuracyFixes = 0
                events += NavigationEvent.GpsRecovered
            }
        }
    }
}
