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

public class IntifaceVibrator
private constructor(
    override val device: DeviceRef,
    private val lease: BridgeLease,
    private val maximum: () -> Percent,
) : TimedDevice {
    private val target = lease.feature
    private val bridge = lease.socket
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
        bridge.features().singleOrNull { it.choice.identity == target.choice.identity }?.index ==
            target.index
    }

    public suspend fun close(): Unit = lease.close()

    override suspend fun output(slice: OutputSlice, fence: OutputFence): WriteResult {
        if (!fence.isCurrent()) return WriteResult.Stale
        val token = lease.generation
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
            bridge.request(
                "ScalarCmd",
                data = levels(steps),
                allowed = { fence.isCurrent() && lease.current(token) },
            )
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
        lease.invalidate()
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
                if (fence.wholeBridge) lease.session.stopAll(fence.epoch, fence::isCurrent)
                else {
                    lease.stopDevice(fence::isCurrent)
                    true
                }
            } catch (_: IOException) {
                false
            }
        val accepted = zero.await() || all
        StopEvidence.Unconfirmed(
            if (accepted) UnconfirmedCause.BridgeOnly else UnconfirmedCause.WriteFailed
        )
    }

    public companion object {
        public suspend fun connect(
            hub: IntifaceHub,
            row: SavedDevice,
            maximum: () -> Percent,
            lost: () -> Unit,
        ): IntifaceVibrator {
            if (row.ref.family != DeviceFamily.Vibrator) throw BridgeSelectionRequired()
            val lease =
                hub.claim(
                    row.address ?: throw BridgeSelectionRequired(),
                    row.bridgeIdentity ?: throw BridgeSelectionRequired(),
                    row.ref.family,
                    lost,
                )
            return IntifaceVibrator(row.ref, lease, maximum)
        }
    }
}

internal fun quantize(level: Double, steps: Int, last: Int, cap: Double): Int {
    if (level <= 0 || steps <= 0) return 0
    val target = level.coerceIn(0.0, 1.0) * steps
    val nearest = if (last < 0 || abs(target - last) > 0.75) target.roundToInt() else last
    val top = floor(cap.coerceIn(0.0, 1.0) * steps + 1e-9).toInt()
    return nearest.coerceIn(minOf(1, top), top)
}
