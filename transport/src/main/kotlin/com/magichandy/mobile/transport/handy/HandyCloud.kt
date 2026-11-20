// Ported from internal/transport/{cloud,cloud_client}.go at 1b80d72e.
package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.SliderReading
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.transport.http.boundedText
import com.magichandy.mobile.transport.http.exchange
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Create a Handy API v3 HSP owner. Stop has a dedicated HTTP dispatcher. Supply [clock] to measure
 * the server timestamp for Play. [readMounting] reads a nonblocking configuration snapshot once per
 * stream.
 */
public fun createHandyCloud(
    device: DeviceRef,
    connectionKey: Secret,
    applicationId: Secret,
    client: OkHttpClient,
    clock: Clock? = null,
    readMounting: () -> Boolean = { false },
): DeviceTransport =
    cloudOwner(
        device,
        connectionKey,
        applicationId,
        client,
        "https://www.handyfeeling.com/api/handy-rest/v3/".toHttpUrl(),
        clock,
        readMounting,
    )

internal fun cloudOwner(
    device: DeviceRef,
    key: Secret,
    appId: Secret,
    client: OkHttpClient,
    base: HttpUrl,
    clock: Clock? = null,
    readMounting: () -> Boolean = { false },
): DeviceTransport {
    require(device.family == DeviceFamily.Handy && device.connection == ConnectionKind.HandyCloud)
    return HandyTransport(
        device,
        CloudWire(key, appId, client, base, clock),
        readMounting = readMounting,
    )
}

internal data class CloudRequest(val path: String, val body: JsonObject)

internal fun cloudRequest(command: HandyCommand): CloudRequest =
    when (command) {
        is HandyCommand.Stroke ->
            CloudRequest(
                "slider/stroke",
                buildJsonObject {
                    put("min", command.low / 100.0)
                    put("max", command.high / 100.0)
                },
            )
        is HandyCommand.Setup ->
            CloudRequest("hsp/setup", buildJsonObject { put("stream_id", command.stream) })
        is HandyCommand.Add ->
            CloudRequest(
                "hsp/add",
                buildJsonObject {
                    put(
                        "points",
                        buildJsonArray {
                            for (frame in command.frames) add(
                                buildJsonObject {
                                    put("x", frame.position.value)
                                    put("t", frame.timeMillis)
                                }
                            )
                        },
                    )
                    put("flush", command.flush)
                    put("tail_point_stream_index", command.tail)
                },
            )
        is HandyCommand.Play ->
            CloudRequest(
                "hsp/play",
                buildJsonObject {
                    put("start_time", command.start)
                    put("playback_rate", 1)
                    put("pause_on_starving", true)
                    put("loop", false)
                    command.serverTimeMillis?.let { put("server_time", it) }
                },
            )
        HandyCommand.HspStop -> CloudRequest("hsp/stop", buildJsonObject {})
        HandyCommand.StopCurrentMode,
        HandyCommand.State,
        HandyCommand.ClockGet,
        is HandyCommand.ClockSet -> error("Not an HSP REST command")
    }

// The engine bounds outstanding Stops per owner. Owners on the same cloud host must run in
// parallel.
private val priorityDispatcher =
    Dispatcher().apply {
        maxRequests = Int.MAX_VALUE
        maxRequestsPerHost = Int.MAX_VALUE
    }

internal fun OkHttpClient.forHandyStops(): OkHttpClient =
    newBuilder().dispatcher(priorityDispatcher).build()

