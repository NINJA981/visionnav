package com.visionnav.core.crossing

import com.visionnav.core.common.DataSource
import com.visionnav.core.common.Reliability
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.navigation.Maneuver
import com.visionnav.core.navigation.ManeuverType
import com.visionnav.core.navigation.Route
import com.visionnav.core.sensing.LocationFix
import com.visionnav.core.tracking.ApproachState
import com.visionnav.core.tracking.ImageMotion
import com.visionnav.core.tracking.MotionEstimate
import com.visionnav.core.vision.AnalysisDemand
import com.visionnav.core.vision.CameraState
import com.visionnav.core.vision.FrameFeed
import com.visionnav.core.vision.FrameObservation
import com.visionnav.core.vision.ObjectClass
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared test geometry: the user stands at [USER]; offsets are in metres (north, east). */
object Fx {
    val USER = GeoPoint(13.0, 80.0)

    fun at(northM: Double, eastM: Double, origin: GeoPoint = USER): GeoPoint =
        GeoMath.destination(GeoMath.destination(origin, 0.0, northM), 90.0, eastM)

    fun fix(point: GeoPoint = USER, accuracyM: Float = 5f, elapsedMs: Long = 0) =
        LocationFix(point, accuracyM, speedMps = 0f, courseDeg = null, courseAccuracyDeg = null, elapsedMs = elapsedMs)

    /** East-west way [northM] metres north of the user, node order west → east (bearing ≈ 90°). */
    fun eastWestWay(id: Long = 1, northM: Double = 10.0, tags: Map<String, String> = mapOf("highway" to "residential")) =
        RoadWay(id, listOf(at(northM, -60.0), at(northM, 0.0), at(northM, 60.0)), tags)

    fun metadata(ways: List<RoadWay> = emptyList(), features: List<MappedFeature> = emptyList()) =
        RoadMetadata(center = USER, radiusM = 100.0, ways = ways, features = features, fetchedAtMs = 0)

    fun feature(id: Long, point: GeoPoint, tags: Map<String, String>) = MappedFeature(id, point, tags)

    /** A route heading due north from 10 m behind the user to 80 m ahead. */
    fun northRoute(maneuvers: List<Maneuver> = emptyList()) = Route(
        geometry = listOf(at(-10.0, 0.0), at(0.0, 0.0), at(30.0, 0.0), at(80.0, 0.0)),
        maneuvers = maneuvers,
        lengthM = 90.0,
        durationS = 70.0,
    )

    fun crossingManeuver(shapeIndex: Int, street: String? = "Anna Salai") = Maneuver(
        type = ManeuverType.CROSS_ROAD,
        engineInstruction = "Cross",
        streetNames = listOfNotNull(street),
        beginShapeIndex = shapeIndex,
        lengthM = 10.0,
        bearingBeforeDeg = 0.0,
        bearingAfterDeg = 0.0,
        isCrossing = true,
    )

    fun estimate(
        trackId: Long,
        cls: ObjectClass = ObjectClass.CAR,
        imageMotion: ImageMotion = ImageMotion.MOVING_RIGHT_IN_IMAGE,
        approach: ApproachState = ApproachState.CROSSING,
        reasons: Set<UncertaintyReason> = emptySet(),
        spanMs: Long = 1_500,
    ) = MotionEstimate(
        trackId = trackId,
        objectClass = cls,
        imageMotion = imageMotion,
        approach = approach,
        confidence = 0.9f,
        lateralSpeed = null,
        scaleGrowthRate = null,
        observationCount = 10,
        observationSpanMs = spanMs,
        reasons = reasons,
    )

    fun reliable(bearing: Double = 0.0) = CameraOrientationEstimate(bearing, Reliability.RELIABLE, emptySet())

    fun unreliable(bearing: Double? = 0.0) =
        CameraOrientationEstimate(bearing, Reliability.LOW, setOf(UncertaintyReason.HEADING_UNRELIABLE))

    /** Interpreted road segment (for fusion tests) with the given flows. */
    fun road(
        directionality: RoadDirectionality,
        flowBearings: List<Double>,
        reliability: Reliability = Reliability.MAPPED,
        distanceM: Double? = 10.0,
    ) = RoadSegmentContext(
        osmWayId = 1,
        name = "Test Road",
        highwayType = "residential",
        geometry = listOf(at(10.0, -60.0), at(10.0, 60.0)),
        directionality = directionality,
        expectedFlows = flowBearings.map { ExpectedFlow(it, DataSource.OSM_MAP, reliability) },
        lanes = null,
        maxSpeedKph = null,
        distanceM = distanceM,
        source = DataSource.OSM_MAP,
        reliability = reliability,
    )

    fun context(
        roads: List<RoadSegmentContext> = emptyList(),
        crossings: List<CrossingCandidate> = emptyList(),
        location: GeoPoint? = USER,
        accuracyM: Float? = 5f,
        userPosition: UserPositionAssessment = UserPositionAssessment.NEAR_UNCONFIRMED,
        uncertainties: Set<UncertaintyReason> = emptySet(),
        elapsedMs: Long = 0,
    ) = RoadCrossingContext(location, accuracyM, roads, crossings, userPosition, uncertainties, elapsedMs)

    fun mappedCrossing(
        distanceM: Double = 8.0,
        refuge: RefugeStatus = RefugeStatus.UNKNOWN,
        control: CrossingControl = CrossingControl.TRAFFIC_SIGNALS,
        id: Long = 100,
    ) = CrossingCandidate.MappedCrossing(id, at(distanceM, 0.0), control, refuge, null, null, distanceM)
}

/** Records every interaction; never produces frames. */
class FakeFrameFeed : FrameFeed {
    val demandCalls = mutableListOf<Pair<String, AnalysisDemand>>()
    var torchCalls = 0
    override val frames: SharedFlow<FrameObservation> = MutableSharedFlow()
    override val cameraState: StateFlow<CameraState> = MutableStateFlow(CameraState.Inactive)
    override fun setDemand(clientId: String, demand: AnalysisDemand) {
        demandCalls += clientId to demand
    }
    override fun setTorch(enabled: Boolean) {
        torchCalls++
    }
}
