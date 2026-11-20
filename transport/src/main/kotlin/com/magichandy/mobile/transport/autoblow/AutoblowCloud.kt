// Ported from internal/autoblow/{client,events,commands}.go at 1b80d72e.
package com.magichandy.mobile.transport.autoblow

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.transport.http.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal class Delivery(val admitted: () -> Boolean)

internal interface AutoblowWire {
    suspend fun state(priorityRead: Boolean = false): JsonObject

    suspend fun put(
        path: String,
        body: JsonObject?,
        stop: Boolean,
        allowed: () -> Boolean,
    ): JsonObject

    suspend fun events(receive: (String) -> Unit)
}

internal class AutoblowCloud(
    private val family: DeviceFamily,
    private val token: Secret,
    client: OkHttpClient,
    private val priority: OkHttpClient,
    private val lookup: HttpUrl = "https://latency.autoblowapi.com/".toHttpUrl(),
) : AutoblowWire {
    private val prefix = if (family == DeviceFamily.AutoblowUltra) "autoblow" else "vacuglide"
    private val normal = client
    private var cluster: HttpUrl? = null

    suspend fun discover() {
        require(token.reveal().length in 1..256 && token.reveal().all { it.code in 33..126 })
        val target = requireNotNull(lookup.resolve(prefix + "/connected"))
        val info = get(target)
        val expected = if (family == DeviceFamily.AutoblowUltra) "autoblow-ultra" else "vacuglide"
        if (
            info["connected"] != JsonPrimitive(true) ||
                info["deviceType"] != JsonPrimitive(expected)
        )
            throw IOException("Device identity or connection unavailable")
        val host = info["cluster"]?.jsonPrimitive?.content.orEmpty().lowercase()
        if (!clusterHost.matches(host)) throw IOException("Unexpected device cluster")
        cluster = ("https://" + host + "/").toHttpUrl()
    }

    private fun url(path: String) =
        requireNotNull(requireNotNull(cluster).resolve(prefix + "/" + path))

    private fun request(url: HttpUrl) =
        Request.Builder().url(url).header("x-device-token", token.reveal())

    private suspend fun get(url: HttpUrl, client: OkHttpClient = normal): JsonObject =
        client.exchange(request(url).build(), ::json)

    override suspend fun state(priorityRead: Boolean) =
        get(url("state"), if (priorityRead) priority else normal)

    override suspend fun put(
        path: String,
        body: JsonObject?,
        stop: Boolean,
        allowed: () -> Boolean,
    ): JsonObject {
        if (!allowed()) throw IOException("Stale Autoblow submission")
        val req =
            request(url(path))
                .tag(Delivery::class.java, Delivery(allowed))
                .put((body?.toString() ?: "").toRequestBody("application/json".toMediaType()))
                .build()
        return (if (stop) priority else normal).exchange(req, ::json)
    }

    private fun json(response: Response): JsonObject {
        if (!response.isSuccessful)
            throw IOException("Device request failed (" + response.code + ")")
        val text = response.boundedText(1L shl 20)
        return if (text.isBlank()) JsonObject(emptyMap())
        else
            try {
                Json.parseToJsonElement(text).jsonObject
            } catch (_: IllegalArgumentException) {
                throw IOException("Invalid device response")
            }
    }

    override suspend fun events(receive: (String) -> Unit) {
        val target =
            requireNotNull(requireNotNull(cluster).resolve("events/stream"))
                .newBuilder()
                .addQueryParameter("deviceToken", token.reveal())
                .build()
        val req = Request.Builder().url(target).header("Accept", "text/event-stream").build()
        normal
            .newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .build()
            .exchange(req) { r ->
                if (!r.isSuccessful) throw IOException("Event stream unavailable")
                val source = r.body.source()
                var name = ""
                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict(65536)
                    if (line.startsWith("event:"))
                        name = line.removePrefix("event:").trim().take(64)
                    else if (line.isEmpty()) {
                        if (name.isNotEmpty()) receive(name)
                        name = ""
                    }
                }
            }
    }

    companion object {
        private val clusterHost = Regex("[a-z0-9-]{1,63}\\.autoblowapi\\.com")

        fun clients(http: OkHttpClient): Pair<OkHttpClient, OkHttpClient> {
            val normal =
                http
                    .newBuilder()
                    .callTimeout(4, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .addNetworkInterceptor { chain ->
                        if (chain.request().tag(Delivery::class.java)?.admitted?.invoke() == false)
                            throw IOException("Stale Autoblow submission")
                        chain.proceed(chain.request())
                    }
                    .build()
            return normal to normal.newBuilder().dispatcher(stopDispatcher).build()
        }

        private val stopDispatcher = Dispatcher()
    }
}
