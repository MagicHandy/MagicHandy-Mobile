package com.magichandy.mobile.transport.intiface

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.motion.MotionFrame
import com.magichandy.mobile.core.ports.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import mockwebserver3.*
import okhttp3.*

internal class BridgePeer : AutoCloseable {
    val diagnostics = CopyOnWriteArrayList<DiagnosticKind>()
    val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default + DiagnosticReporter { diagnostics += it }
        )
    private val origin = TimeSource.Monotonic.markNow()
    val extra = AtomicLong()
    val clock: com.magichandy.mobile.core.ports.Clock =
        object : Clock {
            override fun elapsed() = origin.elapsedNow() + extra.get().milliseconds

            override fun now() = Instant.fromEpochMilliseconds(elapsed().inWholeMilliseconds)
        }
    val names = CopyOnWriteArrayList<String>()

    data class Move(val at: Long, val position: Double, val millis: Long, val index: Int)

    val moves = Channel<Move>(Channel.UNLIMITED)
    @Volatile var respond = true
    @Volatile var reject = false
    @Volatile var ping = 0
    @Volatile var refusePing = false
    @Volatile var index = 5
    @Volatile var copies = 1
    @Volatile var stepCount = 1000L
    @Volatile var deviceName = "The Handy (FW4+)"
    @Volatile var axisType = "Position"
    @Volatile var gap = 0
    @Volatile var reverse = false
    @Volatile var bounds = Limits(Percent(80), Percent(0), Percent(100))
    @Volatile var socket: WebSocket? = null
    val listSeen = CompletableDeferred<Unit>()
    val allStopped = CompletableDeferred<Unit>()
    @Volatile var holdList = false
    @Volatile var replyToList: (() -> Unit)? = null
    val lost = CompletableDeferred<Unit>()
    private val server = MockWebServer()
    val hub = IntifaceHub(OkHttpClient(), scope)
    val address: String

    init {
        val listener =
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    socket = webSocket
                    val (kind, data) =
                        Json.parseToJsonElement(text).jsonArray.single().jsonObject.entries.single()
                    names += kind
                    val body = data.jsonObject
                    val id = body.getValue("Id")
                    var reply = "Ok"
                    val response = buildJsonObject {
                        put("Id", id)
                        when (kind) {
                            "RequestServerInfo" -> {
                                reply = "ServerInfo"
                                put("MessageVersion", 3)
                                put("MaxPingTime", ping)
                            }
                            "RequestDeviceList" -> {
                                reply = "DeviceList"
                                putJsonArray("Devices") {
                                    repeat(copies) { n ->
                                        addJsonObject {
                                            put("DeviceIndex", index + n)
                                            put("DeviceName", deviceName)
                                            put("DeviceMessageTimingGap", gap)
                                            putJsonObject("DeviceMessages") {
                                                putJsonArray("LinearCmd") {
                                                    addJsonObject {
                                                        put("ActuatorType", axisType)
                                                        put("FeatureDescriptor", "Slider")
                                                        put("StepCount", stepCount)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    addJsonObject {
                                        put("DeviceIndex", 22)
                                        put("DeviceName", "Vibrator")
                                        putJsonObject("DeviceMessages") {
                                            putJsonArray("ScalarCmd") {
                                                addJsonObject {
                                                    put("ActuatorType", "Vibrate")
                                                    put("StepCount", 20)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            "LinearCmd" -> {
                                val v = body.getValue("Vectors").jsonArray.single().jsonObject
                                moves.trySend(
                                    Move(
                                        clock.elapsed().inWholeMilliseconds,
                                        v.getValue("Position").jsonPrimitive.double,
                                        v.getValue("Duration").jsonPrimitive.long,
                                        body.getValue("DeviceIndex").jsonPrimitive.int,
                                    )
                                )
                                if (!respond) return
                                if (reject) reply = "Error"
                            }
                            "Ping" -> if (refusePing) reply = "Error"
                        }
                    }
                    val encoded =
                        JsonArray(listOf(buildJsonObject { put(reply, response) })).toString()
                    if (kind == "RequestDeviceList" && holdList) {
                        replyToList = { webSocket.send(encoded) }
                        listSeen.complete(Unit)
                    } else webSocket.send(encoded)
                    if (kind == "StopAllDevices") allStopped.complete(Unit)
                }
            }
        repeat(8) { server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build()) }
        server.start()
        address = server.url("/").toString().replaceFirst("http", "ws")
    }

    suspend fun find() = hub.targets(address)

    fun row(c: BridgeTarget) =
        SavedDevice(
            DeviceRef(DeviceId(c.name), c.name, c.family, ConnectionKind.IntifaceOnPhone),
            defaultDeviceSettings(c.family),
            address = address,
            bridgeIdentity = c.identity,
        )

    suspend fun linear(
        c: BridgeTarget? = null,
        onLoss: () -> Unit = { lost.complete(Unit) },
    ): IntifaceLinear =
        IntifaceLinear.connect(
            hub,
            row(c ?: find().first()),
            scope,
            clock,
            { bounds },
            { reverse },
            onLoss,
        )

    suspend fun next() = withTimeout(2500) { moves.receive() }

    fun batch(points: List<LinearPoint>, first: Boolean = true, defer: Boolean = false) =
        MotionBatch(listOf(MotionFrame(0, Percent(50))), Percent(50), bounds, first, defer, points)

    fun kill() {
        server.close()
    }

    override fun close() {
        scope.cancel()
        server.close()
    }
}
