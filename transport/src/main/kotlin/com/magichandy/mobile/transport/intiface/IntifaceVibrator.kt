// Ported from internal/transport/intiface_vibrator*.go and internal/vibration/render.go at
// 1b80d72e.
package com.magichandy.mobile.transport.intiface

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import java.io.IOException
import kotlin.math.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

internal data class Motor(val index: Int, val steps: Int)

private data class Target(val index: Int, val name: String, val gap: Int, val motors: List<Motor>)

public class IntifaceVibrator
private constructor(
    override val device: DeviceRef,
    private val bridge: ButtplugSocket,
    private val target: Target,
    private val maximum: () -> Percent,
) : TimedDevice {
    override val stopTimeout: kotlin.time.Duration = 2.seconds
    override val interval: kotlin.time.Duration = maxOf(100, target.gap).milliseconds
    private val readout = kotlinx.coroutines.flow.MutableStateFlow<DeviceOutput?>(null)
    override val feedback: kotlinx.coroutines.flow.StateFlow<DeviceOutput?>
        get() = readout

    private var generation: Pair<Epoch, Long>? = null
    private var last = emptyList<Int>()
    private var smoothed = 0.0
    private var previousMillis = 0L

    override val checkConnection: suspend () -> Boolean = {
        val list = bridge.request("RequestDeviceList", "DeviceList")["Devices"] as? JsonArray
        list.orEmpty().any {
            (it as? JsonObject)?.get("DeviceIndex") == JsonPrimitive(target.index)
        }
    }

    public fun close() {
        bridge.close()
    }

    override suspend fun output(slice: OutputSlice, fence: OutputFence): WriteResult {
        if (!fence.isCurrent()) return WriteResult.Stale
        val g = fence.epoch to fence.streamGeneration
        if (generation != g) {
            generation = g
            last = emptyList()
            smoothed = 0.0
            previousMillis = 0
        }
        val raw = 1 - exp(-slice.speed() / 1.2)
        val tau = if (raw > smoothed) 40.0 else 100.0
        smoothed += (1 - exp(-(slice.atMillis - previousMillis) / tau)) * (raw - smoothed)
        previousMillis = slice.atMillis
        val drive =
            (slice.vibration?.level(slice.vibrationMillis, smoothed) ?: smoothed) *
                (slice.atMillis / 2000.0).coerceIn(0.0, 1.0)
        val cap = maximum().value / 100.0
        val steps =
            target.motors.mapIndexed { i, m ->
                quantize(
                    if (drive < 0.02) 0.0 else drive * cap,
                    m.steps,
                    last.getOrNull(i) ?: -1,
                    cap,
                )
            }
        if (steps != last) {
            bridge.request("ScalarCmd", data = levels(steps), allowed = fence::isCurrent)
            last = steps
        }
        val fraction =
            target.motors.indices.maxOf { i -> steps[i].toDouble() / target.motors[i].steps }
        val follows =
            slice.vibration?.mode?.let {
                it == com.magichandy.mobile.core.score.VibrationMode.Follow
            } ?: true
        readout.value =
            DeviceOutput.Level(
                Percent((fraction * 100).roundToInt()),
                Percent((cap * 100).roundToInt()),
                follows,
            )
        return WriteResult.Accepted
    }

    private fun levels(steps: List<Int>) = buildJsonObject {
        put("DeviceIndex", target.index)
        put(
            "Scalars",
            JsonArray(
                target.motors.mapIndexed { i, m ->
                    buildJsonObject {
                        put("Index", m.index)
                        put("ActuatorType", "Vibrate")
                        put("Scalar", steps[i].toDouble() / m.steps)
                    }
                }
            ),
        )
    }

    override suspend fun stop(fence: OutputFence): StopEvidence = supervisorScope {
        val zero = async {
            try {
                bridge.request(
                    "ScalarCmd",
                    data = levels(target.motors.map { 0 }),
                    allowed = fence::isCurrent,
                )
                true
            } catch (_: IOException) {
                false
            }
        }
        val all =
            try {
                bridge.request("StopAllDevices", allowed = fence::isCurrent)
                true
            } catch (_: IOException) {
                false
            }
        val accepted = zero.await() || all
        StopEvidence.Unconfirmed(
            if (accepted) UnconfirmedCause.BridgeOnly else UnconfirmedCause.WriteFailed
        )
    }

    public companion object {
        /**
         * Discover endpoints using the v3 handshake and device list. This issues no actuator
         * command.
         */
        public suspend fun choices(client: OkHttpClient, address: String): List<IntifaceChoice> {
            val bridge = ButtplugSocket {}
            return try {
                bridge.open(client, address)
                bridge.identify()
                bridge.vibrators().map { IntifaceChoice(it.index, it.name) }
            } finally {
                bridge.close()
            }
        }

        /**
         * Requires one identifiable vibrator or an explicitly selected device index. Linear devices
         * are excluded.
         */
        public suspend fun connect(
            ref: DeviceRef,
            address: String,
            selectedIndex: Int?,
            client: OkHttpClient,
            scope: CoroutineScope,
            maximum: () -> Percent,
            lost: () -> Unit,
        ): IntifaceVibrator {
            val bridge = ButtplugSocket(lost)
            try {
                bridge.open(client, address)
                val info = bridge.identify()
                val offered = bridge.vibrators()
                val chosen =
                    offered.singleOrNull { selectedIndex == null || it.index == selectedIndex }
                        ?: throw IOException("Select one vibrator")
                bridge.deviceIndex = chosen.index
                bridge.request(
                    "StopDeviceCmd",
                    data = buildJsonObject { put("DeviceIndex", chosen.index) },
                )
                val timeout = info["MaxPingTime"]?.jsonPrimitive?.longOrNull ?: 0
                if (timeout > 0)
                    scope.launch {
                        try {
                            while (isActive) {
                                delay((timeout / 2).coerceAtLeast(1))
                                bridge.request("Ping")
                            }
                        } catch (_: IOException) {
                            if (bridge.close()) lost()
                        }
                    }
                return IntifaceVibrator(ref, bridge, chosen, maximum)
            } catch (e: Throwable) {
                bridge.close()
                throw e
            }
        }
    }
}

