package com.visionnav.core.safety

import com.visionnav.core.tracking.ApproachState
import com.visionnav.core.vision.ObjectClass

/** Relative proximity from image geometry only. Never converted to metres. */
enum class ProximityBand { FAR, MEDIUM, NEAR, VERY_NEAR, UNKNOWN }

/** Horizontal position in the camera view, as a clock-face-ish bucket. */
enum class ViewSector { FAR_LEFT, LEFT, CENTER, RIGHT, FAR_RIGHT }

enum class SafetySeverity { INFO, CAUTION, WARNING, CRITICAL }

/** A safety-relevant event derived from tracks. Consumed by the announcement layer. */
data class SafetyEvent(
    val trackId: Long,
    val objectClass: ObjectClass,
    val severity: SafetySeverity,
    val sector: ViewSector,
    val proximity: ProximityBand,
    val approach: ApproachState,
    val inWalkingPath: Boolean,
    val elapsedMs: Long,
)
