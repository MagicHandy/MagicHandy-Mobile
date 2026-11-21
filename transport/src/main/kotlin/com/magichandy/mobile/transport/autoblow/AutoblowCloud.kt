// Ported from internal/autoblow/{client,events,commands}.go at 1b80d72e.
package com.magichandy.mobile.transport.autoblow

import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.transport.http.asOneShot
import com.magichandy.mobile.transport.http.boundedText
import com.magichandy.mobile.transport.http.exchange
import com.magichandy.mobile.transport.http.forDeviceStops
import com.magichandy.mobile.transport.http.hasEventStream
import com.magichandy.mobile.transport.http.utf8Bom
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal class Delivery(val admitted: () -> Boolean)

internal class FencedAutoblowRequest : IOException("Autoblow submission fenced before dispatch")

internal fun Throwable.isFencedAutoblow(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is FencedAutoblowRequest }

internal interface AutoblowWire {
    suspend fun state(priorityRead: Boolean = false): JsonObject

    suspend fun put(
        path: String,
        body: JsonObject?,
        stop: Boolean,
        allowed: () -> Boolean,
    ): JsonObject

    suspend fun events(opened: () -> Unit, receive: (String) -> Unit)
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
        if (token.reveal().length !in 1..256 || token.reveal().any { it.code !in 33..126 })
            throw IOException("Invalid device credential")
        val target = requireNotNull(lookup.resolve(prefix + "/connected"))
        val info = get(target)
        val expected = if (family == DeviceFamily.AutoblowUltra) "autoblow-ultra" else "vacuglide"
        if (
            info["connected"] != JsonPrimitive(true) ||
                info["deviceType"] != JsonPrimitive(expected)
        )
            throw IOException("Device identity or connection unavailable")
        val host = (info["cluster"] as? JsonPrimitive)?.content.orEmpty().lowercase()
        if (!clusterHost.matches(host)) throw IOException("Unexpected device cluster")
        cluster = ("https://" + host + "/").toHttpUrl()
    }

    private fun url(path: String) =
        requireNotNull(requireNotNull(cluster).resolve(prefix + "/" + path))

    private fun request(url: HttpUrl) =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("x-device-token", token.reveal())

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
        if (!allowed()) throw FencedAutoblowRequest()
        val payload =
            body?.toString()?.toRequestBody("application/json".toMediaType())
                ?: byteArrayOf().toRequestBody()
        val req =
            request(url(path))
                .tag(Delivery::class.java, Delivery(allowed))
                .put(payload.asOneShot())
                .build()
        val state = (if (stop) priority else normal).exchange(req, ::json)
        if (!stop && !accepted(path, body, state)) throw IOException("Unconfirmed device command")
        return state
    }

    private fun accepted(path: String, body: JsonObject?, state: JsonObject): Boolean =
        when (path) {
            "goto" -> state["success"] == JsonPrimitive(true)
            "target-speed" -> {
                val speed = (state["targetSpeed"] as? JsonPrimitive)?.doubleOrNull
                state["operationalMode"] == JsonPrimitive("TARGET_SPEED_PLAYING") &&
                    speed != null &&
                    speed == (body?.get("targetSpeed") as? JsonPrimitive)?.doubleOrNull
            }
            "valve/stroke-plus" -> state["strokePlusValve"] == JsonPrimitive(true)
            "valve/stroke-minus" -> state["strokeMinusValve"] == JsonPrimitive(true)
            else -> false
        }

    private fun json(response: Response): JsonObject {
        if (!response.isSuccessful)
            throw IOException("Device request failed (" + response.code + ")")
        val text = response.boundedText(1L shl 20)
        if (text.isBlank()) return JsonObject(emptyMap())
        val reply =
            try {
                Json.parseToJsonElement(text).jsonObject
            } catch (_: IllegalArgumentException) {
                throw IOException("Invalid device response")
            }
        for (obj in listOfNotNull(reply, reply["result"] as? JsonObject)) {
            val error = obj["error"]
            if (
                (error != null && error != JsonNull && error != JsonPrimitive(false)) ||
                    listOf("ok", "success", "connected").any { obj[it] == JsonPrimitive(false) }
            ) {
                throw IOException("Device rejected the request")
            }
        }
        return reply
    }

    override suspend fun events(opened: () -> Unit, receive: (String) -> Unit) {
        val target =
            requireNotNull(requireNotNull(cluster).resolve("events/stream"))
                .newBuilder()
                .addQueryParameter("deviceToken", token.reveal())
                .build()
        val req = Request.Builder().url(target).header("Accept", "text/event-stream").build()
        normal
            .newBuilder()
            .dispatcher(eventDispatcher)
            .callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .build()
            .exchange(req) { r ->
                if (!r.hasEventStream()) throw IOException("Event stream unavailable")
                val source = r.body.source()
                if (source.rangeEquals(0, utf8Bom)) source.skip(utf8Bom.size.toLong())
                var name = ""
                var subscribed = false
                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict(65536)
                    if (line.startsWith("event:"))
                        name = line.removePrefix("event:").trim().take(64)
                    else if (line.isEmpty()) {
                        if (!subscribed && name == "connected") {
                            subscribed = true
                            opened()
                        } else if (subscribed && name.isNotEmpty()) receive(name)
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
                            throw FencedAutoblowRequest()
                        chain.proceed(chain.request())
                    }
                    .build()
            return normal to normal.forDeviceStops()
        }

        // An open button feed must not occupy a command or Stop slot.
        private val eventDispatcher =
            Dispatcher().apply {
                maxRequests = Int.MAX_VALUE
                maxRequestsPerHost = Int.MAX_VALUE
            }
    }
}