private suspend fun ButtplugSocket.identify(): JsonObject {
    val info =
        request(
            "RequestServerInfo",
            "ServerInfo",
            buildJsonObject {
                put("ClientName", "MagicHandy")
                put("MessageVersion", 3)
            },
        )
    if (info["MessageVersion"] != JsonPrimitive(3)) throw IOException("Unsupported bridge version")
    return info
}

private suspend fun ButtplugSocket.vibrators(): List<Target> {
    val peers =
        request("RequestDeviceList", "DeviceList")["Devices"] as? JsonArray
            ?: throw IOException("Invalid bridge device list")
    return peers.take(128).mapNotNull { entry ->
        val d = entry as? JsonObject ?: return@mapNotNull null
        val commands = d["DeviceMessages"] as? JsonObject ?: return@mapNotNull null
        if (
            "LinearCmd" in commands ||
                d["DeviceName"]?.jsonPrimitive?.content.orEmpty().contains("Handy", true)
        )
            return@mapNotNull null
        val features =
            (commands["ScalarCmd"] as? JsonArray).orEmpty().mapIndexedNotNull { index, f ->
                val o = f.jsonObject
                val count = o["StepCount"]?.jsonPrimitive?.intOrNull ?: 0
                if (o["ActuatorType"] == JsonPrimitive("Vibrate") && count in 1..65535)
                    Motor(index, count)
                else null
            }
        if (features.isEmpty()) null
        else
            Target(
                (d["DeviceIndex"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
                    ?: return@mapNotNull null,
                (d["DeviceName"] as? JsonPrimitive)
                    ?.content
                    .orEmpty()
                    .filterNot(Char::isISOControl)
                    .take(120),
                (d["DeviceMessageTimingGap"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(
                    0,
                    1000,
                ),
                features,
            )
    }
}

internal fun quantize(level: Double, steps: Int, last: Int, cap: Double): Int {
    if (level <= 0 || steps <= 0) return 0
    val target = level.coerceIn(0.0, 1.0) * steps
    val nearest = if (last < 0 || abs(target - last) > 0.75) target.roundToInt() else last
    val top = floor(cap.coerceIn(0.0, 1.0) * steps + 1e-9).toInt()
    return nearest.coerceIn(minOf(1, top), top)
}
