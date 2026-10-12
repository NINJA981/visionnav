@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.visionnav.mapdata.overpass

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.ServiceError
import com.visionnav.core.common.TimeSource
import com.visionnav.core.geo.GeoMath
import com.visionnav.core.geo.GeoPoint
import com.visionnav.mapdata.Fixtures
import com.visionnav.mapdata.MapDataConfig
import com.visionnav.mapdata.MapDataDiagnostics
import com.visionnav.mapdata.MapService
import com.visionnav.mapdata.SchedulerTimeSource
import com.visionnav.mapdata.errorOrFail
import com.visionnav.mapdata.testConfig
import com.visionnav.mapdata.testHttp
import com.visionnav.mapdata.valueOrFail
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class OverpassRoadMetadataServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val fixture = Fixtures.read("overpass_chennai_100m_handwritten.json")
    private val p0 = GeoPoint(13.0604, 80.2496)
    private val hour = 60L * 60 * 1000

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun ok() = MockResponse().setBody(fixture).setHeader("Content-Type", "application/json")

    private fun TestScope.service(
        config: MapDataConfig = testConfig(server),
        time: TimeSource = SchedulerTimeSource(testScheduler),
        cacheDir: File? = null,
        policy: OverpassPolicy = OverpassPolicy(),
        diagnostics: MapDataDiagnostics? = null,
    ) = OverpassRoadMetadataService(testHttp(config), config, time, cacheDir, policy, diagnostics)

    @Test
    fun `success posts form-encoded query and returns verbatim tags`() = runTest {
        server.enqueue(ok())
        val meta = service().roadsNear(p0, 100.0).valueOrFail()

        assertThat(meta.center).isEqualTo(p0)
        assertThat(meta.radiusM).isEqualTo(100.0)
        assertThat(meta.ways.map { it.osmWayId }).contains(900000002L)
        assertThat(meta.ways.single { it.osmWayId == 900000002L }.tags["oneway"]).isEqualTo("yes")
        assertThat(meta.ways.single { it.osmWayId == 900000001L }.tags).doesNotContainKey("oneway")
        assertThat(meta.features.single { it.osmNodeId == 910000001L }.tags["crossing:island"]).isEqualTo("yes")

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/api/interpreter")
        assertThat(req.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded")
        assertThat(req.getHeader("User-Agent")).isEqualTo(MapDataConfig.DEFAULT_USER_AGENT)
        val body = req.body.readUtf8()
        assertThat(body).startsWith("data=")
        val query = URLDecoder.decode(body.removePrefix("data="), "UTF-8")
        assertThat(query).startsWith("[out:json][timeout:15];")
        assertThat(query).contains("node(around:310,")
        assertThat(query).endsWith("out geom tags;")
    }

    @Test
    fun `result is trimmed to the requested circle`() = runTest {
        server.enqueue(ok())
        val meta = service().roadsNear(p0, 30.0).valueOrFail()
        assertThat(meta.features.map { it.osmNodeId }).containsExactly(910000001L, 910000004L, 910000006L)
        assertThat(meta.ways.map { it.osmWayId }).containsExactly(900000001L, 900000002L, 900000003L)
        meta.features.forEach { assertThat(GeoMath.distanceM(p0, it.point)).isAtMost(30.0) }
    }

    @Test
    fun `second request in the same area is a cache hit including coverage reuse`() = runTest {
        server.enqueue(ok())
        val diagnostics = MapDataDiagnostics()
        val svc = service(diagnostics = diagnostics)
        svc.roadsNear(p0, 100.0).valueOrFail()
        svc.roadsNear(p0, 100.0).valueOrFail()
        svc.roadsNear(GeoMath.destination(p0, 45.0, 50.0), 60.0).valueOrFail()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(diagnostics[MapService.OVERPASS].cacheHits).isEqualTo(2)
        assertThat(diagnostics[MapService.OVERPASS].requests).isEqualTo(1)
    }

    @Test
    fun `cache expires after 24 hours`() = runTest {
        server.enqueue(ok())
        server.enqueue(ok())
        val svc = service()
        svc.roadsNear(p0, 100.0).valueOrFail()
        delay(23 * hour)
        svc.roadsNear(p0, 100.0).valueOrFail()
        assertThat(server.requestCount).isEqualTo(1)
        delay(2 * hour)
        val meta = svc.roadsNear(p0, 100.0).valueOrFail()
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(meta.fetchedAtMs).isEqualTo(SchedulerTimeSource(testScheduler).wallMs())
    }

    @Test
    fun `disk cache is reused by a fresh service instance`() = runTest {
        val dir = tmp.newFolder("overpass")
        server.enqueue(ok())
        service(cacheDir = dir).roadsNear(p0, 100.0).valueOrFail()
        val meta = service(cacheDir = dir).roadsNear(p0, 100.0).valueOrFail()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(meta.features).isNotEmpty()
    }

    @Test
    fun `requests to the same endpoint are spaced by at least 2 seconds`() = runTest {
        server.enqueue(ok())
        server.enqueue(ok())
        val svc = service()
        svc.roadsNear(p0, 100.0).valueOrFail()
        val t0 = testScheduler.currentTime
        svc.roadsNear(GeoMath.destination(p0, 0.0, 3_000.0), 100.0).valueOrFail()
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(testScheduler.currentTime - t0).isAtLeast(2_000L)
    }

    @Test
    fun `429 returns RateLimited and Retry-After is respected without blocking`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
        server.enqueue(ok())
        val svc = service()
        assertThat(svc.roadsNear(p0, 100.0).errorOrFail()).isEqualTo(ServiceError.RateLimited)
        assertThat(server.requestCount).isEqualTo(1)

        delay(10_000)
        val before = testScheduler.currentTime
        assertThat(svc.roadsNear(p0, 100.0).errorOrFail()).isEqualTo(ServiceError.RateLimited)
        assertThat(testScheduler.currentTime).isEqualTo(before) // returned immediately, no waiting
        assertThat(server.requestCount).isEqualTo(1) // endpoint skipped while backed off

        delay(21_000)
        svc.roadsNear(p0, 100.0).valueOrFail()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `consecutive throttles back off exponentially`() = runTest {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(504)) }
        server.enqueue(ok())
        val svc = service(policy = OverpassPolicy(backoffBaseMs = 5_000))
        assertThat(svc.roadsNear(p0, 100.0).errorOrFail()).isEqualTo(ServiceError.Timeout)
        delay(5_001)
        assertThat(svc.roadsNear(p0, 100.0).errorOrFail()).isEqualTo(ServiceError.Timeout)
        delay(5_001) // second backoff is 10 s
        assertThat(svc.roadsNear(p0, 100.0).errorOrFail()).isEqualTo(ServiceError.RateLimited)
        assertThat(server.requestCount).isEqualTo(2)
        delay(5_000)
        svc.roadsNear(p0, 100.0).valueOrFail()
        assertThat(server.requestCount).isEqualTo(3)
    }

    private fun twoEndpointConfig(readTimeoutMs: Long = 2_000) = testConfig(server, readTimeoutMs).copy(
        overpassBaseUrl = server.url("/primary").toString(),
        overpassFallbackUrls = listOf(server.url("/fallback").toString()),
    )

    private fun route(primary: () -> MockResponse, fallback: () -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/primary" -> primary()
                "/fallback" -> fallback()
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun `5xx on primary falls back to the next endpoint`() = runTest {
        route(primary = { MockResponse().setResponseCode(503) }, fallback = { ok() })
        val meta = service(twoEndpointConfig()).roadsNear(p0, 100.0).valueOrFail()
        assertThat(meta.features).isNotEmpty()
        assertThat(server.takeRequest().path).isEqualTo("/primary")
        assertThat(server.takeRequest().path).isEqualTo("/fallback")
    }

    @Test
    fun `timeout on primary falls back to the next endpoint`() = runTest {
        route(primary = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }, fallback = { ok() })
        service(twoEndpointConfig(readTimeoutMs = 300)).roadsNear(p0, 100.0).valueOrFail()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `all endpoints failing reports the most relevant error`() = runTest {
        route(primary = { MockResponse().setResponseCode(429) }, fallback = { MockResponse().setResponseCode(502) })
        assertThat(service(twoEndpointConfig()).roadsNear(p0, 100.0).errorOrFail()).isEqualTo(ServiceError.RateLimited)
    }

    @Test
    fun `timeout with a single endpoint maps to Timeout`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val config = testConfig(server, readTimeoutMs = 300)
        assertThat(service(config).roadsNear(p0, 100.0).errorOrFail()).isEqualTo(ServiceError.Timeout)
    }

    @Test
    fun `offline maps to Offline`() = runTest {
        val dead = MockWebServer().apply { start() }
        val config = testConfig(dead)
        dead.shutdown()
        assertThat(service(config).roadsNear(p0, 100.0).errorOrFail()).isEqualTo(ServiceError.Offline)
    }

    @Test
    fun `malformed JSON maps to Parse and is not cached`() = runTest {
        server.enqueue(MockResponse().setBody("{\"elements\": [ {\"type\": "))
        server.enqueue(ok())
        val svc = service()
        assertThat(svc.roadsNear(p0, 100.0).errorOrFail()).isInstanceOf(ServiceError.Parse::class.java)
        svc.roadsNear(p0, 100.0).valueOrFail()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `400 query error is returned without trying fallbacks`() = runTest {
        route(primary = { MockResponse().setResponseCode(400) }, fallback = { ok() })
        assertThat(service(twoEndpointConfig()).roadsNear(p0, 100.0).errorOrFail()).isEqualTo(ServiceError.Http(400))
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `runtime error remark is treated as failure and never cached`() = runTest {
        server.enqueue(MockResponse().setBody("""{"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 1 after 16 seconds."}"""))
        server.enqueue(ok())
        val svc = service()
        assertThat(svc.roadsNear(p0, 100.0).errorOrFail()).isEqualTo(ServiceError.Timeout)
        delay(6_000)
        assertThat(svc.roadsNear(p0, 100.0).valueOrFail().features).isNotEmpty()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `expired tile is served when the network fails, with its original fetch time`() = runTest {
        server.enqueue(ok())
        server.enqueue(MockResponse().setResponseCode(503))
        val diagnostics = MapDataDiagnostics()
        val svc = service(diagnostics = diagnostics)
        val first = svc.roadsNear(p0, 100.0).valueOrFail()
        delay(25 * hour)
        val stale = svc.roadsNear(p0, 100.0).valueOrFail()
        assertThat(stale.fetchedAtMs).isEqualTo(first.fetchedAtMs)
        assertThat(stale.features).isEqualTo(first.features)
        assertThat(diagnostics[MapService.OVERPASS].staleServed).isEqualTo(1)
        assertThat(diagnostics[MapService.OVERPASS].lastError).isEqualTo(ServiceError.Http(503))
    }

    @Test
    fun `stale serving can be disabled`() = runTest {
        server.enqueue(ok())
        server.enqueue(MockResponse().setResponseCode(503))
        val svc = service(policy = OverpassPolicy(serveStaleOnError = false))
        svc.roadsNear(p0, 100.0).valueOrFail()
        delay(25 * hour)
        assertThat(svc.roadsNear(p0, 100.0).errorOrFail()).isEqualTo(ServiceError.Http(503))
    }

    @Test
    fun `radius is clamped and defaulted`() = runTest {
        val svc = service()
        assertThat(svc.normalizeRadius(5.0)).isEqualTo(30.0)
        assertThat(svc.normalizeRadius(1_000.0)).isEqualTo(300.0)
        assertThat(svc.normalizeRadius(Double.NaN)).isEqualTo(120.0)
        assertThat(svc.normalizeRadius(-3.0)).isEqualTo(120.0)
        assertThat(svc.normalizeRadius(150.0)).isEqualTo(150.0)
        server.enqueue(ok())
        assertThat(svc.roadsNear(p0, 10_000.0).valueOrFail().radiusM).isEqualTo(300.0)
        val query = URLDecoder.decode(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8().removePrefix("data="), "UTF-8")
        assertThat(query).contains("around:490,") // 300 bucket + 177 half-diagonal + 10 margin, rounded up
    }
}
