package com.visionnav.core.navigation

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.navigation.NavigationEvent.ArrivalUncertain
import com.visionnav.core.navigation.NavigationEvent.Arrived
import com.visionnav.core.navigation.NavigationEvent.BackOnRoute
import com.visionnav.core.navigation.NavigationEvent.ExecuteManeuver
import com.visionnav.core.navigation.NavigationEvent.GpsDegraded
import com.visionnav.core.navigation.NavigationEvent.GpsRecovered
import com.visionnav.core.navigation.NavigationEvent.OffRoute
import com.visionnav.core.navigation.NavigationEvent.PrepareManeuver
import com.visionnav.core.navigation.NavigationEvent.RerouteNeeded
import com.visionnav.core.navigation.RouteFixtures.fix
import com.visionnav.core.navigation.RouteFixtures.pointAlong
import org.junit.Test

class RouteProgressTrackerTest {

    private var t = 0L

    /** Walks from [fromM] to [toM] along the route in [stepM] increments, one fix per 1.5 s. */
    private fun RouteProgressTracker.walk(
        fromM: Double,
        toM: Double,
        stepM: Double = 2.0,
        accuracy: Float = 5f,
        lateralM: Double = 0.0,
    ): Pair<List<NavigationEvent>, RouteProgress> {
        val events = mutableListOf<NavigationEvent>()
        var along = fromM
        var last: RouteProgress? = null
        while (along <= toM + 1e-9) {
            val u = update(fix(pointAlong(route.geometry, along, lateralM), t, accuracy))
            events += u.events
            last = u.progress
            t += 1_500
            along += stepM
        }
        return events to last!!
    }

    private fun RouteProgressTracker.at(alongM: Double, lateralM: Double = 0.0, accuracy: Float = 5f, dtMs: Long = 1_500): RouteUpdate {
        val u = update(fix(pointAlong(route.geometry, alongM, lateralM), t, accuracy))
        t += dtMs
        return u
    }

    // --- maneuvers -------------------------------------------------------------------------

    @Test
    fun `L route walk emits prepare and execute once each then arrives`() {
        val tracker = RouteProgressTracker(RouteFixtures.lShape())
        val (events, last) = tracker.walk(0.0, 350.0)

        val prepares = events.filterIsInstance<PrepareManeuver>()
        val executes = events.filterIsInstance<ExecuteManeuver>()
        assertThat(prepares.filter { it.maneuverIndex == 1 }).hasSize(1)
        assertThat(executes.filter { it.maneuverIndex == 1 }).hasSize(1)
        val prep = prepares.first { it.maneuverIndex == 1 }
        assertThat(prep.distanceM).isAtMost(40.0)
        assertThat(prep.distanceM).isGreaterThan(36.0)
        assertThat(executes.single().distanceM).isAtMost(10.0)
        assertThat(events.indexOf(prep)).isLessThan(events.indexOf(executes.single()))
        assertThat(executes.single().maneuver.type).isEqualTo(ManeuverType.RIGHT)

        assertThat(events.filterIsInstance<OffRoute>()).isEmpty()
        val arrived = events.filterIsInstance<Arrived>()
        assertThat(arrived).hasSize(1)
        assertThat(arrived.single().maneuver?.type).isEqualTo(ManeuverType.ARRIVE_RIGHT)
        assertThat(last.arrived).isTrue()
        // The arrive maneuver is pre-announced but never "executed".
        assertThat(executes.none { it.maneuver.type == ManeuverType.ARRIVE_RIGHT }).isTrue()
    }

    @Test
    fun `straight route crossing is prepared and executed once`() {
        val tracker = RouteProgressTracker(RouteFixtures.straight())
        val (events, _) = tracker.walk(0.0, 260.0, stepM = 3.0)
        assertThat(events.filterIsInstance<PrepareManeuver>().map { it.maneuverIndex }).containsExactly(1)
        val exec = events.filterIsInstance<ExecuteManeuver>()
        assertThat(exec).hasSize(1)
        assertThat(exec.single().maneuver.isCrossing).isTrue()
    }

    @Test
    fun `progress reports distances and maneuver index`() {
        val tracker = RouteProgressTracker(RouteFixtures.lShape())
        val p = tracker.at(50.0).progress
        assertThat(p.alongM).isWithin(1.0).of(50.0)
        assertThat(p.remainingM).isWithin(1.5).of(300.0)
        assertThat(p.currentManeuverIndex).isEqualTo(1)
        assertThat(p.nextManeuver?.type).isEqualTo(ManeuverType.RIGHT)
        assertThat(p.distanceToNextManeuverM!!).isWithin(1.0).of(150.0)
        assertThat(p.offRouteDistanceM).isLessThan(1.0)
        assertThat(p.gpsAccuracyM).isEqualTo(5f)
        assertThat(p.isOffRoute).isFalse()
    }

