package com.visionnav.mapdata.valhalla

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.ServiceError
import com.visionnav.core.geo.GeoPoint
import com.visionnav.mapdata.Fixtures
import com.visionnav.mapdata.MapDataConfig
import com.visionnav.mapdata.MapDataDiagnostics
import com.visionnav.mapdata.MapService
import com.visionnav.mapdata.errorOrFail
import com.visionnav.mapdata.testConfig
import com.visionnav.mapdata.testHttp
import com.visionnav.mapdata.valueOrFail
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test

class ValhallaRoutingServiceTest {
    private lateinit var server: MockWebServer
    private val from = GeoPoint(13.0827, 80.2707)
    private val to = GeoPoint(13.0850, 80.2750)

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun service(config: MapDataConfig = testConfig(server), diagnostics: MapDataDiagnostics? = null) =
        ValhallaRoutingService(testHttp(config), config, diagnostics = diagnostics)

    @Test
    fun `success posts pedestrian request with heading and user agent`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("valhalla_route_chennai.json")))
        val diagnostics = MapDataDiagnostics()
        val route = service(diagnostics = diagnostics).walkingRoute(from, to, headingDeg = 365.4).valueOrFail()
        assertThat(route.geometry).hasSize(56)

        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/route")
        assertThat(req.getHeader("User-Agent")).isEqualTo(MapDataConfig.DEFAULT_USER_AGENT)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertThat(body["costing"]!!.jsonPrimitive.content).isEqualTo("pedestrian")
        val locations = body["locations"]!!.jsonArray
        assertThat(locations).hasSize(2)
        assertThat(locations[0].jsonObject["lat"]!!.jsonPrimitive.double).isEqualTo(13.0827)
        assertThat(locations[0].jsonObject["heading"]!!.jsonPrimitive.int).isEqualTo(5)
        assertThat(locations[0].jsonObject).containsKey("heading_tolerance")
        assertThat(locations[1].jsonObject).doesNotContainKey("heading")
        val ped = body["costing_options"]!!.jsonObject["pedestrian"]!!.jsonObject
        assertThat(ped["walking_speed"]!!.jsonPrimitive.double).isEqualTo(4.5)
        assertThat(ped["use_ferry"]!!.jsonPrimitive.double).isEqualTo(0.0)
        assertThat(ped).containsKey("step_penalty")
        assertThat(ped).containsKey("use_living_streets")
        val dir = body["directions_options"]!!.jsonObject
        assertThat(dir["units"]!!.jsonPrimitive.content).isEqualTo("kilometers")
        assertThat(dir["language"]!!.jsonPrimitive.content).isEqualTo("en-US")

        assertThat(diagnostics[MapService.VALHALLA].requests).isEqualTo(1)
        assertThat(diagnostics[MapService.VALHALLA].successes).isEqualTo(1)
    }

    @Test
    fun `no heading omits heading fields`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("valhalla_route_chennai.json")))
        service().walkingRoute(from, to).valueOrFail()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertThat(body["locations"]!!.jsonArray[0].jsonObject).doesNotContainKey("heading")
    }

    @Test
    fun `no route 442 maps to NotFound`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(Fixtures.read("valhalla_error_442_handwritten.json")))
        val diagnostics = MapDataDiagnostics()
        assertThat(service(diagnostics = diagnostics).walkingRoute(from, to).errorOrFail()).isEqualTo(ServiceError.NotFound)
        assertThat(diagnostics[MapService.VALHALLA].lastError).isEqualTo(ServiceError.NotFound)
    }

    @Test
    fun `no suitable edges 171 maps to NotFound`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error_code":171,"error":"No suitable edges near location","status_code":400,"status":"Bad Request"}"""),
        )
        assertThat(service().walkingRoute(from, to).errorOrFail()).isEqualTo(ServiceError.NotFound)
    }

    @Test
    fun `other 400 errors stay Http`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error_code":154,"error":"Path distance exceeds the max distance limit","status_code":400}"""),
        )
        assertThat(service().walkingRoute(from, to).errorOrFail()).isEqualTo(ServiceError.Http(400))
    }

    @Test
    fun `429 maps to RateLimited`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "10"))
        assertThat(service().walkingRoute(from, to).errorOrFail()).isEqualTo(ServiceError.RateLimited)
    }

    @Test
    fun `500 maps to Http`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>busy</html>"))
        assertThat(service().walkingRoute(from, to).errorOrFail()).isEqualTo(ServiceError.Http(503))
    }

    @Test
    fun `malformed JSON maps to Parse`() = runTest {
        server.enqueue(MockResponse().setBody("""{"trip": {"legs": [ """))
        assertThat(service().walkingRoute(from, to).errorOrFail()).isInstanceOf(ServiceError.Parse::class.java)
    }

    @Test
    fun `read timeout maps to Timeout`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val config = testConfig(server, readTimeoutMs = 300)
        assertThat(service(config).walkingRoute(from, to).errorOrFail()).isEqualTo(ServiceError.Timeout)
    }

    @Test
    fun `connection refused maps to Offline`() = runTest {
        val dead = MockWebServer().apply { start() }
        val config = testConfig(dead)
        dead.shutdown()
        assertThat(service(config).walkingRoute(from, to).errorOrFail()).isEqualTo(ServiceError.Offline)
    }
}
