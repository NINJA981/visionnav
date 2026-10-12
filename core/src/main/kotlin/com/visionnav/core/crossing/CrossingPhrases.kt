package com.visionnav.core.crossing

import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.vision.ObjectClass

/** Which side traffic on a one-way road comes from, relative to the camera's facing. */
enum class TrafficSide { LEFT, RIGHT }

/**
 * What is said about one tracked vehicle. IMAGE_* wording describes the camera view only;
 * WORLD_* wording is used only when camera orientation is RELIABLE (see [FlowFusion.worldLateralDirection]).
 */
enum class TrackDescription {
    APPROACHING,
    MOVING_AWAY,
    IMAGE_LEFT_TO_RIGHT,
    IMAGE_RIGHT_TO_LEFT,
    WORLD_FROM_LEFT,
    WORLD_FROM_RIGHT,
    DIRECTION_UNCLEAR,
    /** Used only when the user explicitly asks for a traffic summary. */
    NO_CLEAR_MOTION,
}

/**
 * The complete crossing-mode vocabulary. Every string the crossing package can speak is produced here,
 * and [catalog] enumerates all of them so tests can check each against [com.visionnav.core.announce.SafetyPhraseGuard].
 *
 * Wording rules (docs/PLAN.md §0):
 * - Describe observations and coverage limits; never assure safety, never tell the user to cross.
 * - The absence of detections is never phrased as reassurance.
 * - "Your left/right" only for world-frame estimates; otherwise "in the camera view".
 */
object CrossingPhrases {
    const val POSSIBLE_CROSSING = "Possible crossing ahead. Stop and assess your surroundings."
    const val VISUAL_CROSSWALK = "Possible crosswalk markings in the camera view."
    const val OBSERVING = "Observing traffic. Camera sees ahead only."
    const val COVERAGE_REMINDER = "Camera sees ahead only."
    const val OBSERVATION_ENDED = "Observation ended."
    const val SEVERAL_VEHICLES = "Several vehicles moving in the camera view."
    const val AGAINST_EXPECTED = "Vehicle moving against the expected direction."
    const val NO_VEHICLES_ON_REQUEST = "No vehicles recognised in the camera view. Camera sees ahead only."
    const val ONE_WAY = "One-way road."
    const val ROUNDABOUT = "Roundabout. Traffic likely one-way."
    const val TWO_WAY = "Two-way road."
    const val ROAD_DIRECTION_UNAVAILABLE = "Road direction unavailable."
    const val REFUGE_UNCONFIRMED = "Refuge island not confirmed."

    fun oneWayFrom(side: TrafficSide): String = when (side) {
        TrafficSide.LEFT -> "One-way road. Traffic expected from your left."
        TrafficSide.RIGHT -> "One-way road. Traffic expected from your right."
    }

    fun mappedCrossing(control: CrossingControl, refuge: RefugeStatus, audibleSignal: Boolean?): String {
        val base = when (control) {
            CrossingControl.TRAFFIC_SIGNALS -> "Mapped crossing with traffic signals ahead."
            CrossingControl.MARKED_ZEBRA -> "Mapped zebra crossing ahead."
            CrossingControl.UNCONTROLLED -> "Mapped crossing without signals ahead."
            CrossingControl.UNMARKED -> "Mapped unmarked crossing ahead."
            CrossingControl.UNKNOWN -> if (refuge == RefugeStatus.MAPPED) "Crossing ahead." else "Mapped crossing ahead."
        }
        val parts = mutableListOf(base)
        if (refuge == RefugeStatus.MAPPED) parts += "Refuge island mapped."
        if (audibleSignal == true) parts += "Audible signal mapped."
        return parts.joinToString(" ")
    }

    /** Spoken noun for a vehicle class; real class names when known. */
    fun vehicleNoun(objectClass: ObjectClass): String = when (objectClass) {
        ObjectClass.CAR -> "Car"
        ObjectClass.BUS -> "Bus"
        ObjectClass.MOTORCYCLE -> "Motorcycle"
        ObjectClass.TRUCK -> "Truck"
        ObjectClass.BICYCLE -> "Bicycle"
        else -> "Vehicle"
    }