    @Test
    fun `jitter around the execute threshold executes only once`() {
        val tracker = RouteProgressTracker(RouteFixtures.lShape())
        tracker.walk(0.0, 180.0)
        val events = mutableListOf<NavigationEvent>()
        for (a in listOf(191.0, 189.0, 192.0, 188.0, 193.0, 190.0, 195.0, 198.0)) events += tracker.at(a).events
        assertThat(events.filterIsInstance<ExecuteManeuver>()).hasSize(1)
        assertThat(events.filterIsInstance<PrepareManeuver>()).isEmpty()
    }

    @Test
    fun `prepare distance grows with poor accuracy`() {
        val tracker = RouteProgressTracker(RouteFixtures.lShape())
        val (events, _) = tracker.walk(0.0, 195.0, accuracy = 30f)
        val prep = events.filterIsInstance<PrepareManeuver>().single { it.maneuverIndex == 1 }
        assertThat(prep.distanceM).isGreaterThan(50.0)
        assertThat(prep.distanceM).isAtMost(60.0)
        assertThat(prep.accuracyM).isEqualTo(30f)
    }

    @Test
    fun `maneuver passed between sparse fixes is skipped not announced late`() {
        val tracker = RouteProgressTracker(RouteFixtures.lShape())
        tracker.at(150.0, dtMs = 30_000) // GPS gap
        val u = tracker.at(220.0) // now 20 m past the turn at 200 m
        assertThat(u.events.filterIsInstance<ExecuteManeuver>()).isEmpty()
        assertThat(u.progress.currentManeuverIndex).isEqualTo(2)
    }

    // --- off route -------------------------------------------------------------------------

    @Test
    fun `a single noisy fix does not trigger off route`() {
        val tracker = RouteProgressTracker(RouteFixtures.straight())
        tracker.walk(0.0, 40.0)
        val events = mutableListOf<NavigationEvent>()
        events += tracker.at(42.0, lateralM = 60.0).events
        events += tracker.at(44.0).events
        events += tracker.at(46.0, lateralM = -50.0, dtMs = 9_000).events
        events += tracker.at(48.0).events
        assertThat(events.filterIsInstance<OffRoute>()).isEmpty()
        assertThat(events.filterIsInstance<RerouteNeeded>()).isEmpty()
    }

    @Test
    fun `off route requires both duration and fix count`() {
        val tracker = RouteProgressTracker(RouteFixtures.straight())
        tracker.walk(0.0, 40.0)
        // Three far fixes in quick succession: enough fixes, not enough time.
        val quick = (1..3).flatMap { tracker.at(40.0, lateralM = 40.0, dtMs = 1_000).events }
        assertThat(quick.filterIsInstance<OffRoute>()).isEmpty()
        // Sustained past 8 s.
        val later = (1..6).flatMap { tracker.at(40.0, lateralM = 40.0, dtMs = 1_500).events }
        assertThat(later.filterIsInstance<OffRoute>()).hasSize(1)
        assertThat(later.filterIsInstance<RerouteNeeded>().first().attempt).isEqualTo(1)
    }

    @Test
    fun `two sparse far fixes over a long time are not enough`() {
        val tracker = RouteProgressTracker(RouteFixtures.straight())
        tracker.walk(0.0, 40.0)
        val e = tracker.at(40.0, lateralM = 40.0, dtMs = 20_000).events + tracker.at(40.0, lateralM = 40.0).events
        assertThat(e.filterIsInstance<OffRoute>()).isEmpty()
    }

    @Test
    fun `back on route uses hysteresis`() {
        val tracker = RouteProgressTracker(RouteFixtures.straight())
        tracker.walk(0.0, 40.0)
        repeat(4) { tracker.at(40.0, lateralM = 40.0, dtMs = 3_000) }
        assertThat(tracker.at(40.0, lateralM = 40.0).progress.isOffRoute).isTrue()

        // 20 m off is inside the 25 m threshold but not inside the 17.5 m back-on band.
        assertThat(tracker.at(42.0, lateralM = 20.0).events).doesNotContain(BackOnRoute)
        assertThat(tracker.at(44.0, lateralM = 20.0).events).doesNotContain(BackOnRoute)
        // One good fix is not enough; two are.
        assertThat(tracker.at(46.0, lateralM = 2.0).events).doesNotContain(BackOnRoute)
        val u = tracker.at(48.0, lateralM = 1.0)
        assertThat(u.events).contains(BackOnRoute)
        assertThat(u.progress.isOffRoute).isFalse()
    }

