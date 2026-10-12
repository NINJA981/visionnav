package com.visionnav.core.crossing

import com.visionnav.core.common.DataSource
import com.visionnav.core.common.Outcome
import com.visionnav.core.common.Reliability
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.geo.GeoPoint

enum class RoadDirectionality { ONE_WAY, TWO_WAY, UNKNOWN }

/** An expected direction of vehicle travel along a road at a location, from map data only. */
data class ExpectedFlow(
    val bearingDeg: Double,
    val source: DataSource,
    val reliability: Reliability,
)

/** Raw road way as delivered by the map layer (OSM tags preserved, no interpretation). */
data class RoadWay(
    val osmWayId: Long,
    val geometry: List<GeoPoint>,
    val tags: Map<String, String>,
)

/** Raw mapped crossing node / feature (OSM tags preserved). */
data class MappedFeature(
    val osmNodeId: Long,
    val point: GeoPoint,
    val tags: Map<String, String>,
)

/** Raw map data near a point. Implemented in :mapdata (Overpass). */
data class RoadMetadata(
    val center: GeoPoint,
    val radiusM: Double,
    val ways: List<RoadWay>,
    /** highway=crossing, crossing=*, entrance=*, highway=steps nodes, traffic_signals, kerb, etc. */
    val features: List<MappedFeature>,
    val fetchedAtMs: Long,
)

interface RoadMetadataService {
    suspend fun roadsNear(point: GeoPoint, radiusM: Double): Outcome<RoadMetadata>
}

/** Interpreted road segment near the user. */
data class RoadSegmentContext(
    val osmWayId: Long?,
    val name: String?,
    val highwayType: String?,
    val geometry: List<GeoPoint>,
    val directionality: RoadDirectionality,
    /** 0 flows when UNKNOWN; 1 when ONE_WAY; 2 (opposite bearings) when TWO_WAY. */
    val expectedFlows: List<ExpectedFlow>,
    val lanes: Int?,
    val maxSpeedKph: Int?,
    val distanceM: Double?,
    val source: DataSource,
    val reliability: Reliability,
)

enum class CrossingControl { TRAFFIC_SIGNALS, MARKED_ZEBRA, UNMARKED, UNCONTROLLED, UNKNOWN }

enum class RefugeStatus { MAPPED, NOT_MAPPED, UNKNOWN }

/** Crossing evidence of different kinds. These are deliberately NOT interchangeable. */
sealed interface CrossingCandidate {
    val reliability: Reliability

    /** A crossing that exists in map data. Does not mean the user is at it. */
    data class MappedCrossing(
        val osmNodeId: Long,
        val point: GeoPoint,
        val control: CrossingControl,
        val refuge: RefugeStatus,
        val hasTactilePaving: Boolean?,
        val hasAudibleSignal: Boolean?,
        val distanceM: Double?,
        override val reliability: Reliability = Reliability.MAPPED,
    ) : CrossingCandidate

    /** Zebra stripes observed by the camera, persistence-gated. Location is NOT known precisely. */
    data class VisualCrosswalk(
        val score: Float,
        val persistedMs: Long,
        override val reliability: Reliability = Reliability.LOW,
    ) : CrossingCandidate

    /** The route crosses a road but no crossing is mapped there. */
    data class PossibleCrossing(
        val point: GeoPoint?,
        val roadName: String?,
        val distanceM: Double?,
        override val reliability: Reliability = Reliability.INFERRED,
    ) : CrossingCandidate
}

/**
 * Whether the user is confirmed to be at the crossing point. GPS proximity alone yields
 * [UNCONFIRMED]; only explicit user confirmation yields [USER_CONFIRMED].
 */
enum class UserPositionAssessment { NOT_NEAR, NEAR_UNCONFIRMED, USER_CONFIRMED, UNKNOWN }

data class RoadCrossingContext(
    /** Null when GPS is unavailable — never fabricated. */
    val location: GeoPoint?,
    val locationAccuracyM: Float?,
    val roads: List<RoadSegmentContext>,
    val crossings: List<CrossingCandidate>,
    val userPosition: UserPositionAssessment,
    val uncertainties: Set<UncertaintyReason>,
    val elapsedMs: Long,
)

/** Map-vs-camera comparison outcome. */
enum class FlowComparison { EXPECTED_FLOW_MATCH, EXPECTED_FLOW_MISMATCH, INSUFFICIENT_INFORMATION }

/** Whether image-left/right can be mapped to the user's world-left/right. */
data class CameraOrientationEstimate(
    /** Bearing the rear camera faces, degrees true. Null if unknown. */
    val cameraBearingDeg: Double?,
    val reliability: Reliability,
    val reasons: Set<UncertaintyReason>,
) {
    val isReliable: Boolean get() = reliability == Reliability.RELIABLE && cameraBearingDeg != null
}

enum class CrossingAssistState {
    INACTIVE,
    APPROACH_CONTEXT_AVAILABLE,
    USER_STOPPED_FOR_OBSERVATION,
    OBSERVING_TRAFFIC,
    OBSERVATION_UNCERTAIN,
    OBSERVATION_COMPLETE,
    SEGMENT_TRANSITION,
    EXITED,
}

/** Stages of a multi-stage crossing. Only entered on map evidence or user confirmation. */
enum class CrossingStage { SIDEWALK, ROAD_SEGMENT_1, REFUGE, ROAD_SEGMENT_2, OPPOSITE_SIDEWALK, UNKNOWN }
