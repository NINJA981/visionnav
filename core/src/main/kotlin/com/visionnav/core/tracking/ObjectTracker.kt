package com.visionnav.core.tracking

import com.visionnav.core.vision.Detection
import com.visionnav.core.vision.NormBox
import com.visionnav.core.vision.ObjectClass
import kotlin.math.max

/**
 * Configuration for [ObjectTracker]. All distances are in normalised image units.
 *
 * @param highConfidenceThreshold detections at or above this go to the first association pass.
 * @param newTrackMinConfidence unmatched detections at or above this start a tentative track. This is
 *   deliberately below [highConfidenceThreshold] (unlike textbook ByteTrack) so a consistently
 *   weak-but-real person or vehicle still gets tracked; tentative tracks need [minHitsToConfirm]
 *   hits before being exposed, which suppresses one-frame false positives.
 * @param minIouForMatch minimum IoU for an overlap-based match.
 * @param maxCentreDistanceRatio fallback gate for non-overlapping boxes (fast motion at low fps):
 *   centre distance in units of the mean box diagonal.
 * @param centreFallbackMaxScore score of a fallback match at zero distance; kept below typical IoU
 *   matches so overlap always wins.
 * @param familyMismatchPenalty score multiplier when a track and detection differ in class but are
 *   in the same [classFamilies] group (e.g. car vs truck).
 * @param minScore pairs below this score are never associated.
 * @param historySize bounded history length per track.
 * @param minHitsToConfirm hits before a tentative track is confirmed and exposed.
 * @param maxMissedFrames / [maxMissedMs] a confirmed track is removed when either is exceeded.
 * @param tentativeMaxMissedFrames a tentative track is removed after more misses than this.
 * @param reportLostForFrames confirmed tracks are still returned while coasting this many frames
 *   (consumers see [Track.missedFrames]); after that they are kept internally for re-association only.
 * @param ambiguityMargin two candidate detections whose scores differ by less than this make the
 *   match ambiguous -> identity suspect.
 * @param ambiguityMinScore the runner-up must score at least this to count as a real alternative.
 * @param maxCentreJumpWidths centre deviation from the predicted position, in box widths, above
 *   which a match is treated as an implausible jump -> identity suspect.
 * @param maxSizeRatio width or height ratio between consecutive observations above which the match
 *   is treated as implausible -> identity suspect.
 * @param maxPredictionMs constant-velocity prediction horizon cap.
 * @param velocitySmoothing EMA weight of the newest velocity measurement (0..1).
 * @param horizontalFovDeg used to remove camera-yaw shift from the prediction.
 * @param suspectClearSamples once a suspect track has this many samples of fresh (valid) history the
 *   flag is cleared; motion estimation only ever uses valid history anyway.
 */
data class TrackerConfig(
    val highConfidenceThreshold: Float = 0.55f,
    val newTrackMinConfidence: Float = 0.45f,
    val minIouForMatch: Float = 0.1f,
    val maxCentreDistanceRatio: Float = 1.2f,
    val centreFallbackMaxScore: Float = 0.3f,
    val familyMismatchPenalty: Float = 0.8f,
    val classFamilies: List<Set<ObjectClass>> = DetectionFilterConfig.DEFAULT_CLASS_FAMILIES,
    val minScore: Float = 0.05f,
    val historySize: Int = 30,
    val minHitsToConfirm: Int = 2,
    val maxMissedFrames: Int = 10,
    val maxMissedMs: Long = 1_500,
    val tentativeMaxMissedFrames: Int = 1,
    val reportLostForFrames: Int = 2,
    val ambiguityMargin: Float = 0.1f,
    val ambiguityMinScore: Float = 0.2f,
    val maxCentreJumpWidths: Float = 1.0f,
    val maxSizeRatio: Float = 1.6f,
    val maxPredictionMs: Long = 500,
    val velocitySmoothing: Float = 0.5f,
    val horizontalFovDeg: Float = 66f,
    val suspectClearSamples: Int = 6,
) {
    init {
        require(historySize >= 2) { "historySize must be >= 2" }
        require(minHitsToConfirm >= 1) { "minHitsToConfirm must be >= 1" }
        require(horizontalFovDeg > 0f) { "horizontalFovDeg must be > 0" }
    }
}