    @Test
    fun `maneuver events are suppressed while off route`() {
        val tracker = RouteProgressTracker(RouteFixtures.straight())
        tracker.walk(0.0, 150.0)
        repeat(6) { tracker.at(160.0, lateralM = 40.0, dtMs = 2_000) }
        val e = (0..5).flatMap { tracker.at(170.0 + it * 5, lateralM = 40.0).events }
        assertThat(e.filterIsInstance<PrepareManeuver>()).isEmpty()
    }

    @Test
    fun `off-route threshold scales with accuracy`() {
        val tracker = RouteProgressTracker(RouteFixtures.straight())
        tracker.walk(0.0, 40.0, accuracy = 30f)
        // 40 m off with 30 m accuracy is inside max(25, 45) = 45 m: not off route.
        val e = (1..10).flatMap { tracker.at(40.0, lateralM = 40.0, accuracy = 30f, dtMs = 2_000).events }
        assertThat(e.filterIsInstance<OffRoute>()).isEmpty()
    }

    // --- reroute backoff -------------------------------------------------------------------

    @Test
    fun `reroute requests back off exponentially to a 60 s cap`() {
        val tracker = RouteProgressTracker(RouteFixtures.straight())
        tracker.walk(0.0, 40.0)
        var offAt = -1L
        var firstAttempt: RerouteNeeded? = null
        while (offAt < 0) {
            val now = t
            val u = tracker.at(40.0, lateralM = 50.0, dtMs = 2_000)
            if (u.events.any { it is OffRoute }) {
                offAt = now
                firstAttempt = u.events.filterIsInstance<RerouteNeeded>().single()
            }
        }
        assertThat(firstAttempt!!.attempt).isEqualTo(1)
        assertThat(firstAttempt.from).isNotNull()

        val requests = mutableListOf<Pair<Long, Int>>()
        var now = offAt
        while (now <= offAt + 250_000) {
            tracker.tick(now).filterIsInstance<RerouteNeeded>().forEach { requests += (now - offAt) to it.attempt }
            now += 1_000
        }
        assertThat(requests).containsExactly(
            10_000L to 2,
            30_000L to 3,
            70_000L to 4,
            130_000L to 5,
            190_000L to 6,
            250_000L to 7,
        ).inOrder()
    }

    @Test
    fun `backoff resets once the user is confirmed on a new route`() {
        val tracker = RouteProgressTracker(RouteFixtures.straight())
        tracker.walk(0.0, 40.0)
        repeat(6) { tracker.at(40.0, lateralM = 50.0, dtMs = 2_000) }
        val here = pointAlong(RouteFixtures.straight().geometry, 40.0, 50.0)
        // App installs a new route starting where the user is.
        val newGeom = RouteFixtures.polyline(here, 0.0 to 200.0)
        tracker.setRoute(Route(newGeom, listOf(RouteFixtures.maneuver(ManeuverType.START, 0), RouteFixtures.maneuver(ManeuverType.ARRIVE, 1)), 200.0, 150.0))
        tracker.walk(0.0, 20.0)
        // Off the new route: first request is immediate again (attempt 1).
        val e = (1..8).flatMap { tracker.at(30.0, lateralM = 50.0, dtMs = 2_000).events }
        assertThat(e.filterIsInstance<RerouteNeeded>().first().attempt).isEqualTo(1)
    }

    // --- arrival ---------------------------------------------------------------------------

    @Test
    fun `arrival requires two good fixes near the end`() {
        val route = RouteFixtures.straight()
        val tracker = RouteProgressTracker(route)
        tracker.walk(0.0, 280.0, stepM = 5.0)
        val first = tracker.at(296.0)
        assertThat(first.events.filterIsInstance<Arrived>()).isEmpty()
        val second = tracker.at(298.0)
        assertThat(second.events.filterIsInstance<Arrived>()).hasSize(1)
        // Terminal: nothing more afterwards.
        assertThat(tracker.at(300.0).events).isEmpty()
    }

    @Test
    fun `poor accuracy near the end yields ArrivalUncertain never Arrived`() {
        val tracker = RouteProgressTracker(RouteFixtures.straight())
        tracker.walk(0.0, 250.0, stepM = 5.0)
        val events = (0..8).flatMap { tracker.at(260.0 + it * 5, accuracy = 50f).events }
        assertThat(events.filterIsInstance<Arrived>()).isEmpty()
        assertThat(events.filterIsInstance<ArrivalUncertain>()).hasSize(1)

        // Accuracy recovers: now arrival can be confirmed.
        val later = tracker.at(299.0, accuracy = 6f).events + tracker.at(300.0, accuracy = 6f).events
        assertThat(later.filterIsInstance<Arrived>()).hasSize(1)
    }

