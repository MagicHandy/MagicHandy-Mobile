package com.magichandy.mobile.transport.http

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

/**
 * Keep cancellation attached through body consumption. The callback owns and always closes the
 * body.
 */
internal suspend fun <T> Call.Factory.exchange(request: Request, consume: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(IOException("HTTP exchange interrupted", e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        continuation.resume(response.use(consume))
                    } catch (failure: Exception) {
                        continuation.resumeWithException(failure)
                    }
                }
            }
        )
    }

internal fun Response.boundedText(limit: Long): String {
    val source = body.source()
    if (source.request(limit + 1)) throw IOException("Response exceeds size limit")
    return source.readUtf8()
}
