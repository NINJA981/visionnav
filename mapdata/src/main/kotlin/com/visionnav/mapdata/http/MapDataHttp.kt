package com.visionnav.mapdata.http

import com.visionnav.core.common.Outcome
import com.visionnav.core.common.ServiceError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

/** A fully-read HTTP response of any status code. */
data class HttpResponseData(
    val code: Int,
    val body: String,
    /** Raw `Retry-After` header, if present. */
    val retryAfter: String?,
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Thin coroutine wrapper over OkHttp.
 *
 * - The call is enqueued on OkHttp's own dispatcher and awaited with [suspendCancellableCoroutine];
 *   cancelling the coroutine cancels the [Call] (socket closed immediately).
 * - The caller is moved to [ioDispatcher] so parsing that follows runs off the main thread.
 * - Transport failures are mapped to [ServiceError]; HTTP error statuses are returned as data so
 *   each service can interpret its own error bodies.
 */
class MapDataHttp(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun execute(request: Request): Outcome<HttpResponseData> = withContext(ioDispatcher) {
        val call = client.newCall(request)
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(Outcome.Failure(mapException(e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val outcome: Outcome<HttpResponseData> = try {
                        response.use {
                            Outcome.Success(
                                HttpResponseData(
                                    code = it.code,
                                    body = it.body?.string().orEmpty(),
                                    retryAfter = it.header("Retry-After"),
                                ),
                            )
                        }
                    } catch (e: IOException) {
                        Outcome.Failure(mapException(e))
                    }
                    if (cont.isActive) cont.resume(outcome)
                }
            })
        }
    }

    companion object {
        /** Maps a transport exception to the shared error vocabulary. */
        fun mapException(e: Throwable): ServiceError = when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> ServiceError.Offline
            is SocketTimeoutException -> ServiceError.Timeout
            // OkHttp's callTimeout surfaces as InterruptedIOException("timeout").
            is InterruptedIOException -> ServiceError.Timeout
            is IOException -> ServiceError.Unknown(e.message ?: e.javaClass.simpleName)
            else -> ServiceError.Unknown(e.message ?: e.javaClass.simpleName)
        }

        /** Generic status mapping: 429 → [ServiceError.RateLimited], otherwise [ServiceError.Http]. */
        fun statusToError(code: Int): ServiceError =
            if (code == 429) ServiceError.RateLimited else ServiceError.Http(code)

        /**
         * Parses `Retry-After` (delta-seconds or HTTP-date) into milliseconds from [nowWallMs].
         * Returns null when absent or unparseable; never negative.
         */
        fun parseRetryAfterMs(value: String?, nowWallMs: Long): Long? {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return null
            v.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
            return try {
                val at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                (at - nowWallMs).coerceAtLeast(0)
            } catch (_: Exception) {
                null
            }
        }
    }
}
