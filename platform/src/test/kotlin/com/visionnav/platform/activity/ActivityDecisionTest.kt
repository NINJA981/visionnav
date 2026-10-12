package com.visionnav.platform.activity

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.sensing.MotionActivity
import org.junit.Test

class ActivityDecisionTest {

    private fun input(
        now: Long = 10_000L,
        usable: Boolean = true,
        listeningSince: Long? = 0L,
        lastStep: Long? = null,
        speed: Float? = null,
        previous: MotionActivity = MotionActivity.UNKNOWN,
    ) = ActivityInputs(now, usable, listeningSince, lastStep, speed, previous)

    private fun decide(i: ActivityInputs) = ActivityDecision.decide(i)

    @Test
    fun `recent step means walking`() {
        assertThat(decide(input(lastStep = 9_000L))).isEqualTo(MotionActivity.WALKING)
        assertThat(decide(input(lastStep = 8_000L))).isEqualTo(MotionActivity.WALKING) // exactly 2 s
    }

    @Test
    fun `reliable speed above 0_6 means walking even without step sensor`() {
        assertThat(decide(input(usable = false, listeningSince = null, speed = 1.1f)))
            .isEqualTo(MotionActivity.WALKING)
        assertThat(decide(input(usable = false, listeningSince = null, speed = 0.6f)))
            .isEqualTo(MotionActivity.UNKNOWN)
    }

    @Test
    fun `no steps for 2_5 s with low or unknown speed is stationary`() {
        assertThat(decide(input(lastStep = 7_500L))).isEqualTo(MotionActivity.STATIONARY)
        assertThat(decide(input(lastStep = 7_000L, speed = 0.2f))).isEqualTo(MotionActivity.STATIONARY)
        // Never stepped since listening began 3 s ago.
        assertThat(decide(input(listeningSince = 7_000L))).isEqualTo(MotionActivity.STATIONARY)
    }

    @Test
    fun `not enough listening time yet keeps previous`() {
        assertThat(decide(input(listeningSince = 9_000L, previous = MotionActivity.UNKNOWN)))
            .isEqualTo(MotionActivity.UNKNOWN)
    }

    @Test
    fun `hysteresis band keeps previous`() {
        // Last step 2.2 s ago: neither recent nor quiet long enough.
        assertThat(decide(input(lastStep = 7_800L, previous = MotionActivity.WALKING)))
            .isEqualTo(MotionActivity.WALKING)
        // Quiet but moderate speed: ambiguous.
        assertThat(decide(input(lastStep = 5_000L, speed = 0.45f, previous = MotionActivity.WALKING)))
            .isEqualTo(MotionActivity.WALKING)
    }

    @Test
    fun `gps speed alone never declares stationary`() {
        assertThat(decide(input(usable = false, listeningSince = null, speed = 0.0f)))
            .isEqualTo(MotionActivity.UNKNOWN)
    }

    @Test
    fun `no step sensor and no reliable speed is unknown`() {
        assertThat(decide(input(usable = false, listeningSince = null, previous = MotionActivity.WALKING)))
            .isEqualTo(MotionActivity.UNKNOWN)
    }

    @Test
    fun `permission missing makes stale step data irrelevant`() {
        // A step timestamp recorded before the permission was revoked must not count.
        assertThat(decide(input(usable = false, listeningSince = null, lastStep = 9_500L)))
            .isEqualTo(MotionActivity.UNKNOWN)
    }
}