internal class CloudWire(
    private val key: Secret,
    private val appId: Secret,
    client: OkHttpClient,
    private val base: HttpUrl,
    clock: Clock? = null,
) : HandyWire {
    private val serverClock = clock?.let(::CloudClock)
    override val maxPoints = 100
    override val stopTimeout = 5.seconds
    private val normal =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.tag(Admission::class.java)?.allowed?.invoke() != true)
                    throw StaleCloudRequest()
                chain.proceed(request)
            }
            .build()
    private val priority = normal.forHandyStops()

    private class StaleCloudRequest : IOException("Cloud request fenced before dispatch")

    private class Admission(val allowed: () -> Boolean)

    private suspend fun exchange(
        client: OkHttpClient,
        path: String,
        body: JsonObject?,
        allowed: () -> Boolean,
    ): JsonObject? {
        if (!allowed()) throw StaleCloudRequest()
        val request =
            Request.Builder()
                .url(requireNotNull(base.resolve(path)))
                .tag(Admission::class.java, Admission(allowed))
                .header("Accept", "application/json")
                .header("X-Api-Key", appId.reveal())
                .header("X-Connection-Key", key.reveal())
        if (body != null)
            request.put(body.toString().toRequestBody("application/json".toMediaType()))
        return client.exchange(request.build()) readResponse@{ response ->
            if (!response.isSuccessful) return@readResponse null
            val json =
                try {
                    Json.parseToJsonElement(response.boundedText(65536)) as? JsonObject
                } catch (_: SerializationException) {
                    null
                }
            json?.takeUnless { obj ->
                val error = obj["error"]
                (error != null && error.toString() !in setOf("null", "false")) ||
                    obj["ok"]?.toString() == "false"
            }
        }
    }

    override suspend fun readPosition(allowed: () -> Boolean): SliderReading = coroutineScope {
        suspend fun read(path: String): JsonObject =
            exchange(normal, path, null, allowed)?.get("result") as? JsonObject
                ?: throw IOException("Handy startup read unavailable")
        val slider = async { read("slider/state") }
        val stroke = read("slider/stroke")
        val state = slider.await()
        fun JsonObject.number(name: String) =
            (get(name) as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
                ?: throw IOException("Handy startup reading incomplete")
        state.number("position")
        SliderReading(
            state.number("position_absolute"),
            state.number("speed_absolute"),
            stroke.number("min") * 100,
            stroke.number("max") * 100,
            stroke.number("min_absolute"),
            stroke.number("max_absolute"),
        )
    }

    override suspend fun command(command: HandyCommand, allowed: () -> Boolean): WriteResult {
        if (!allowed()) return WriteResult.Stale
        val timed =
            if (command is HandyCommand.Play && serverClock != null) {
                val time = serverClock.deliveryTime { readServerTime(allowed) }
                if (time == null && allowed())
                    DiagnosticReporter.record(DiagnosticKind.ClockSyncUnavailable)
                command.copy(serverTimeMillis = time)
            } else command
        val request = cloudRequest(timed)
        return try {
            if (exchange(normal, request.path, request.body, allowed) != null) WriteResult.Accepted
            else WriteResult.Uncertain
        } catch (failure: IOException) {
            if (failure !is StaleCloudRequest && failure.cause !is StaleCloudRequest) throw failure
            DiagnosticReporter.record(DiagnosticKind.WriteFenced)
            WriteResult.Stale
        }
    }

    private suspend fun readServerTime(allowed: () -> Boolean): Long? {
        if (!allowed()) return null
        val request =
            Request.Builder()
                .url(requireNotNull(base.resolve("servertime")))
                .tag(Admission::class.java, Admission(allowed))
                .build()
        return try {
            withTimeoutOrNull(2.seconds) {
                normal.exchange(request) {
                    if (it.isSuccessful) parseServerTimeMillis(it.boundedText(4096)) else null
                }
            }
        } catch (_: IOException) {
            null // The caller records clock unavailability and omits the optional Play timestamp.
        }
    }

    override suspend fun stop(allowed: () -> Boolean): StopEvidence {
        val request = cloudRequest(HandyCommand.HspStop)
        return try {
            val result =
                exchange(priority, request.path, request.body, allowed)
                    ?: return StopEvidence.Unconfirmed(UnconfirmedCause.WriteFailed)
            if (
                stopped(result) ||
                    exchange(priority, "hsp/state", null, allowed)?.let(::stopped) == true
            )
                StopEvidence.Acknowledged
            else StopEvidence.Unconfirmed(UnconfirmedCause.NoReply)
        } catch (failure: IOException) {
            DiagnosticReporter.record(DiagnosticKind.TransportStopFailed)
            StopEvidence.Unconfirmed(
                if (failure is StaleCloudRequest || failure.cause is StaleCloudRequest)
                    UnconfirmedCause.WriteFailed
                else UnconfirmedCause.NoReply
            )
        }
    }

    private fun stopped(reply: JsonObject): Boolean {
        val candidates = mutableListOf(reply)
        var level = listOf(reply)
        repeat(3) {
            level = level.flatMap { state ->
                listOf(
                        "result",
                        "data",
                        "state",
                        "hsp_state",
                        "hspState",
                        "responseHspStop",
                        "responseHspStateGet",
                    )
                    .mapNotNull { state[it] as? JsonObject }
            }
            candidates.addAll(level)
        }
        if (
            candidates.any { obj ->
                listOf("ok", "available", "hsp_available").any { obj[it]?.toString() == "false" }
            }
        )
            return false
        val states = candidates.flatMap { obj ->
            listOf("playback_state", "play_state", "playState", "state").mapNotNull {
                (obj[it] as? JsonPrimitive)?.content
            }
        }
        return states.isNotEmpty() && states.all { it in setOf("stopped", "2") }
    }
}