/**
 * ByteTrack-lite multi-object tracker.
 *
 * Per [update]:
 * 1. Each live track predicts its box with a damped constant-velocity model, minus the image shift
 *    caused by camera yaw (camera turning right moves static objects left by `yaw·dt/HFOV`).
 * 2. **Pass 1:** all tracks vs high-confidence detections. **Pass 2:** still-unmatched tracks vs
 *    low-confidence detections (recovers objects whose score dipped, e.g. partial occlusion).
 * 3. Association score = IoU with the predicted box; if boxes don't overlap, a centre-distance
 *    fallback with a capped, lower score. Only same-class pairs, or same-family pairs (car/truck/bus,
 *    bicycle/motorcycle) with a penalty, may match.
 * 4. Assignment is **greedy by best score** (deterministic tie-break by track id, then detection
 *    index). With the small N of a phone camera scene (< ~30 objects) greedy is near-optimal for
 *    IoU costs and far simpler to audit than Hungarian.
 * 5. **Identity-switch guard:** a match is flagged when another detection scored within
 *    [TrackerConfig.ambiguityMargin] of the winner, or when the box jumped implausibly (centre or
 *    size). The track gets `identitySuspect = true` and `historyValidFromMs` = this frame, so motion
 *    classification restarts from scratch instead of mixing two objects' histories.
 * 6. Unmatched detections spawn tentative tracks; tracks are confirmed after
 *    [TrackerConfig.minHitsToConfirm] hits and removed after too many misses.
 *
 * IDs are monotonically increasing and never reused for the lifetime of this instance, including
 * across [reset], so downstream dedupe keys can never confuse two different objects.
 *
 * Not thread-safe: call from a single analysis thread/coroutine.
 */
class ObjectTracker(val config: TrackerConfig = TrackerConfig()) {

    private class TrackState(
        val id: Long,
        firstSample: TrackSample,
        initialClass: ObjectClass,
    ) {
        val history = ArrayDeque<TrackSample>().apply { add(firstSample) }
        val firstSeenMs: Long = firstSample.elapsedMs
        var lastSeenMs: Long = firstSample.elapsedMs
        var missedFrames = 0
        var hits = 1
        var confirmed = false
        var identitySuspect = false
        var historyValidFromMs: Long = firstSample.elapsedMs
        /** Object velocity with camera ego-motion removed, normalised units per ms. */
        var vx = 0f
        var vy = 0f
        var hasVelocity = false
        val classVotes = HashMap<ObjectClass, Float>().apply { put(initialClass, firstSample.confidence) }
        var objectClass: ObjectClass = initialClass
        /** Predicted box for the current frame (set at the start of each update). */
        var predicted: NormBox = firstSample.box

        val lastBox: NormBox get() = history.last().box

        fun toTrack(): Track = Track(
            id = id,
            objectClass = objectClass,
            history = history.toList(),
            firstSeenMs = firstSeenMs,
            lastSeenMs = lastSeenMs,
            missedFrames = missedFrames,
            identitySuspect = identitySuspect,
            historyValidFromMs = historyValidFromMs,
        )
    }

    private data class Match(val track: TrackState, val detIndex: Int, val score: Float, val ambiguous: Boolean)

    private val tracks = ArrayList<TrackState>()
    private var nextId = 1L
    private var lastUpdateMs: Long? = null
    private val hfovRad = Math.toRadians(config.horizontalFovDeg.toDouble()).toFloat()

    /** Number of live tracks, including tentative and coasting ones (diagnostics). */
    val liveTrackCount: Int get() = tracks.size

