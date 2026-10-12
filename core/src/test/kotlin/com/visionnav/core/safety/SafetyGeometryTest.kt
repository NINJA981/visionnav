package com.visionnav.core.safety

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.tracking.TrackFixtures.boxAt
import com.visionnav.core.tracking.TrackFixtures.boxBottom
import com.visionnav.core.vision.NormBox
import com.visionnav.core.vision.ObjectClass
import org.junit.Test

class SafetyGeometryTest {

    private val g = SafetyGeometry()

    @Test
    fun `sectors are centre-x quintiles`() {
        assertThat(g.sector(boxAt(0.1f))).isEqualTo(ViewSector.FAR_LEFT)
        assertThat(g.sector(boxAt(0.3f))).isEqualTo(ViewSector.LEFT)
        assertThat(g.sector(boxAt(0.5f))).isEqualTo(ViewSector.CENTER)
        assertThat(g.sector(boxAt(0.7f))).isEqualTo(ViewSector.RIGHT)
        assertThat(g.sector(boxAt(0.9f))).isEqualTo(ViewSector.FAR_RIGHT)
    }

    @Test
    fun `proximity is class aware`() {
        val box = boxBottom(0.5f, bottom = 0.8f, w = 0.2f, h = 0.5f)
        assertThat(g.proximity(ObjectClass.CAR, box)).isEqualTo(ProximityBand.VERY_NEAR)
        assertThat(g.proximity(ObjectClass.PERSON, box)).isEqualTo(ProximityBand.NEAR)
        assertThat(g.proximity(ObjectClass.BUS, box)).isEqualTo(ProximityBand.NEAR)
        val small = boxBottom(0.5f, bottom = 0.7f, w = 0.1f, h = 0.15f)
        assertThat(g.proximity(ObjectClass.CAR, small)).isEqualTo(ProximityBand.MEDIUM)
        assertThat(g.proximity(ObjectClass.BUS, small)).isEqualTo(ProximityBand.FAR)
    }

    @Test
    fun `box reaching the bottom edge is at least NEAR for ground objects but not for elevated ones`() {
        val clipped = boxBottom(0.5f, bottom = 0.99f, w = 0.05f, h = 0.05f)
        assertThat(g.proximity(ObjectClass.PERSON, clipped)).isEqualTo(ProximityBand.NEAR)
        assertThat(g.proximity(ObjectClass.TRAFFIC_LIGHT, clipped)).isEqualTo(ProximityBand.FAR)
        assertThat(g.proximity(ObjectClass.PERSON, boxBottom(0.5f, bottom = 0.9f, w = 0.05f, h = 0.05f))).isEqualTo(ProximityBand.MEDIUM)
    }

    @Test
    fun `degenerate box has UNKNOWN proximity`() {
        assertThat(g.proximity(ObjectClass.CAR, NormBox(0.5f, 0.5f, 0.5f, 0.5f))).isEqualTo(ProximityBand.UNKNOWN)
    }

    @Test
    fun `walking path is the central corridor in the lower image`() {
        assertThat(g.inWalkingPath(boxBottom(0.5f, bottom = 0.9f, w = 0.1f, h = 0.3f))).isTrue()
        assertThat(g.inWalkingPath(boxBottom(0.3f, bottom = 0.9f, w = 0.12f, h = 0.3f))).isTrue() // overlaps 0.35
        assertThat(g.inWalkingPath(boxBottom(0.2f, bottom = 0.9f, w = 0.1f, h = 0.3f))).isFalse()
        assertThat(g.inWalkingPath(boxBottom(0.5f, bottom = 0.35f, w = 0.1f, h = 0.2f))).isFalse() // above lower 60 %
    }
}
