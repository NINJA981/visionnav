package com.visionnav.mapdata.photon

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.common.ServiceError
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class PhotonGeocodingServiceTest {
    private lateinit var server: MockWebServer
    private val near = GeoPoint(13.0827, 80.2707)

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun TestScope.service(diagnostics: MapDataDiagnostics? = null) =
        testConfig(server).let { PhotonGeocodingService(testHttp(it), it, SchedulerTimeSource(testScheduler), diagnostics = diagnostics) }

    private fun fc(vararg features: String) = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    private fun feature(p: GeoPoint, name: String) =
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]},"properties":{"name":"$name","osm_key":"amenity","osm_value":"cafe"}}"""

    @Test
    fun `search sends biased query and parses live fixture`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("photon_search_chennai_central.json")))
        val places = service().search("  Chennai Central ", near, limit = 4).valueOrFail()
        assertThat(places).hasSize(4)
        places.forEach { assertThat(it.distanceM).isNotNull() }

        val req = server.takeRequest()
        val url = req.requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/api")
        assertThat(url.queryParameter("q")).isEqualTo("Chennai Central")
        assertThat(url.queryParameter("limit")).isEqualTo("8")
        assertThat(url.queryParameter("lat")).isEqualTo("13.082700")
        assertThat(url.queryParameter("lon")).isEqualTo("80.270700")
        assertThat(url.queryParameter("lang")).isEqualTo("en")
        assertThat(req.getHeader("User-Agent")).isEqualTo(MapDataConfig.DEFAULT_USER_AGENT)
    }

    @Test
    fun `results within 5 km rank before far ones, keeping Photon order inside groups`() = runTest {
        val far1 = GeoMath.destination(near, 0.0, 40_000.0)
        val near1 = GeoMath.destination(near, 90.0, 4_000.0)
        val far2 = GeoMath.destination(near, 180.0, 6_000.0)
        val near2 = GeoMath.destination(near, 270.0, 300.0)
        server.enqueue(MockResponse().setBody(fc(feature(far1, "A"), feature(near1, "B"), feature(far2, "C"), feature(near2, "D"))))
        val places = service().search("cafe", near).valueOrFail()
        assertThat(places.map { it.name }).containsExactly("B", "D", "A", "C").inOrder()
        assertThat(places[0].distanceM!!).isWithin(1.0).of(4_000.0)
    }

    @Test
    fun `without a bias point Photon order is kept and distance is null`() = runTest {
        server.enqueue(MockResponse().setBody(fc(feature(near, "A"), feature(near, "B"))))
        val places = service().search("cafe", near = null).valueOrFail()
        assertThat(places.map { it.name }).containsExactly("A", "B").inOrder()
        assertThat(places.all { it.distanceM == null }).isTrue()
        assertThat(server.takeRequest().requestUrl!!.queryParameter("lat")).isNull()
    }

    @Test
    fun `near-identical results are de-duplicated and limit applied after dedupe`() = runTest {
        val a = GeoMath.destination(near, 0.0, 100.0)
        server.enqueue(
            MockResponse().setBody(
                fc(
                    feature(a, "Saravana Bhavan"),
                    feature(GeoMath.destination(a, 90.0, 20.0), "saravana bhavan"), // dup: same name, 20 m
                    feature(GeoMath.destination(a, 90.0, 200.0), "Saravana Bhavan"), // kept: 200 m apart
                    feature(GeoMath.destination(a, 90.0, 10.0), "Murugan Idli"), // kept: different name
                    feature(GeoMath.destination(a, 0.0, 500.0), "Fourth"),
                ),
            ),
        )
        val places = service().search("food", near, limit = 3).valueOrFail()
        assertThat(places.map { it.name }).containsExactly("Saravana Bhavan", "Saravana Bhavan", "Murugan Idli").inOrder()
        assertThat(GeoMath.distanceM(places[0].point, places[1].point)).isGreaterThan(30.0)
    }

    @Test
    fun `identical query is cached for 5 minutes`() = runTest {
        repeat(2) { server.enqueue(MockResponse().setBody(fc(feature(near, "A")))) }
        val diagnostics = MapDataDiagnostics()
        val svc = service(diagnostics)
        svc.search("cafe", near).valueOrFail()
        delay(60_000)
        // Bias moved ~20 m: same cache key, distances recomputed from the new bias point.
        val moved = GeoMath.destination(near, 0.0, 20.0)
        val cached = svc.search("CAFE ", moved).valueOrFail()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(cached.single().distanceM!!).isWithin(0.5).of(20.0)
        assertThat(diagnostics[MapService.PHOTON].cacheHits).isEqualTo(1)

        delay(5 * 60_000)
        svc.search("cafe", near).valueOrFail()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `blank query returns empty without a request`() = runTest {
        assertThat(service().search("   ", near).valueOrFail()).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `no results is an empty success`() = runTest {
        server.enqueue(MockResponse().setBody(fc()))
        assertThat(service().search("zzzz", near).valueOrFail()).isEmpty()
    }

    @Test
    fun `reverse returns nearest place from live fixture`() = runTest {
        server.enqueue(MockResponse().setBody(Fixtures.read("photon_reverse_chennai.json")))
        val place = service().reverse(near).valueOrFail()
        assertThat(place.name).isEqualTo("Meta Hospital")
        assertThat(place.distanceM!!).isLessThan(20.0)
        val url = server.takeRequest().requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/reverse")
        assertThat(url.queryParameter("lat")).isEqualTo("13.082700")
        assertThat(url.queryParameter("lang")).isEqualTo("en")
    }

    @Test
    fun `reverse with no features is NotFound`() = runTest {
        server.enqueue(MockResponse().setBody(fc()))
        assertThat(service().reverse(near).errorOrFail()).isEqualTo(ServiceError.NotFound)
    }

    @Test
    fun `429 maps to RateLimited and 5xx to Http`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(502))
        val diagnostics = MapDataDiagnostics()
        val svc = service(diagnostics)
        assertThat(svc.search("a", near).errorOrFail()).isEqualTo(ServiceError.RateLimited)
        assertThat(svc.reverse(near).errorOrFail()).isEqualTo(ServiceError.Http(502))
        assertThat(diagnostics[MapService.PHOTON].failures).isEqualTo(2)
        assertThat(diagnostics[MapService.PHOTON].lastError).isEqualTo(ServiceError.Http(502))
    }

    @Test
    fun `malformed JSON maps to Parse and is not cached`() = runTest {
        server.enqueue(MockResponse().setBody("{\"features\": [ {"))
        server.enqueue(MockResponse().setBody(fc(feature(near, "A"))))
        val svc = service()
        assertThat(svc.search("a", near).errorOrFail()).isInstanceOf(ServiceError.Parse::class.java)
        assertThat(svc.search("a", near).valueOrFail()).hasSize(1)
    }
}
