package com.visionnav.core.crossing

import com.visionnav.core.common.Reliability
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.tracking.ImageMotion
import com.visionnav.core.tracking.MotionEstimate
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Lateral motion expressed relative to the camera's facing direction.
 *
 * Convention: rear camera, phone upright in portrait, user facing where the camera points.
 * The upright image is what the user would see looking that way, so image-right == the user's
 * right. A vehicle moving right in the image therefore travels from the user's left to their right.
 */
enum class WorldLateral { LEFT_TO_RIGHT, RIGHT_TO_LEFT, UNKNOWN }

/** Why a map/camera comparison could not be made. */
enum class FusionLimitation {
    NOT_A_VEHICLE,
    ORIENTATION_UNRELIABLE,
    MOTION_NOT_LATERAL,
    CAMERA_FOV_UNKNOWN,
    NO_ROAD,
    ROAD_DIRECTION_UNKNOWN,
    ROAD_DIRECTION_UNRELIABLE,
    ROAD_DISTANCE_UNKNOWN,
    ROAD_TOO_FAR,
    ROAD_NOT_ACROSS_VIEW,
    BEARING_AMBIGUOUS,
}

data class FlowFusionConfig(
    /** Travel bearing within this of an expected flow is a match (of its opposite: a mismatch). */
    val matchToleranceDeg: Double = 60.0,
    /** Road axis must be within this of the camera's lateral axis (camera bearing ± 90°). */
    val perpendicularToleranceDeg: Double = 40.0,
    /** Bin width for [FlowFusion.approximateTravelBearing]. */
    val travelBearingBinDeg: Double = 45.0,
    /** Road must be at most this far from the user to be "the road in view". */
    val maxRoadDistanceM: Double = 30.0,
    val minPlausibleHfovDeg: Float = 20f,
    val maxPlausibleHfovDeg: Float = 130f,
)

/** Detailed comparison outcome. [limitation] is set iff the result is INSUFFICIENT_INFORMATION. */
data class FlowFusionResult(
    val comparison: FlowComparison,
    /** Unbinned world travel bearing used for the comparison, if derivable. */
    val travelBearingDeg: Double?,
    /** True when the road direction was INFERRED (e.g. roundabout convention), not explicitly mapped. */
    val basedOnInferredDirection: Boolean,
    val limitation: FusionLimitation?,
)

/**
 * Pure map/camera fusion (docs/PLAN.md §4.5). Holds no state and writes nothing: in particular it
 * never feeds camera-derived bearings back into navigation heading.
 *
 * Safety rationale: the comparison is *advisory*. A mismatch is returned as a value only — callers
 * must keep passing the observation to the safety engine and the reporter. A vehicle travelling
 * "the wrong way" (wrong-way driver, contraflow bus/bicycle lane, stale map) is exactly the kind of
 * observation that must not be filtered out.
 */
class FlowFusion(val config: FlowFusionConfig = FlowFusionConfig()) {

    /** World-lateral direction, or UNKNOWN unless orientation is reliable and motion is lateral in the image. */
    fun worldLateralDirection(estimate: MotionEstimate, orientation: CameraOrientationEstimate): WorldLateral {
        if (!orientation.isReliable) return WorldLateral.UNKNOWN
        return when (estimate.imageMotion) {
            ImageMotion.MOVING_RIGHT_IN_IMAGE -> WorldLateral.LEFT_TO_RIGHT
            ImageMotion.MOVING_LEFT_IN_IMAGE -> WorldLateral.RIGHT_TO_LEFT
            else -> WorldLateral.UNKNOWN
        }
    }

    /**
     * Approximate world travel bearing (degrees true), binned to [FlowFusionConfig.travelBearingBinDeg]:
     * camera bearing + 90° for image-rightward motion, − 90° for leftward. Null when not derivable.
     */
    fun approximateTravelBearing(estimate: MotionEstimate, orientation: CameraOrientationEstimate): Double? =
        rawTravelBearing(estimate, orientation)?.let { b ->
            val bin = config.travelBearingBinDeg
            GeoMath.normalizeDeg((b / bin).roundToLong() * bin)
        }

    fun compare(
        estimate: MotionEstimate,
        orientation: CameraOrientationEstimate,
        road: RoadSegmentContext?,
        cameraHfovDeg: Float?,
    ): FlowComparison = compareDetailed(estimate, orientation, road, cameraHfovDeg).comparison