    fun track(objectClass: ObjectClass, description: TrackDescription): String {
        val noun = vehicleNoun(objectClass)
        return when (description) {
            TrackDescription.APPROACHING -> "$noun approaching."
            TrackDescription.MOVING_AWAY -> "$noun moving away."
            TrackDescription.IMAGE_LEFT_TO_RIGHT -> "$noun moving left to right in the camera view."
            TrackDescription.IMAGE_RIGHT_TO_LEFT -> "$noun moving right to left in the camera view."
            TrackDescription.WORLD_FROM_LEFT -> "$noun passing from your left."
            TrackDescription.WORLD_FROM_RIGHT -> "$noun passing from your right."
            TrackDescription.DIRECTION_UNCLEAR -> "$noun detected, direction unclear."
            TrackDescription.NO_CLEAR_MOTION -> "$noun in the camera view, motion unclear."
        }
    }

    /** One short phrase per uncertainty reason (docs/PLAN.md §5). */
    fun uncertainty(reason: UncertaintyReason): String = when (reason) {
        UncertaintyReason.GPS_UNAVAILABLE -> "Location unavailable."
        UncertaintyReason.GPS_LOW_ACCURACY -> "Location accuracy low."
        UncertaintyReason.HEADING_UNAVAILABLE -> "Compass unavailable. Describing the camera view only."
        UncertaintyReason.HEADING_UNRELIABLE -> "Compass unreliable. Describing the camera view only."
        UncertaintyReason.ORIENTATION_UNRELIABLE -> "Phone orientation unclear. Describing the camera view only."
        UncertaintyReason.ROAD_DIRECTION_UNAVAILABLE -> ROAD_DIRECTION_UNAVAILABLE
        UncertaintyReason.MAP_DATA_UNAVAILABLE -> "Map data unavailable."
        UncertaintyReason.CAMERA_UNAVAILABLE -> "Camera unavailable."
        UncertaintyReason.CAMERA_OBSTRUCTED -> "Camera view blocked."
        UncertaintyReason.LOW_LIGHT -> "Traffic view unclear. Low light."
        UncertaintyReason.GLARE -> "Traffic view unclear. Glare."
        UncertaintyReason.BLUR -> "Traffic view unclear. Image blurred."
        UncertaintyReason.CAMERA_MOVING -> "Traffic view unclear. Camera moving."
        UncertaintyReason.DETECTOR_UNAVAILABLE -> "Vehicle detection unavailable."
        UncertaintyReason.TRACK_TOO_SHORT,
        UncertaintyReason.MOTION_INCONSISTENT,
        UncertaintyReason.IDENTITY_SWITCH,
        -> "Vehicle detected, direction unclear."
        UncertaintyReason.CROSSING_AMBIGUOUS -> "Several crossings nearby. Exact crossing unclear."
    }

    /** Every string this package can produce. */
    fun catalog(): Set<String> = buildSet {
        addAll(
            listOf(
                POSSIBLE_CROSSING, VISUAL_CROSSWALK, OBSERVING, COVERAGE_REMINDER, OBSERVATION_ENDED,
                SEVERAL_VEHICLES, AGAINST_EXPECTED, NO_VEHICLES_ON_REQUEST, ONE_WAY, ROUNDABOUT, TWO_WAY,
                ROAD_DIRECTION_UNAVAILABLE, REFUGE_UNCONFIRMED,
            ),
        )
        TrafficSide.entries.forEach { add(oneWayFrom(it)) }
        for (c in CrossingControl.entries) for (r in RefugeStatus.entries) for (a in listOf(true, false, null)) {
            add(mappedCrossing(c, r, a))
        }
        for (cls in ObjectClass.entries) for (d in TrackDescription.entries) add(track(cls, d))
        UncertaintyReason.entries.forEach { add(uncertainty(it)) }
    }
}
