package com.visionnav.core.announce

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** Broad coverage of the frozen [SafetyPhraseGuard] against realistic sentences (test 24 support). */
class SafetyPhraseGuardPhrasesTest {
    private val guard = SafetyPhraseGuard()

    private val forbidden = listOf(
        "It is safe to cross now",
        "It is safe to cross now.",
        "IT IS SAFE TO CROSS",
        "It's safe to cross.",
        "It’s safe to go.",
        "Safe to cross.",
        "Safe to walk now.",
        "It is now safe to proceed.",
        "Now safe.",
        "The road is clear.",
        "Road clear.",
        "The street is empty.",
        "Crossing is clear, go.",
        "The path is free.",
        "Traffic is clear.",
        "Cross now.",
        "Cross now!",
        "You can cross.",
        "You may cross the road.",
        "Go ahead and cross.",
        "Okay to cross.",
        "OK to cross.",
        "Time to cross.",
        "Begin to cross now.",
        "No vehicles are coming.",
        "No cars approaching.",
        "No traffic is coming.",
        "Traffic has stopped.",
        "Traffic stopped.",
        "All clear.",
        "All clear!",
        "Go",
        "Walk.",
        "Cross!",
    )

    private val allowed = listOf(
        "Road crossing ahead. Stop at the kerb and assess traffic.",
        "Vehicle approaching.",
        "Traffic view unclear.",
        "Traffic view unclear. Low light.",
        "Vehicle moving left to right in the camera view.",
        "Vehicle moving away.",
        "Vehicle detected, direction unclear.",
        "Several vehicles moving in the camera view.",
        "Possible crossing ahead. Stop and assess your surroundings.",
        "Mapped crossing with signals ahead.",
        "One-way road, traffic expected from your left.",
        "Road direction unavailable.",
        "Camera unavailable.",
        "Camera sees ahead only.",
        "Traffic view limited to the front.",
        "Turn right onto Anna Salai in 40 metres.",
        "Turn left now.",
        "You have arrived near Central Station. The destination is on your right.",
        "Arrival point near. Exact entrance not confirmed.",
        "You may be off route. Recalculating.",
        "Location unavailable.",
        "Stop. Obstacle directly ahead.",
        "Go to the settings screen to change voices.",
        "Walk straight for 100 metres.",
        "Road crossing in 40 metres.",
    )

    @Test
    fun `forbidden sentences are blocked`() {
        for (s in forbidden) assertWithMessage(s).that(guard.isAllowed(s)).isFalse()
    }

    @Test
    fun `allowed sentences pass`() {
        for (s in allowed) assertWithMessage(s).that(guard.isAllowed(s)).isTrue()
    }
}
