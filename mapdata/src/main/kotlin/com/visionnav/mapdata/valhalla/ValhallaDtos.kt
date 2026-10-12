package com.visionnav.mapdata.valhalla

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models for Valhalla's native `/route` JSON (https://valhalla.github.io/valhalla/api/route/api-reference/).
// Every field is optional/defaulted: the parser decides what is mandatory.

@Serializable
internal data class ValhallaResponse(
    val trip: ValhallaTrip? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val error: String? = null,
    @SerialName("status_code") val statusCode: Int? = null,
    val status: String? = null,
)

@Serializable
internal data class ValhallaTrip(
    val legs: List<ValhallaLeg> = emptyList(),
    val summary: ValhallaSummary? = null,
    /** "kilometers" or "miles". */
    val units: String? = null,
    val status: Int? = null,
)

@Serializable
internal data class ValhallaLeg(
    val maneuvers: List<ValhallaManeuver> = emptyList(),
    val summary: ValhallaSummary? = null,
    val shape: String? = null,
)

@Serializable
internal data class ValhallaSummary(
    val length: Double? = null,
    val time: Double? = null,
)

@Serializable
internal data class ValhallaManeuver(
    val type: Int = 0,
    val instruction: String = "",
    @SerialName("street_names") val streetNames: List<String> = emptyList(),
    @SerialName("begin_street_names") val beginStreetNames: List<String> = emptyList(),
    val time: Double? = null,
    /** In the trip's units (km when requested as "kilometers"). */
    val length: Double? = null,
    @SerialName("begin_shape_index") val beginShapeIndex: Int = 0,
    @SerialName("end_shape_index") val endShapeIndex: Int? = null,
    @SerialName("bearing_before") val bearingBefore: Double? = null,
    @SerialName("bearing_after") val bearingAfter: Double? = null,
    @SerialName("travel_mode") val travelMode: String? = null,
    @SerialName("travel_type") val travelType: String? = null,
)
