@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.visionnav.mapdata

import com.visionnav.core.common.Outcome
import com.visionnav.core.common.ServiceError
import com.visionnav.core.common.TimeSource
import com.visionnav.mapdata.http.HttpClientFactory
import com.visionnav.mapdata.http.MapDataHttp
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.mockwebserver.MockWebServer

object Fixtures {
    fun read(name: String): String =
        requireNotNull(Fixtures::class.java.classLoader.getResource("fixtures/$name")) { "Missing fixture $name" }
            .readText(Charsets.UTF_8)
}

/** Clock driven by the coroutine test scheduler, so `delay` and time move together. */
class SchedulerTimeSource(
    private val scheduler: TestCoroutineScheduler,
    private val wallBaseMs: Long = 1_760_000_000_000L,
) : TimeSource {
    override fun elapsedMs(): Long = scheduler.currentTime
    override fun wallMs(): Long = wallBaseMs + scheduler.currentTime
}

/** Short real-time timeouts so failure tests stay fast. */
fun testConfig(server: MockWebServer? = null, readTimeoutMs: Long = 2_000): MapDataConfig {
    val base = server?.url("/")?.toString()?.trimEnd('/') ?: "http://127.0.0.1:9"
    return MapDataConfig(
        valhallaBaseUrl = base,
        overpassBaseUrl = "$base/api/interpreter",
        overpassFallbackUrls = emptyList(),
        photonBaseUrl = base,
        connectTimeoutMs = 2_000,
        readTimeoutMs = readTimeoutMs,
        writeTimeoutMs = 2_000,
        callTimeoutMs = 10_000,
    )
}

fun testHttp(config: MapDataConfig): MapDataHttp = MapDataHttp(HttpClientFactory.create(config))

fun <T> Outcome<T>.valueOrFail(): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> throw AssertionError("Expected success but got $error")
}

fun Outcome<*>.errorOrFail(): ServiceError = when (this) {
    is Outcome.Failure -> error
    is Outcome.Success -> throw AssertionError("Expected failure but got $value")
}
