package com.visionnav.core.scene

import com.visionnav.core.crossing.CrossingCandidate
import com.visionnav.core.vision.CrosswalkObservation

/**
 * @param scoreThreshold a frame is positive when its zebra-stripe score is at least this.
 * @param minPersistMs evidence must persist this long before a candidate is emitted.
 * @param minPositiveRatio fraction of frames in the last [minPersistMs] that must be positive.
 * @param dropAfterAbsentMs an emitted candidate is withdrawn after this long without a positive frame.
 * @param restartGapMs before emission, a gap without positives longer than this restarts persistence.
 */
data class CrosswalkGateConfig(
    val scoreThreshold: Float = 0.6f,
    val minPersistMs: Long = 1_200,
    val minPositiveRatio: Float = 0.7f,
    val dropAfterAbsentMs: Long = 2_000,
    val restartGapMs: Long = 400,
)

/**
 * Phase 6C persistence gate for the single-frame classical-CV zebra detector. A painted-stripe
 * pattern can appear for a frame or two on many surfaces (railings, shadows, blinds); only evidence
 * that persists across ≥ [CrosswalkGateConfig.minPersistMs] with ≥ 70 % positive frames becomes a
 * [CrossingCandidate.VisualCrosswalk] — which is still only "possible crosswalk" with LOW reliability.
 *
 * Call [update] once per analysed frame, passing null when the detector produced nothing (that frame
 * counts as negative). Not thread-safe.
 */
class CrosswalkPersistenceGate(val config: CrosswalkGateConfig = CrosswalkGateConfig()) {

    private data class Frame(val elapsedMs: Long, val positive: Boolean, val score: Float)

    private val window = ArrayDeque<Frame>()
    private var episodeStartMs: Long? = null
    private var lastPositiveMs: Long? = null
    private var lastEmittedScore = 0f
    private var active = false
    private var lastFrameMs: Long? = null

    /** True while a candidate is being emitted. */
    val isActive: Boolean get() = active

    /** @return the current candidate, or null while evidence is insufficient or after it dropped. */
    fun update(observation: CrosswalkObservation?, elapsedMs: Long): CrossingCandidate.VisualCrosswalk? {
        lastFrameMs?.let { if (elapsedMs < it) reset() }
        lastFrameMs = elapsedMs

        val score = observation?.score?.takeIf { it.isFinite() } ?: 0f
        val positive = observation != null && score >= config.scoreThreshold
        window.addLast(Frame(elapsedMs, positive, score))
        while (window.isNotEmpty() && window.first().elapsedMs < elapsedMs - config.minPersistMs) window.removeFirst()

        if (positive) {
            if (episodeStartMs == null) episodeStartMs = elapsedMs
            lastPositiveMs = elapsedMs
        }

        val sinceLastPositive = lastPositiveMs?.let { elapsedMs - it }
        if (active) {
            if (sinceLastPositive == null || sinceLastPositive > config.dropAfterAbsentMs) {
                clearEpisode()
                return null
            }
        } else {
            if (sinceLastPositive != null && sinceLastPositive > config.restartGapMs) clearEpisode()
            val start = episodeStartMs ?: return null
            val persisted = elapsedMs - start
            if (persisted >= config.minPersistMs && positiveRatio() >= config.minPositiveRatio) active = true
            else return null
        }

        val positives = window.filter { it.positive }
        if (positives.isNotEmpty()) lastEmittedScore = positives.map { it.score }.average().toFloat()
        return CrossingCandidate.VisualCrosswalk(
            score = lastEmittedScore,
            persistedMs = elapsedMs - (episodeStartMs ?: elapsedMs),
        )
    }

    fun reset() {
        window.clear()
        clearEpisode()
        lastFrameMs = null
    }

    private fun clearEpisode() {
        episodeStartMs = null
        lastPositiveMs = null
        active = false
        lastEmittedScore = 0f
    }

    private fun positiveRatio(): Float =
        if (window.isEmpty()) 0f else window.count { it.positive }.toFloat() / window.size
}
