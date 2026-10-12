package com.visionnav.core.scene

import com.visionnav.core.announce.Announcement
import com.visionnav.core.announce.AnnouncementPriority
import com.visionnav.core.announce.Earcon
import com.visionnav.core.announce.HapticPattern
import com.visionnav.core.safety.ProximityBand
import com.visionnav.core.safety.SafetyGeometry
import com.visionnav.core.safety.SafetyGeometry.Companion.rank
import com.visionnav.core.safety.SafetyGeometryConfig
import com.visionnav.core.safety.SpokenWords
import com.visionnav.core.tracking.Track
import com.visionnav.core.vision.ObjectCategory
import com.visionnav.core.vision.ObjectClass
import kotlin.math.abs

/**
 * @param minObstacleProximity an in-path object must be at least this near to trigger advice.
 * @param minOccupancyProximity objects at least this near count towards side-band occupancy.
 * @param blockedOccupancy a side band at or above this occupancy fraction counts as blocked.
 * @param sideDifferenceMargin one side is "freer" only if its occupancy is lower by at least this much;
 *   otherwise the obstacle's own offset decides, and if it is centred no side is suggested.
 * @param centredTolerance obstacle centre within 0.5 ± this is considered centred.
 * @param maxMissedFrames only tracks seen within this many frames are considered.
 */
data class PathConfig(
    val geometry: SafetyGeometryConfig = SafetyGeometryConfig(),
    val minObstacleProximity: ProximityBand = ProximityBand.NEAR,
    val minOccupancyProximity: ProximityBand = ProximityBand.MEDIUM,
    val blockedOccupancy: Float = 0.6f,
    val sideDifferenceMargin: Float = 0.15f,
    val centredTolerance: Float = 0.03f,
    val maxMissedFrames: Int = 2,
)

enum class PathSide { LEFT, RIGHT }

/** Outcome of [PathAnalyzer.analyze]. Only [ObstacleAhead] and [Blocked] carry an announcement. */
sealed interface PathAssessment {
    val announcement: Announcement?

    /**
     * No recognised object in the walking corridor. This is NOT a statement that the path is clear —
     * the detector only knows a few classes and cannot see kerbs, steps or holes — so nothing is spoken.
     */
    data class NothingRecognisedInPath(val elapsedMs: Long) : PathAssessment {
        override val announcement: Announcement? get() = null
    }

    /** Objects are in the corridor but not yet near enough to warrant advice. */
    data class ObstacleNotNear(val trackIds: List<Long>, val elapsedMs: Long) : PathAssessment {
        override val announcement: Announcement? get() = null
    }

    /** A near object is in the corridor; [freerSide] is null when neither side is clearly freer. */
    data class ObstacleAhead(
        val trackId: Long,
        val objectClass: ObjectClass,
        val proximity: ProximityBand,
        val freerSide: PathSide?,
        val leftOccupancy: Float,
        val rightOccupancy: Float,
        override val announcement: Announcement,
    ) : PathAssessment

    /** A near object is in the corridor and both side bands are heavily occupied. */
    data class Blocked(
        val trackIds: List<Long>,
        val leftOccupancy: Float,
        val rightOccupancy: Float,
        override val announcement: Announcement,
    ) : PathAssessment
}

/**
 * Phase 6B walking-corridor analysis. When a near object occupies the corridor, compares how much of
 * the left band (x < corridorLeft) and right band (x > corridorRight) of the lower image is covered by
 * medium-or-nearer objects and reports the freer side as an observation ("More space on the left."),
 * never as an assurance that the side is passable. If both bands are heavily occupied it says
 * "Path ahead blocked." Announcements use WARNING priority.
 *
 * Elevated traffic-control objects are ignored (their boxes don't touch the ground). Stateless.
 */
class PathAnalyzer(val config: PathConfig = PathConfig()) {

    private val geometry = SafetyGeometry(config.geometry)

