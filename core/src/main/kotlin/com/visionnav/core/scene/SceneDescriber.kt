package com.visionnav.core.scene

import com.visionnav.core.announce.Announcement
import com.visionnav.core.announce.AnnouncementPriority
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.safety.ProximityBand
import com.visionnav.core.safety.SafetyGeometry
import com.visionnav.core.safety.SafetyGeometry.Companion.rank
import com.visionnav.core.safety.SafetyGeometryConfig
import com.visionnav.core.safety.SpokenWords
import com.visionnav.core.safety.ViewSector
import com.visionnav.core.tracking.Track
import com.visionnav.core.vision.ObjectCategory
import com.visionnav.core.vision.ObjectClass

/** Coarse side of the camera view used for scene summaries. */
enum class SceneSide { LEFT, AHEAD, RIGHT }

/**
 * @param maxItems at most this many groups are spoken, by salience.
 * @param maxMissedFrames only tracks seen within this many frames are described ("current" tracks).
 */
data class SceneConfig(
    val maxItems: Int = 3,
    val maxMissedFrames: Int = 2,
    val geometry: SafetyGeometryConfig = SafetyGeometryConfig(),
)

/** One spoken group: same class on the same side. [proximity] is the nearest member's band. */
data class SceneItem(
    val objectClass: ObjectClass,
    val count: Int,
    val side: SceneSide,
    val proximity: ProximityBand,
    val inWalkingPath: Boolean,
    val salience: Float,
    val trackIds: List<Long>,
)

/**
 * @param items spoken groups, most salient first.
 * @param omittedGroups groups not spoken because of [SceneConfig.maxItems].
 */
data class SceneDescription(
    val text: String,
    val items: List<SceneItem>,
    val omittedGroups: Int,
    val qualityIssue: UncertaintyReason?,
    val elapsedMs: Long,
) {
    /** User-requested summary: OBSERVATION priority (safety and navigation still preempt it). */
    fun toAnnouncement(): Announcement = Announcement(
        text = text,
        priority = AnnouncementPriority.OBSERVATION,
        dedupeKey = "scene:$elapsedMs",
        ttlMs = 6_000,
        createdAtMs = elapsedMs,
    )
}

/**
 * Phase 6A "What's around": a concise summary of current confirmed tracks, grouped by class and
 * side, e.g. "Two people ahead. A car on the right, far. A bench on the left, close."
 *
 * - Groups are ranked by salience: vehicles and objects in the walking path first, then nearer ones.
 * - If the camera has a quality problem, the summary is prefixed with it, because a sparse scene
 *   under low light means "can't see well", not "nothing there".
 * - With no tracks it says "Nothing recognised in the camera view." — never "clear" or "nothing
 *   there": absence of detection is not absence of hazard.
 */
class SceneDescriber(val config: SceneConfig = SceneConfig()) {

    private val geometry = SafetyGeometry(config.geometry)

    /**
     * @param qualityIssues frame-quality problems, most severe first (as from `FrameQuality.issues()`).
     */
    fun describe(tracks: List<Track>, nowMs: Long, qualityIssues: List<UncertaintyReason> = emptyList()): SceneDescription {
        val current = tracks.filter { it.missedFrames <= config.maxMissedFrames && it.history.isNotEmpty() }

        data class Obs(val track: Track, val side: SceneSide, val proximity: ProximityBand, val inPath: Boolean)
        val observations = current.map { t ->
            val box = t.latest.box
            Obs(t, side(geometry.sector(box)), geometry.proximity(t.objectClass, box), geometry.inWalkingPath(box))
        }

        val groups = observations
            .groupBy { it.track.objectClass to it.side }
            .map { (key, members) ->
                val nearest = members.maxByOrNull { it.proximity.rank }!!.proximity
                val inPath = members.any { it.inPath }
                SceneItem(
                    objectClass = key.first,
                    count = members.size,
                    side = key.second,
                    proximity = nearest,
                    inWalkingPath = inPath,
                    salience = salience(key.first, nearest, inPath, members.size),
                    trackIds = members.map { it.track.id }.sorted(),
                )
            }
            .sortedWith(
                compareByDescending<SceneItem> { it.salience }
                    .thenBy { it.objectClass.ordinal }
                    .thenBy { it.side.ordinal },
            )

        val spoken = groups.take(config.maxItems.coerceAtLeast(1))
        val omitted = groups.size - spoken.size
        val issue = qualityIssues.firstOrNull { it in QUALITY_PHRASES }

        val sentences = ArrayList<String>()
        issue?.let { sentences += QUALITY_PHRASES.getValue(it) }
        if (spoken.isEmpty()) {
            sentences += NOTHING_RECOGNISED
        } else {
            spoken.forEach { sentences += sentence(it) }
            if (omitted > 0) sentences += "More objects in view."
        }
        return SceneDescription(sentences.joinToString(" "), spoken, omitted, issue, nowMs)
    }

    private fun sentence(item: SceneItem): String {
        val subject = SpokenWords.counted(item.objectClass, item.count)
        val where = when (item.side) {
            SceneSide.AHEAD -> "ahead"
            SceneSide.LEFT -> "on the left"
            SceneSide.RIGHT -> "on the right"
        }
        val prox = SpokenWords.proximityWord(item.proximity)
        return if (prox != null) "$subject $where, $prox." else "$subject $where."
    }

    private fun side(sector: ViewSector): SceneSide = when (sector) {
        ViewSector.FAR_LEFT, ViewSector.LEFT -> SceneSide.LEFT
        ViewSector.CENTER -> SceneSide.AHEAD
        ViewSector.RIGHT, ViewSector.FAR_RIGHT -> SceneSide.RIGHT
    }

    private fun salience(c: ObjectClass, p: ProximityBand, inPath: Boolean, count: Int): Float {
        val base = when (c.category) {
            ObjectCategory.VEHICLE -> 4f
            ObjectCategory.PERSON -> 2f
            ObjectCategory.ANIMAL -> 1.5f
            ObjectCategory.OBSTACLE, ObjectCategory.OTHER -> 1f
            ObjectCategory.TRAFFIC_CONTROL -> 0.5f
        }
        val path = if (inPath) 3f else 0f
        val prox = when (p) {
            ProximityBand.VERY_NEAR -> 3f
            ProximityBand.NEAR -> 2f
            ProximityBand.MEDIUM -> 1f
            ProximityBand.UNKNOWN -> 0.5f
            ProximityBand.FAR -> 0f
        }
        return base + path + prox + 0.1f * count.coerceAtMost(3)
    }

    companion object {
        const val NOTHING_RECOGNISED = "Nothing recognised in the camera view."

        /** Spoken prefix per camera-quality problem. */
        val QUALITY_PHRASES: Map<UncertaintyReason, String> = mapOf(
            UncertaintyReason.CAMERA_UNAVAILABLE to "Camera unavailable.",
            UncertaintyReason.CAMERA_OBSTRUCTED to "Camera view obstructed.",
            UncertaintyReason.DETECTOR_UNAVAILABLE to "Object detection unavailable.",
            UncertaintyReason.LOW_LIGHT to "Low light, view limited.",
            UncertaintyReason.GLARE to "Glare, view limited.",
            UncertaintyReason.BLUR to "Image blurry, view limited.",
            UncertaintyReason.CAMERA_MOVING to "Camera moving, view limited.",
        )
    }
}
