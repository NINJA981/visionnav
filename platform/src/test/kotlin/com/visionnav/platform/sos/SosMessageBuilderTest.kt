package com.visionnav.platform.sos

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.sensing.LocationFix
import org.junit.Test

class SosMessageBuilderTest {

    private val fix = LocationFix(
        point = GeoPoint(13.0827, 80.2707),
        accuracyM = 11.2f,
        speedMps = null,
        courseDeg = null,
        courseAccuracyDeg = null,
        elapsedMs = 1_000L,
    )

    @Test
    fun `message with a fix has an OSM link and accuracy`() {
        assertThat(SosMessageBuilder.buildMessage(fix)).isEqualTo(
            "AURA SOS: I need help. My location: " +
                "https://www.openstreetmap.org/?mlat=13.082700&mlon=80.270700 (accuracy ~12 m)",
        )
    }

    @Test
    fun `message without a fix says location unavailable and has no coordinates`() {
        val msg = SosMessageBuilder.buildMessage(null)
        assertThat(msg).isEqualTo("AURA SOS: I need help. My location: location unavailable.")
        assertThat(msg).doesNotContain("mlat")
    }

    @Test
    fun `negative coordinates use a dot decimal separator`() {
        val south = fix.copy(point = GeoPoint(-33.865143, -151.2099), accuracyM = 0.2f)
        assertThat(SosMessageBuilder.buildMessage(south))
            .contains("mlat=-33.865143&mlon=-151.209900 (accuracy ~1 m)")
    }

    @Test
    fun `old fix is labelled with its age`() {
        val msg = SosMessageBuilder.buildMessage(fix, fixAgeMs = 5 * 60_000L)
        assertThat(msg).endsWith("(accuracy ~12 m, last known position about 5 min ago)")
        assertThat(SosMessageBuilder.buildMessage(fix, fixAgeMs = 5_000L)).endsWith("(accuracy ~12 m)")
    }

    @Test
    fun `numbers are cleaned, validated and de-duplicated`() {
        val cleaned = SosMessageBuilder.normalizeNumbers(
            listOf(" +91 98765-43210 ", "(044) 2345.6789", "", "abc", "12", "+919876543210"),
        )
        assertThat(cleaned).containsExactly("+919876543210", "04423456789").inOrder()
    }
}
