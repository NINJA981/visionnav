package com.visionnav.platform.location

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.UncertaintyReason
import com.visionnav.core.geo.GeoPoint
import com.visionnav.core.sensing.LocationFix
import com.visionnav.core.sensing.LocationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FusedLocationSourceTest {

    private class FakeClient : LocationUpdatesClient {
        var permission = true
        var requestCount = 0
        var removeCount = 0
        var listener: LocationUpdatesClient.Listener? = null

        override fun hasPermission() = permission

        override fun requestUpdates(listener: LocationUpdatesClient.Listener) {
            if (!permission) throw SecurityException()
            requestCount++
            this.listener = listener
        }

        override fun removeUpdates() {
            removeCount++
            listener = null
        }
    }

    private val unavailable = LocationState.Unavailable(UncertaintyReason.GPS_UNAVAILABLE)

    private fun fix(elapsedMs: Long, accuracy: Float = 5f) = LocationFix(
        point = GeoPoint(13.0827, 80.2707),
        accuracyM = accuracy,
        speedMps = 1.2f,
        courseDeg = 90f,
        courseAccuracyDeg = 10f,
        elapsedMs = elapsedMs,
    )

    private fun TestScope.newSource(client: FakeClient) =
        FusedLocationSource(client, { testScheduler.currentTime }, backgroundScope)

    @Test
    fun `many starts open a single subscription, closed only by the last stop`() = runTest {
        val client = FakeClient()
        val source = newSource(client)
        source.start()
        source.start()
        source.start()
        assertThat(client.requestCount).isEqualTo(1)
        assertThat(source.holders).isEqualTo(3)
        source.stop()
        source.stop()
        assertThat(client.removeCount).isEqualTo(0)
        source.stop()
        assertThat(client.removeCount).isEqualTo(1)
        source.stop() // unbalanced: ignored
        assertThat(client.removeCount).isEqualTo(1)
        source.start()
        assertThat(client.requestCount).isEqualTo(2)
        source.stop()
    }

    @Test
    fun `fix becomes Available and poor accuracy is still Available`() = runTest {
        val client = FakeClient()
        val source = newSource(client)
        assertThat(source.state.value).isEqualTo(unavailable)
        source.start()
        val f = fix(testScheduler.currentTime, accuracy = 80f)
        client.listener!!.onFix(f)
        assertThat(source.state.value).isEqualTo(LocationState.Available(f))
        source.stop()
        assertThat(source.state.value).isEqualTo(unavailable)
    }

    @Test
    fun `no fix for more than 10 s becomes Unavailable`() = runTest {
        val client = FakeClient()
        val source = newSource(client)
        source.start()
        client.listener!!.onFix(fix(testScheduler.currentTime))
        advanceTimeBy(9_500)
        runCurrent()
        assertThat(source.state.value).isInstanceOf(LocationState.Available::class.java)
        advanceTimeBy(1_600)
        runCurrent()
        assertThat(source.state.value).isEqualTo(unavailable)
        // A new fix restores availability.
        client.listener!!.onFix(fix(testScheduler.currentTime))
        assertThat(source.state.value).isInstanceOf(LocationState.Available::class.java)
        source.stop()
    }

    @Test
    fun `old cached fix is never published as current`() = runTest {
        val client = FakeClient()
        val source = newSource(client)
        advanceTimeBy(60_000)
        source.start()
        client.listener!!.onFix(fix(testScheduler.currentTime - 30_000))
        assertThat(source.state.value).isEqualTo(unavailable)
        source.stop()
    }

    @Test
    fun `provider unavailability is reported as Unavailable`() = runTest {
        val client = FakeClient()
        val source = newSource(client)
        source.start()
        client.listener!!.onFix(fix(testScheduler.currentTime))
        client.listener!!.onAvailabilityChanged(false)
        assertThat(source.state.value).isEqualTo(unavailable)
        client.listener!!.onAvailabilityChanged(true)
        assertThat(source.state.value).isEqualTo(unavailable) // waits for a real fix
        source.stop()
    }

    @Test
    fun `missing permission is Unavailable and recovers when granted`() = runTest {
        val client = FakeClient().apply { permission = false }
        val source = newSource(client)
        source.start()
        assertThat(client.requestCount).isEqualTo(0)
        assertThat(source.state.value).isEqualTo(unavailable)
        client.permission = true
        advanceTimeBy(FusedLocationSource.WATCHDOG_PERIOD_MS + 1)
        runCurrent()
        assertThat(client.requestCount).isEqualTo(1)
        client.listener!!.onFix(fix(testScheduler.currentTime))
        assertThat(source.state.value).isInstanceOf(LocationState.Available::class.java)
        source.stop()
    }

    @Test
    fun `revoked permission closes the subscription and ignores late callbacks`() = runTest {
        val client = FakeClient()
        val source = newSource(client)
        source.start()
        val oldListener = client.listener!!
        oldListener.onFix(fix(testScheduler.currentTime))
        client.permission = false
        advanceTimeBy(FusedLocationSource.WATCHDOG_PERIOD_MS + 1)
        runCurrent()
        assertThat(client.removeCount).isEqualTo(1)
        assertThat(source.state.value).isEqualTo(unavailable)
        oldListener.onFix(fix(testScheduler.currentTime))
        assertThat(source.state.value).isEqualTo(unavailable)
        source.stop()
    }

    @Test
    fun `callbacks after stop are ignored`() = runTest {
        val client = FakeClient()
        val source = newSource(client)
        source.start()
        val listener = client.listener!!
        source.stop()
        listener.onFix(fix(testScheduler.currentTime))
        assertThat(source.state.value).isEqualTo(unavailable)
    }
}