    /**
     * Associates this frame's (already filtered) [detections] with existing tracks.
     *
     * @param elapsedMs monotonic frame timestamp. A timestamp earlier than the previous frame is
     *   treated as a discontinuity and resets the tracker (IDs are still never reused).
     * @param cameraYawRateRadPerSec gyro yaw rate at this frame (positive = turning right), or null
     *   if unavailable; stored in each [TrackSample] for ego-motion compensation downstream.
     * @return confirmed tracks (including ones coasting for up to
     *   [TrackerConfig.reportLostForFrames] frames), sorted by id.
     */
    fun update(detections: List<Detection>, elapsedMs: Long, cameraYawRateRadPerSec: Float?): List<Track> {
        lastUpdateMs?.let { if (elapsedMs < it) reset() }
        lastUpdateMs = elapsedMs

        val yaw = cameraYawRateRadPerSec?.takeIf { it.isFinite() }
        for (t in tracks) t.predicted = predict(t, elapsedMs, yaw)

        val highIdx = detections.indices.filter { detections[it].confidence >= config.highConfidenceThreshold }
        val lowIdx = detections.indices.filter { detections[it].confidence < config.highConfidenceThreshold }

        val pass1 = greedyMatch(tracks, detections, highIdx)
        val matchedTracks = pass1.mapTo(HashSet()) { it.track.id }
        val pass2 = greedyMatch(tracks.filter { it.id !in matchedTracks }, detections, lowIdx)

        val usedDets = HashSet<Int>()
        for (m in pass1 + pass2) {
            applyMatch(m.track, detections[m.detIndex], elapsedMs, yaw, m.ambiguous)
            usedDets += m.detIndex
            matchedTracks += m.track.id
        }

        for (t in tracks) if (t.id !in matchedTracks) t.missedFrames++
        tracks.removeAll { isDead(it, elapsedMs) }

        detections.indices
            .filter { it !in usedDets && detections[it].confidence >= config.newTrackMinConfidence }
            .sortedByDescending { detections[it].confidence }
            .forEach { i ->
                val d = detections[i]
                val state = TrackState(nextId++, TrackSample(elapsedMs, d.box, d.confidence, yaw), d.objectClass)
                if (config.minHitsToConfirm <= 1) state.confirmed = true
                tracks += state
            }

        return confirmedTracks()
    }

    /** Current confirmed tracks without advancing time. */
    fun confirmedTracks(): List<Track> = tracks
        .filter { it.confirmed && it.missedFrames <= config.reportLostForFrames }
        .sortedBy { it.id }
        .map { it.toTrack() }

    /**
     * Drops every track (e.g. between road-crossing segments, so the next observation starts from
     * zero history). The ID counter is NOT reset, so IDs are never reused.
     */
    fun reset() {
        tracks.clear()
        lastUpdateMs = null
    }

    // --- internals -------------------------------------------------------------------------

    private fun predict(t: TrackState, nowMs: Long, yaw: Float?): NormBox {
        val dtMs = (nowMs - t.lastSeenMs).coerceAtLeast(0L)
        val motionDt = dtMs.coerceAtMost(config.maxPredictionMs).toFloat()
        val egoShift = if (yaw != null) -yaw * (dtMs / 1000f) / hfovRad else 0f
        val dx = (if (t.hasVelocity) t.vx * motionDt else 0f) + egoShift
        val dy = if (t.hasVelocity) t.vy * motionDt else 0f
        return BoxGeometry.shift(t.lastBox, dx, dy)
    }

    private fun compatibility(trackClass: ObjectClass, detClass: ObjectClass): Float? = when {
        trackClass == detClass -> 1f
        config.classFamilies.any { trackClass in it && detClass in it } -> config.familyMismatchPenalty
        else -> null
    }

    private fun score(t: TrackState, d: Detection): Float {
        val compat = compatibility(t.objectClass, d.objectClass) ?: return 0f
        val iou = BoxGeometry.iou(t.predicted, d.box)
        val raw = if (iou >= config.minIouForMatch) {
            iou
        } else {
            val meanDiag = (BoxGeometry.diagonal(t.predicted) + BoxGeometry.diagonal(d.box)) / 2f
            if (meanDiag <= 0f) return 0f
            val ratio = BoxGeometry.centerDistance(t.predicted, d.box) / meanDiag
            if (ratio >= config.maxCentreDistanceRatio) 0f
            else config.centreFallbackMaxScore * (1f - ratio / config.maxCentreDistanceRatio)
        }
        return raw * compat
    }