    /**
     * Compares only when: the object is a vehicle, orientation is RELIABLE, motion is lateral in the
     * image, camera HFOV is known and plausible, the road is close (≤ [FlowFusionConfig.maxRoadDistanceM]),
     * its direction is ONE_WAY/TWO_WAY with reliability ≥ INFERRED (INFERRED is flagged), and the road
     * runs across the camera view (so lateral image motion is motion along the road).
     * TWO_WAY roads can only MATCH. The unbinned travel bearing is used for the comparison itself;
     * binning is a reporting granularity.
     */
    fun compareDetailed(
        estimate: MotionEstimate,
        orientation: CameraOrientationEstimate,
        road: RoadSegmentContext?,
        cameraHfovDeg: Float?,
    ): FlowFusionResult {
        val travel = rawTravelBearing(estimate, orientation)
        val inferred = road?.reliability == Reliability.INFERRED
        fun insufficient(l: FusionLimitation) =
            FlowFusionResult(FlowComparison.INSUFFICIENT_INFORMATION, travel, inferred, l)

        if (!estimate.objectClass.isVehicle) return insufficient(FusionLimitation.NOT_A_VEHICLE)
        if (!orientation.isReliable) return insufficient(FusionLimitation.ORIENTATION_UNRELIABLE)
        val cameraBearing = orientation.cameraBearingDeg ?: return insufficient(FusionLimitation.ORIENTATION_UNRELIABLE)
        if (travel == null) return insufficient(FusionLimitation.MOTION_NOT_LATERAL)
        if (cameraHfovDeg == null || !cameraHfovDeg.isFinite() ||
            cameraHfovDeg < config.minPlausibleHfovDeg || cameraHfovDeg > config.maxPlausibleHfovDeg
        ) {
            return insufficient(FusionLimitation.CAMERA_FOV_UNKNOWN)
        }
        if (road == null) return insufficient(FusionLimitation.NO_ROAD)
        if (road.directionality == RoadDirectionality.UNKNOWN) return insufficient(FusionLimitation.ROAD_DIRECTION_UNKNOWN)
        val flows = road.expectedFlows.filter { it.reliability >= Reliability.INFERRED }
        if (road.reliability < Reliability.INFERRED || flows.isEmpty()) {
            return insufficient(FusionLimitation.ROAD_DIRECTION_UNRELIABLE)
        }
        val distance = road.distanceM ?: return insufficient(FusionLimitation.ROAD_DISTANCE_UNKNOWN)
        if (distance > config.maxRoadDistanceM) return insufficient(FusionLimitation.ROAD_TOO_FAR)

        val roadAxis = flows.first().bearingDeg
        val offAxis = min(
            GeoMath.absAngleDiff(roadAxis, cameraBearing + 90.0),
            GeoMath.absAngleDiff(roadAxis, cameraBearing - 90.0),
        )
        if (offAxis > config.perpendicularToleranceDeg) return insufficient(FusionLimitation.ROAD_NOT_ACROSS_VIEW)

        val tol = config.matchToleranceDeg
        return when (road.directionality) {
            RoadDirectionality.TWO_WAY ->
                if (flows.any { GeoMath.absAngleDiff(travel, it.bearingDeg) <= tol }) {
                    FlowFusionResult(FlowComparison.EXPECTED_FLOW_MATCH, travel, inferred, null)
                } else {
                    insufficient(FusionLimitation.BEARING_AMBIGUOUS)
                }
            RoadDirectionality.ONE_WAY -> {
                val f = flows.first().bearingDeg
                when {
                    GeoMath.absAngleDiff(travel, f) <= tol ->
                        FlowFusionResult(FlowComparison.EXPECTED_FLOW_MATCH, travel, inferred, null)
                    GeoMath.absAngleDiff(travel, f + 180.0) <= tol ->
                        FlowFusionResult(FlowComparison.EXPECTED_FLOW_MISMATCH, travel, inferred, null)
                    else -> insufficient(FusionLimitation.BEARING_AMBIGUOUS)
                }
            }
            RoadDirectionality.UNKNOWN -> insufficient(FusionLimitation.ROAD_DIRECTION_UNKNOWN)
        }
    }

    private fun rawTravelBearing(estimate: MotionEstimate, orientation: CameraOrientationEstimate): Double? {
        val cam = orientation.cameraBearingDeg ?: return null
        return when (worldLateralDirection(estimate, orientation)) {
            WorldLateral.LEFT_TO_RIGHT -> GeoMath.normalizeDeg(cam + 90.0)
            WorldLateral.RIGHT_TO_LEFT -> GeoMath.normalizeDeg(cam - 90.0)
            WorldLateral.UNKNOWN -> null
        }
    }
}

data class MismatchPersistenceConfig(
    /** A single track must mismatch continuously for at least this long… */
    val minPersistMs: Long = 1_500,
    /** …or at least this many distinct tracks must mismatch within [windowMs]. */
    val minDistinctTracks: Int = 2,
    val windowMs: Long = 3_000,
    /** A gap longer than this between mismatches of one track restarts its run. */
    val maxGapMs: Long = 1_000,
)

/**
 * Debounces [FlowComparison.EXPECTED_FLOW_MISMATCH] before "against the expected direction" is spoken,
 * so a single noisy frame or a mis-tracked box can't trigger it. This only gates *wording*; it never
 * filters the underlying observation. Not thread-safe.
 */
class MismatchPersistence(private val config: MismatchPersistenceConfig = MismatchPersistenceConfig()) {

    private class Run(val firstMs: Long, var lastMs: Long)

    private val runs = HashMap<Long, Run>()

    /** Records one comparison for [trackId] and returns [isConfirmed]. */
    fun update(trackId: Long, comparison: FlowComparison, nowMs: Long): Boolean {
        runs.entries.removeAll { nowMs - it.value.lastMs > config.windowMs }
        when (comparison) {
            FlowComparison.EXPECTED_FLOW_MISMATCH -> {
                val r = runs[trackId]
                if (r == null || nowMs - r.lastMs > config.maxGapMs) runs[trackId] = Run(nowMs, nowMs) else r.lastMs = nowMs
            }
            FlowComparison.EXPECTED_FLOW_MATCH -> runs.remove(trackId)
            FlowComparison.INSUFFICIENT_INFORMATION -> Unit
        }
        return isConfirmed(nowMs)
    }

    fun isConfirmed(nowMs: Long): Boolean {
        val active = runs.values.filter { nowMs - it.lastMs <= config.windowMs }
        return active.size >= config.minDistinctTracks ||
            active.any { it.lastMs - it.firstMs >= config.minPersistMs && nowMs - it.lastMs <= config.maxGapMs }
    }

    fun reset() = runs.clear()
}
