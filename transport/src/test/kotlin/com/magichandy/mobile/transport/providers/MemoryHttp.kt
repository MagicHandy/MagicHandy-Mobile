package com.magichandy.mobile.transport.providers

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * The delegate supplies Call metadata only. Neither synchronous nor asynchronous calls open a
 * socket.
 */
internal class MemoryHttp(val respond: (Request) -> Response?) : Call.Factory {
    private val metadata = OkHttpClient()
    var cancelled = false

    override fun newCall(request: Request): Call =
        object : Call by metadata.newCall(request) {
            override fun execute(): Response = error("Unexpected blocking I/O")

            override fun enqueue(responseCallback: Callback) {
                respond(request)?.let { responseCallback.onResponse(this, it) }
            }

            override fun cancel() {
                cancelled = true
            }
        }
}

internal fun Request.reply(text: String, type: String = "application/json", code: Int = 200) =
    Response.Builder()
        .request(this)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("OK")
        .body(text.toResponseBody(type.toMediaType()))
        .build()

internal fun Request.payload() = Buffer().also { body?.writeTo(it) }.readUtf8()