    private fun greedyMatch(candidates: List<TrackState>, detections: List<Detection>, detIdx: List<Int>): List<Match> {
        if (candidates.isEmpty() || detIdx.isEmpty()) return emptyList()
        data class Pair(val track: TrackState, val det: Int, val score: Float)

        val pairs = ArrayList<Pair>()
        val scoresByTrack = HashMap<Long, MutableList<Float>>()
        for (t in candidates) {
            for (i in detIdx) {
                val s = score(t, detections[i])
                if (s >= config.minScore) {
                    pairs += Pair(t, i, s)
                    scoresByTrack.getOrPut(t.id) { ArrayList() } += s
                }
            }
        }
        pairs.sortWith(compareByDescending<Pair> { it.score }.thenBy { it.track.id }.thenBy { it.det })

        val usedTracks = HashSet<Long>()
        val usedDets = HashSet<Int>()
        val out = ArrayList<Match>()
        for (p in pairs) {
            if (p.track.id in usedTracks || p.det in usedDets) continue
            usedTracks += p.track.id
            usedDets += p.det
            val others = scoresByTrack[p.track.id].orEmpty().sortedDescending()
            val runnerUp = others.getOrNull(1)
            val ambiguous = runnerUp != null &&
                runnerUp >= config.ambiguityMinScore &&
                p.score - runnerUp < config.ambiguityMargin
            out += Match(p.track, p.det, p.score, ambiguous)
        }
        return out
    }

    private fun applyMatch(t: TrackState, d: Detection, nowMs: Long, yaw: Float?, ambiguous: Boolean) {
        val prev = t.lastBox
        val pred = t.predicted
        val refWidth = max(max(pred.width, d.box.width), 1e-4f)
        val jumpWidths = BoxGeometry.centerDistance(pred, d.box) / refWidth
        val sizeRatio = maxOf(
            ratio(d.box.width, prev.width),
            ratio(d.box.height, prev.height),
        )
        val implausible = jumpWidths > config.maxCentreJumpWidths || sizeRatio > config.maxSizeRatio
        val suspectNow = ambiguous || implausible

        val dtMs = (nowMs - t.lastSeenMs).toFloat()
        if (suspectNow) {
            t.identitySuspect = true
            t.historyValidFromMs = nowMs
            t.vx = 0f
            t.vy = 0f
            t.hasVelocity = false
        } else if (dtMs > 0f) {
            // Measured image velocity, with the yaw-induced shift added back so the stored velocity
            // describes the object, not the camera.
            val egoShift = if (yaw != null) -yaw * (dtMs / 1000f) / hfovRad else 0f
            val ivx = (d.box.centerX - prev.centerX - egoShift) / dtMs
            val ivy = (d.box.centerY - prev.centerY) / dtMs
            val a = config.velocitySmoothing
            if (t.hasVelocity) {
                t.vx = a * ivx + (1 - a) * t.vx
                t.vy = a * ivy + (1 - a) * t.vy
            } else {
                t.vx = ivx
                t.vy = ivy
                t.hasVelocity = true
            }
        }

        t.history.addLast(TrackSample(nowMs, d.box, d.confidence, yaw))
        while (t.history.size > config.historySize) t.history.removeFirst()
        // Keep historyValidFromMs consistent with the retained window.
        if (t.historyValidFromMs < t.history.first().elapsedMs) t.historyValidFromMs = t.history.first().elapsedMs

        t.lastSeenMs = nowMs
        t.missedFrames = 0
        t.hits++
        if (!t.confirmed && t.hits >= config.minHitsToConfirm) t.confirmed = true

        t.classVotes.merge(d.objectClass, d.confidence, Float::plus)
        t.objectClass = t.classVotes.maxWith(compareBy<Map.Entry<ObjectClass, Float>> { it.value }.thenBy { -it.key.ordinal }).key

        if (t.identitySuspect && !suspectNow) {
            val valid = t.history.count { it.elapsedMs >= t.historyValidFromMs }
            if (valid >= config.suspectClearSamples) t.identitySuspect = false
        }
    }

    private fun ratio(a: Float, b: Float): Float {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        return if (lo <= 1e-6f) Float.POSITIVE_INFINITY else hi / lo
    }

    private fun isDead(t: TrackState, nowMs: Long): Boolean =
        if (!t.confirmed) {
            t.missedFrames > config.tentativeMaxMissedFrames
        } else {
            t.missedFrames > config.maxMissedFrames || nowMs - t.lastSeenMs > config.maxMissedMs
        }
}