    @Test
    fun `accuracy just above 40 m never claims arrival`() {
        val tracker = RouteProgressTracker(RouteFixtures.straight())
        tracker.walk(0.0, 250.0, stepM = 5.0)
        val events = (0..10).flatMap { tracker.at(300.0, accuracy = 41f).events }
        assertThat(events.filterIsInstance<Arrived>()).isEmpty()
        assertThat(events.filterIsInstance<ArrivalUncertain>()).hasSize(1)
    }

    @Test
    fun `being near the destination point early on the route is not arrival`() {
        // U route: the end is 20 m from the start, but along-distance is far from the end.
        val tracker = RouteProgressTracker(RouteFixtures.uShape())
        val (events, _) = tracker.walk(0.0, 10.0)
        assertThat(events.filterIsInstance<Arrived>()).isEmpty()
    }

    // --- snapping --------------------------------------------------------------------------

    @Test
    fun `no snapping back to an earlier parallel segment`() {
        val route = RouteFixtures.uShape()
        val tracker = RouteProgressTracker(route)
        tracker.walk(0.0, 250.0, stepM = 5.0)
        // On the return leg, but GPS noise puts the fix 13 m west: only 7 m from the outbound leg.
        val noisy = pointAlong(route.geometry, 252.0, lateralM = 13.0)
        val naive = GeoMath.projectOntoPolyline(noisy, route.geometry)!!
        assertWithMessage("fixture sanity: naive projection picks the outbound leg").that(naive.alongM).isLessThan(150.0)

        val u = tracker.update(fix(noisy, t))
        assertThat(u.progress.alongM).isWithin(3.0).of(252.0)
        assertThat(u.progress.currentManeuverIndex).isEqualTo(3)
        assertThat(u.events.filterIsInstance<PrepareManeuver>().none { it.maneuverIndex < 3 }).isTrue()
    }

    @Test
    fun `no snapping forward to a later parallel segment`() {
        val route = RouteFixtures.uShape()
        val tracker = RouteProgressTracker(route)
        tracker.walk(0.0, 65.0, stepM = 5.0)
        // On the outbound leg, but noise puts the fix 13 m east: only 7 m from the return leg.
        val noisy = pointAlong(route.geometry, 68.0, lateralM = 13.0)
        val naive = GeoMath.projectOntoPolyline(noisy, route.geometry)!!
        assertWithMessage("fixture sanity: naive projection picks the return leg").that(naive.alongM).isGreaterThan(170.0)

        val u = tracker.update(fix(noisy, t))
        assertThat(u.progress.alongM).isWithin(3.0).of(68.0)
        assertThat(u.progress.currentManeuverIndex).isEqualTo(1)
    }

    @Test
    fun `after a long gps gap progress may jump forward`() {
        val tracker = RouteProgressTracker(RouteFixtures.lShape())
        tracker.walk(0.0, 20.0)
        t += 60_000
        val u = tracker.at(150.0)
        assertThat(u.progress.alongM).isWithin(1.0).of(150.0)
    }

    @Test
    fun `first fix mid-route snaps globally`() {
        val tracker = RouteProgressTracker(RouteFixtures.lShape())
        val u = tracker.at(260.0)
        assertThat(u.progress.alongM).isWithin(1.0).of(260.0)
        assertThat(u.progress.currentManeuverIndex).isEqualTo(2)
    }

    // --- GPS quality -----------------------------------------------------------------------

    @Test
    fun `gps degraded and recovered with hysteresis`() {
        val tracker = RouteProgressTracker(RouteFixtures.straight())
        assertThat(tracker.at(0.0, accuracy = 35f).events).isEmpty()
        assertThat(tracker.at(2.0, accuracy = 35f).events).contains(GpsDegraded(35f))
        assertThat(tracker.at(4.0, accuracy = 50f).events.filterIsInstance<GpsDegraded>()).isEmpty()
        assertThat(tracker.at(6.0, accuracy = 25f).events).isEmpty() // between thresholds
        assertThat(tracker.at(8.0, accuracy = 8f).events).isEmpty()
        assertThat(tracker.at(10.0, accuracy = 8f).events).contains(GpsRecovered)
    }

    @Test
    fun `stale gps is reported by tick`() {
        val tracker = RouteProgressTracker(RouteFixtures.straight())
        tracker.update(fix(pointAlong(tracker.route.geometry, 0.0), 1_000))
        assertThat(tracker.tick(5_000)).isEmpty()
        assertThat(tracker.tick(11_001)).containsExactly(GpsDegraded(null))
        assertThat(tracker.tick(20_000)).isEmpty()
    }
}
