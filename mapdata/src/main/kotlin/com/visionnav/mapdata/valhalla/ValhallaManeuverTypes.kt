package com.visionnav.mapdata.valhalla

import com.visionnav.core.navigation.ManeuverType

/**
 * Valhalla maneuver `type` codes (DirectionsLeg_Maneuver_Type), verified against the official
 * route API reference (https://valhalla.github.io/valhalla/api/route/api-reference/):
 *
 * ```
 *  0 None              11 SharpRight        22 StayStraight     33 TransitConnectionStart
 *  1 Start             12 UturnRight        23 StayRight        34 TransitConnectionTransfer
 *  2 StartRight        13 UturnLeft         24 StayLeft         35 TransitConnectionDestination
 *  3 StartLeft         14 SharpLeft         25 Merge            36 PostTransitConnectionDestination
 *  4 Destination       15 Left              26 RoundaboutEnter  37 MergeRight
 *  5 DestinationRight  16 SlightLeft        27 RoundaboutExit   38 MergeLeft
 *  6 DestinationLeft   17 RampStraight      28 FerryEnter       39 ElevatorEnter
 *  7 Becomes           18 RampRight         29 FerryExit        40 StepsEnter
 *  8 Continue          19 RampLeft          30 Transit          41 EscalatorEnter
 *  9 SlightRight       20 ExitRight         31 TransitTransfer  42 BuildingEnter
 * 10 Right             21 ExitLeft          32 TransitRemainOn  43 BuildingExit
 * ```
 *
 * `StartRight`/`StartLeft` describe the side of the street the trip starts on, not a turn,
 * so they map to [ManeuverType.START]. Car-oriented codes (ramps, exits, stay, merge) are mapped
 * to the equivalent geometric direction; transit/ferry codes have no pedestrian equivalent → OTHER.
 */
object ValhallaManeuverTypes {
    const val NONE = 0
    const val START = 1
    const val START_RIGHT = 2
    const val START_LEFT = 3
    const val DESTINATION = 4
    const val DESTINATION_RIGHT = 5
    const val DESTINATION_LEFT = 6
    const val BECOMES = 7
    const val CONTINUE = 8
    const val SLIGHT_RIGHT = 9
    const val RIGHT = 10
    const val SHARP_RIGHT = 11
    const val UTURN_RIGHT = 12
    const val UTURN_LEFT = 13
    const val SHARP_LEFT = 14
    const val LEFT = 15
    const val SLIGHT_LEFT = 16
    const val RAMP_STRAIGHT = 17
    const val RAMP_RIGHT = 18
    const val RAMP_LEFT = 19
    const val EXIT_RIGHT = 20
    const val EXIT_LEFT = 21
    const val STAY_STRAIGHT = 22
    const val STAY_RIGHT = 23
    const val STAY_LEFT = 24
    const val MERGE = 25
    const val ROUNDABOUT_ENTER = 26
    const val ROUNDABOUT_EXIT = 27
    const val FERRY_ENTER = 28
    const val FERRY_EXIT = 29
    const val MERGE_RIGHT = 37
    const val MERGE_LEFT = 38
    const val ELEVATOR_ENTER = 39
    const val STEPS_ENTER = 40
    const val ESCALATOR_ENTER = 41
    const val BUILDING_ENTER = 42
    const val BUILDING_EXIT = 43

    fun toManeuverType(code: Int): ManeuverType = when (code) {
        START, START_RIGHT, START_LEFT -> ManeuverType.START
        DESTINATION -> ManeuverType.ARRIVE
        DESTINATION_RIGHT -> ManeuverType.ARRIVE_RIGHT
        DESTINATION_LEFT -> ManeuverType.ARRIVE_LEFT
        BECOMES, CONTINUE, RAMP_STRAIGHT, STAY_STRAIGHT, MERGE -> ManeuverType.CONTINUE
        SLIGHT_RIGHT, RAMP_RIGHT, EXIT_RIGHT, STAY_RIGHT, MERGE_RIGHT -> ManeuverType.SLIGHT_RIGHT
        RIGHT -> ManeuverType.RIGHT
        SHARP_RIGHT -> ManeuverType.SHARP_RIGHT
        UTURN_RIGHT, UTURN_LEFT -> ManeuverType.U_TURN
        SHARP_LEFT -> ManeuverType.SHARP_LEFT
        LEFT -> ManeuverType.LEFT
        SLIGHT_LEFT, RAMP_LEFT, EXIT_LEFT, STAY_LEFT, MERGE_LEFT -> ManeuverType.SLIGHT_LEFT
        ROUNDABOUT_ENTER -> ManeuverType.ENTER_ROUNDABOUT
        ROUNDABOUT_EXIT -> ManeuverType.EXIT_ROUNDABOUT
        ELEVATOR_ENTER -> ManeuverType.ELEVATOR
        STEPS_ENTER -> ManeuverType.STAIRS
        ESCALATOR_ENTER -> ManeuverType.ESCALATOR
        BUILDING_ENTER -> ManeuverType.ENTER_BUILDING
        BUILDING_EXIT -> ManeuverType.EXIT_BUILDING
        else -> ManeuverType.OTHER // 0 None, 28–36 ferry/transit, unknown future codes
    }
}
