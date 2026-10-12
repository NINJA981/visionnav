package com.visionnav.mapdata.http

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.ServiceError
import com.visionnav.mapdata.MapDataConfig
import com.visionnav.mapdata.testConfig
import com.visionnav.mapdata.testHttp
import com.visionnav.mapdata.valueOrFail
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class MapDataHttpTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends configured user agent and returns non-2xx as data`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(418).setHeader("Retry-After", "7").setBody("teapot"))
        val http = testHttp(testConfig(server))
        val resp = http.execute(Request.Builder().url(server.url("/x")).build()).valueOrFail()
        assertThat(resp.code).isEqualTo(418)
        assertThat(resp.body).isEqualTo("teapot")
        assertThat(resp.retryAfter).isEqualTo("7")
        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo(MapDataConfig.DEFAULT_USER_AGENT)
    }

    @Test
    fun `cancelling the coroutine cancels the HTTP call promptly`() = runBlocking {
        server.enqueue(MockResponse().setBody("late").setHeadersDelay(10, TimeUnit.SECONDS))
        val http = testHttp(testConfig(server, readTimeoutMs = 20_000))
        val started = System.nanoTime()
        val job = async { http.execute(Request.Builder().url(server.url("/slow")).build()) }
        server.takeRequest(5, TimeUnit.SECONDS) // request reached the server
        job.cancel()
        withTimeout(3_000) { job.join() }
        assertThat(job.isCancelled).isTrue()
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(8_000L)
    }

    @Test
    fun `exception mapping`() {
        assertThat(MapDataHttp.mapException(UnknownHostException("x"))).isEqualTo(ServiceError.Offline)
        assertThat(MapDataHttp.mapException(ConnectException("refused"))).isEqualTo(ServiceError.Offline)
        assertThat(MapDataHttp.mapException(SocketTimeoutException("read"))).isEqualTo(ServiceError.Timeout)
        assertThat(MapDataHttp.mapException(InterruptedIOException("timeout"))).isEqualTo(ServiceError.Timeout)
        assertThat(MapDataHttp.mapException(IOException("reset"))).isEqualTo(ServiceError.Unknown("reset"))
        assertThat(MapDataHttp.statusToError(429)).isEqualTo(ServiceError.RateLimited)
        assertThat(MapDataHttp.statusToError(502)).isEqualTo(ServiceError.Http(502))
    }

    @Test
    fun `retry-after parsing`() {
        assertThat(MapDataHttp.parseRetryAfterMs("30", 0)).isEqualTo(30_000)
        assertThat(MapDataHttp.parseRetryAfterMs(" 0 ", 0)).isEqualTo(0)
        assertThat(MapDataHttp.parseRetryAfterMs(null, 0)).isNull()
        assertThat(MapDataHttp.parseRetryAfterMs("soon", 0)).isNull()
        // Wed, 21 Oct 2015 07:28:00 GMT = 1445412480000
        assertThat(MapDataHttp.parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", 1_445_412_470_000)).isEqualTo(10_000)
        assertThat(MapDataHttp.parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", 1_445_412_490_000)).isEqualTo(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank user agent rejected`() {
        UserAgentInterceptor(" ")
    }
}
