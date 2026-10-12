package com.visionnav.core.tracking

import com.visionnav.core.vision.Detection
import com.visionnav.core.vision.NormBox
import com.visionnav.core.vision.ObjectClass

/** Deterministic builders shared by tracking / motion / safety / scene tests. */
object TrackFixtures {

    fun boxAt(cx: Float, cy: Float = 0.5f, w: Float = 0.1f, h: Float = 0.2f) =
        NormBox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)

    /** Box defined by its bottom edge, convenient for ground objects. */
    fun boxBottom(cx: Float, bottom: Float, w: Float, h: Float) = NormBox(cx - w / 2, bottom - h, cx + w / 2, bottom)

    fun det(cls: ObjectClass, box: NormBox, conf: Float = 0.8f) = Detection(cls, conf, box)

    fun track(
        samples: List<TrackSample>,
        id: Long = 1,
        cls: ObjectClass = ObjectClass.CAR,
        identitySuspect: Boolean = false,
        historyValidFromMs: Long = samples.first().elapsedMs,
        missedFrames: Int = 0,
    ) = Track(
        id = id,
        objectClass = cls,
        history = samples,
        firstSeenMs = samples.first().elapsedMs,
        lastSeenMs = samples.last().elapsedMs,
        missedFrames = missedFrames,
        identitySuspect = identitySuspect,
        historyValidFromMs = historyValidFromMs,
    )

    /** A single-sample confirmed track with [box] as its latest observation. */
    fun trackWithBox(id: Long, cls: ObjectClass, box: NormBox, nowMs: Long = 1_000, missedFrames: Int = 0) =
        track(listOf(TrackSample(nowMs, box, 0.8f)), id = id, cls = cls, missedFrames = missedFrames)

    /** Samples every [stepMs] from t=0 with boxes produced by [boxFor] (index -> box). */
    fun samples(count: Int, stepMs: Long = 100, yaw: (Int) -> Float? = { null }, boxFor: (Int) -> NormBox): List<TrackSample> =
        (0 until count).map { i -> TrackSample(i * stepMs, boxFor(i), 0.8f, yaw(i)) }
}
