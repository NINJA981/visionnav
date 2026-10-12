package com.visionnav.mapdata.http

import com.visionnav.mapdata.MapDataConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Builds the single [OkHttpClient] shared by all map services. */
object HttpClientFactory {

    /**
     * @param base optional client to derive from (shares its connection pool and dispatcher),
     *   e.g. an app-wide client. Its interceptors are kept; ours is appended.
     */
    fun create(config: MapDataConfig, base: OkHttpClient? = null): OkHttpClient =
        (base?.newBuilder() ?: OkHttpClient.Builder())
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(config.callTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(UserAgentInterceptor(config.userAgent))
            .build()
}

/**
 * Sets `User-Agent` on every request. Required by the OSMF / Overpass / Komoot usage policies,
 * which block anonymous or default library agents.
 */
class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    init {
        require(userAgent.isNotBlank()) { "User-Agent must not be blank" }
    }

    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}