    fun analyze(tracks: List<Track>, nowMs: Long): PathAssessment {
        val g = config.geometry
        data class Obs(val track: Track, val proximity: ProximityBand, val inPath: Boolean)
        val obs = tracks
            .filter { it.missedFrames <= config.maxMissedFrames && it.history.isNotEmpty() }
            .filter { it.objectClass.category != ObjectCategory.TRAFFIC_CONTROL }
            .map { Obs(it, geometry.proximity(it.objectClass, it.latest.box), geometry.inWalkingPath(it.latest.box)) }

        val inPath = obs.filter { it.inPath }
        if (inPath.isEmpty()) return PathAssessment.NothingRecognisedInPath(nowMs)

        val nearInPath = inPath.filter { it.proximity.rank >= config.minObstacleProximity.rank }
        if (nearInPath.isEmpty()) return PathAssessment.ObstacleNotNear(inPath.map { it.track.id }.sorted(), nowMs)

        val primary = nearInPath.sortedWith(
            compareByDescending<Obs> { it.proximity.rank }
                .thenByDescending { it.track.latest.box.height }
                .thenBy { it.track.id },
        ).first()

        val occupying = obs
            .filter { it.proximity.rank >= config.minOccupancyProximity.rank && it.track.latest.box.bottom > g.pathTopY }
            .map { it.track.latest.box.left to it.track.latest.box.right }
        val leftOcc = coverage(occupying, 0f, g.corridorLeft)
        val rightOcc = coverage(occupying, g.corridorRight, 1f)

        if (leftOcc >= config.blockedOccupancy && rightOcc >= config.blockedOccupancy) {
            val ids = nearInPath.map { it.track.id }.sorted()
            return PathAssessment.Blocked(
                trackIds = ids,
                leftOccupancy = leftOcc,
                rightOccupancy = rightOcc,
                announcement = announcement(BLOCKED_TEXT, "path:blocked", nowMs),
            )
        }

        val obstacleCx = primary.track.latest.box.centerX
        val side: PathSide? = when {
            leftOcc + config.sideDifferenceMargin <= rightOcc -> PathSide.LEFT
            rightOcc + config.sideDifferenceMargin <= leftOcc -> PathSide.RIGHT
            abs(obstacleCx - 0.5f) <= config.centredTolerance -> null
            obstacleCx > 0.5f -> PathSide.LEFT
            else -> PathSide.RIGHT
        }?.takeIf { s -> (if (s == PathSide.LEFT) leftOcc else rightOcc) < config.blockedOccupancy }

        val noun = SpokenWords.capitalize(SpokenWords.singular(primary.track.objectClass))
        val text = when (side) {
            PathSide.LEFT -> "$noun ahead. More space on the left."
            PathSide.RIGHT -> "$noun ahead. More space on the right."
            null -> "$noun ahead."
        }
        return PathAssessment.ObstacleAhead(
            trackId = primary.track.id,
            objectClass = primary.track.objectClass,
            proximity = primary.proximity,
            freerSide = side,
            leftOccupancy = leftOcc,
            rightOccupancy = rightOcc,
            announcement = announcement(text, "path:${primary.track.id}:${side ?: "none"}", nowMs),
        )
    }

    private fun announcement(text: String, key: String, nowMs: Long) = Announcement(
        text = text,
        priority = AnnouncementPriority.WARNING,
        dedupeKey = key,
        ttlMs = 2_500,
        earcon = Earcon.WARNING,
        haptic = HapticPattern.WARNING,
        createdAtMs = nowMs,
    )

    /** Fraction of [lo, hi] covered by the union of the given x-intervals. */
    private fun coverage(intervals: List<Pair<Float, Float>>, lo: Float, hi: Float): Float {
        val width = hi - lo
        if (width <= 0f) return 0f
        val clipped = intervals
            .map { (a, b) -> maxOf(a, lo) to minOf(b, hi) }
            .filter { (a, b) -> b > a }
            .sortedBy { it.first }
        var covered = 0f
        var curStart = Float.NaN
        var curEnd = Float.NaN
        for ((a, b) in clipped) {
            if (curStart.isNaN()) {
                curStart = a; curEnd = b
            } else if (a <= curEnd) {
                curEnd = maxOf(curEnd, b)
            } else {
                covered += curEnd - curStart
                curStart = a; curEnd = b
            }
        }
        if (!curStart.isNaN()) covered += curEnd - curStart
        return (covered / width).coerceIn(0f, 1f)
    }

    companion object {
        const val BLOCKED_TEXT = "Path ahead blocked."
    }
}
