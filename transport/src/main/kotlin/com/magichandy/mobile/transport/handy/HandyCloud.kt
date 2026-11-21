// Ported from internal/transport/{cloud,cloud_client}.go at 1b80d72e.
package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceConnectionFailure.Reason
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceConnectionException
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.SliderReading
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.transport.http.asOneShot
import com.magichandy.mobile.transport.http.boundedText
import com.magichandy.mobile.transport.http.exchange
import com.magichandy.mobile.transport.http.forDeviceStops
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
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

internal class CloudWire(
    private val key: Secret,
    private val appId: Secret,
    client: OkHttpClient,
    private val base: HttpUrl,
    clock: Clock? = null,
) : TimedHandyWire(clock) {
    @Volatile
    override var latency: Duration? = null
        private set

    private val serverClock = clock?.let(::CloudClock)
    override val maxPoints = 100
    override val stopTimeout = 5.seconds
    private val normal =
        client
            .newBuilder()
            // The vendor waits up to five seconds for a device. Allow time for the network round
            // trip.
            .callTimeout(8, TimeUnit.SECONDS)
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
    private val priority = normal.forDeviceStops()

    private class StaleCloudRequest : IOException("Cloud request fenced before dispatch")

    private class Admission(val allowed: () -> Boolean)

    private suspend fun exchange(
        client: OkHttpClient,
        path: String,
        body: JsonObject?,
        allowed: () -> Boolean,
    ): JsonObject {
        if (!allowed()) throw StaleCloudRequest()
        val request =
            Request.Builder()
                .url(requireNotNull(base.resolve(path)))
                .tag(Admission::class.java, Admission(allowed))
                .header("Accept", "application/json")
                .header("X-Api-Key", appId.reveal())
                .header("X-Connection-Key", key.reveal())
        if (body != null)
            request.put(body.toString().toRequestBody("application/json".toMediaType()).asOneShot())
        val sent = clock?.elapsed()
        return client.exchange(request.build()) { response ->
            if (sent != null) latency = requireNotNull(clock).elapsed() - sent
            response.handyReply()
        }
    }

    override suspend fun checkConnection(allowed: () -> Boolean): Boolean = checkCloud {
        val result = exchange(normal, "connected", null, allowed).cloudResult()
        when (result?.get("connected")) {
            JsonPrimitive(true) -> true
            JsonPrimitive(false) -> throw cloudFailure(Reason.Offline)
            else -> throw cloudFailure(Reason.InvalidReply)
        }
    }

    override suspend fun establishIdle(allowed: () -> Boolean): StopEvidence = checkCloud {
        checkConnection(allowed)
        confirmDeviceIdle(normal, allowed)
    }

    private suspend fun <T> checkCloud(read: suspend () -> T): T =
        try {
            read()
        } catch (e: DeviceConnectionException) {
            throw e
        } catch (_: InterruptedIOException) {
            throw cloudFailure(Reason.TimedOut)
        } catch (_: IOException) {
            throw cloudFailure(Reason.ServiceUnavailable)
        }

    private suspend fun confirmDeviceIdle(
        client: OkHttpClient,
        allowed: () -> Boolean,
    ): StopEvidence {
        fun mode(reply: JsonObject) =
            (reply.cloudResult()?.get("mode") as? JsonPrimitive)?.intOrNull
        val current = mode(exchange(client, "mode", null, allowed))
        when (current) {
            7 -> return StopEvidence.Acknowledged
            3,
            5 -> throw cloudFailure(Reason.DeviceBusy) // Maintenance or firmware update.
            0,
            1,
            2,
            4,
            6,
            8,
            9 -> Unit // A controllable mode. Only request device-wide idle.
            else -> throw cloudFailure(Reason.InvalidReply)
        }
        val changed = exchange(client, "mode2", buildJsonObject { put("mode", 7) }, allowed)
        if (mode(changed) == 7) return StopEvidence.Acknowledged
        if (changed.explicitAck() && mode(exchange(client, "mode", null, allowed)) == 7)
            return StopEvidence.Acknowledged
        throw cloudFailure(Reason.IdleUnconfirmed)
    }

    override suspend fun readPosition(allowed: () -> Boolean): SliderReading? =
        try {
            coroutineScope {
                suspend fun read(path: String): JsonObject =
                    exchange(normal, path, null, allowed).cloudResult()
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
        } catch (failure: IOException) {
            if (
                !allowed() && (failure is StaleCloudRequest || failure.cause is StaleCloudRequest)
            ) {
                DiagnosticReporter.record(DiagnosticKind.WriteFenced)
                null
            } else throw failure
        }

    override suspend fun playTimestamp(allowed: () -> Boolean): Long? {
        val time = serverClock?.deliveryTime { readServerTime(allowed) }
        if (time == null && allowed())
            DiagnosticReporter.record(DiagnosticKind.ClockSyncUnavailable)
        return time
    }

    override suspend fun dispatch(command: HandyCommand, allowed: () -> Boolean): WriteResult {
        if (!allowed()) return WriteResult.Stale
        if (command == HandyCommand.State) {
            val state = exchange(normal, "hsp/state", null, allowed)
            return if (state.cloudPlayback() != null) WriteResult.Accepted
            else WriteResult.Uncertain
        }
        val request = cloudRequest(command)
        return try {
            if (exchange(normal, request.path, request.body, allowed).acknowledges(command))
                WriteResult.Accepted
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

    override suspend fun stop(allowed: () -> Boolean): StopEvidence =
        try {
            try {
                stopHsp(allowed)
            } catch (e: DeviceConnectionException) {
                if (e.failure.deviceCode != wrongHandyMode) throw e
                confirmDeviceIdle(priority, allowed)
            }
        } catch (e: IOException) {
            DiagnosticReporter.record(DiagnosticKind.TransportStopFailed)
            val refused =
                e is DeviceConnectionException ||
                    e is StaleCloudRequest ||
                    e.cause is StaleCloudRequest
            StopEvidence.Unconfirmed(
                if (refused) UnconfirmedCause.WriteFailed else UnconfirmedCause.NoReply
            )
        }

    private suspend fun stopHsp(allowed: () -> Boolean): StopEvidence {
        val result = exchange(priority, "hsp/stop", buildJsonObject {}, allowed)
        if (result.cloudPlayback() == HandyPlayback.Stopped) return StopEvidence.Acknowledged
        val state = exchange(priority, "hsp/state", null, allowed).cloudPlayback()
        return when (state) {
            HandyPlayback.Stopped -> StopEvidence.Acknowledged
            HandyPlayback.Uninitialized -> confirmDeviceIdle(priority, allowed)
            else -> StopEvidence.Unconfirmed(UnconfirmedCause.NoReply)
        }
    }
}
