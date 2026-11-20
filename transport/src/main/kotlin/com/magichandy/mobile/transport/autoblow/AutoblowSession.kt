// Ported from internal/transport/autoblow{,_control,_session,_ultra}.go at 1b80d72e.
package com.magichandy.mobile.transport.autoblow

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

public enum class AutoblowButton {
    Stop,
    Faster,
    Slower,
}

public class AutoblowSession
internal constructor(
    override val device: DeviceRef,
    internal val wire: AutoblowWire,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val config: () -> DeviceSettings,
    private val lost: () -> Unit,
    private val button: (AutoblowButton) -> Unit,
) : TimedDevice {
    private val ultra = device.family == DeviceFamily.AutoblowUltra
    private val readout = kotlinx.coroutines.flow.MutableStateFlow<DeviceOutput?>(null)
    override val feedback: kotlinx.coroutines.flow.StateFlow<DeviceOutput?>
        get() = readout

    private val valves =
        ValveSteps(wire) { (config() as? DeviceSettings.VacuGlide)?.mayStepValve == true }
    override val interval: Duration = if (ultra) 20.milliseconds else 100.milliseconds
    override val stopTimeout: Duration = 5.seconds
    private var stream: Pair<Epoch, Long>? = null
    private var sentUntil = -1L
    private var previous = -1
    private var halfRoundTrip = 0L
    private val uncertain = AtomicBoolean()
    private val flight = AtomicReference<Job?>()
    private val closed = AtomicBoolean()
    private val health = mutableListOf<Job>()

    internal suspend fun initialize() {
        val start = clock.elapsed()
        if (!recognizes(wire.state())) throw IOException("Device mode unavailable")
        halfRoundTrip = ((clock.elapsed() - start).inWholeMilliseconds / 2).coerceIn(0, 300)
        if (!halt { true }) throw IOException("Device idle unconfirmed")
        health += scope.launch {
            try {
                while (isActive) {
                    delay(5.seconds)
                    val state = wire.state()
                    if (!recognizes(state)) throw IOException("Device health unavailable")
                }
            } catch (_: IOException) {
                if (!closed.get()) lost()
            }
        }
        health += scope.launch {
            var backoff = 1
            while (isActive) {
                try {
                    wire.events {
                        if (!closed.get()) {
                            when (it) {
                                "pause-button-pressed" -> if (ultra) button(AutoblowButton.Stop)
                                "mode-button-pressed" -> if (!ultra) button(AutoblowButton.Stop)
                                "speed-up-button-pressed",
                                "speed-plus-button-pressed" -> button(AutoblowButton.Faster)
                                "speed-down-button-pressed",
                                "speed-minus-button-pressed" -> button(AutoblowButton.Slower)
                            }
                        }
                    }
                } catch (_: IOException) {
                    DiagnosticReporter.record(DiagnosticKind.DeviceConnectionFailed)
                }
                delay(backoff.seconds)
                backoff = (backoff * 2).coerceAtMost(30)
            }
        }
    }

    override suspend fun output(slice: OutputSlice, fence: OutputFence): WriteResult {
        if (!fence.isCurrent() || closed.get()) return WriteResult.Stale
        val g = fence.epoch to fence.streamGeneration
        if (stream != g) {
            stream = g
            sentUntil = -1
            previous = -1
        }
        val job = currentCoroutineContext().job
        if (!flight.compareAndSet(null, job)) return WriteResult.Uncertain
        return try {
            if (!fence.isCurrent()) return WriteResult.Stale
            if (ultra) position(slice, fence)
            else {
                tempo(slice, fence)
                valves.apply(slice, fence)
            }
            WriteResult.Accepted
        } catch (cancelled: CancellationException) {
            uncertain.set(true)
            throw cancelled
        } catch (_: IOException) {
            uncertain.set(true)
            WriteResult.Uncertain
        } finally {
            flight.compareAndSet(job, null)
        }
    }

    private suspend fun position(slice: OutputSlice, fence: OutputFence) {
        if (slice.atMillis + halfRoundTrip < sentUntil) return
        val initial = previous < 0
        val end = if (initial) slice.atMillis else slice.legEnd(600)
        val min = slice.limits.strokeLow.value
        val position =
            (min + slice.position(end) * (slice.limits.strokeHigh.value - min) / 100.0).roundToInt()
        val duration = maxOf(100, end - slice.atMillis)
        val speed =
            if (initial) 30
            else (abs(position - previous) * 250.0 / duration).roundToInt().coerceIn(1, 100)
        sentUntil = if (initial) 100 else maxOf(end, slice.atMillis + 100)
        if (position != previous) {
            wire.put(
                "goto",
                buildJsonObject {
                    put("position", position)
                    put("speed", minOf(speed, slice.limits.topSpeed.value))
                },
                false,
                fence::isCurrent,
            )
            previous = position
        }
    }

    private suspend fun tempo(slice: OutputSlice, fence: OutputFence) {
        val settings = config() as DeviceSettings.VacuGlide
        val range = settings.tempoRange
        val value =
            if (slice.tempo.value == 0) 0
            else
                (range.first + (slice.tempo.value - 1) * (range.last - range.first) / 99.0)
                    .roundToInt()
                    .coerceIn(5, 100)
        if (
            value == previous ||
                value != 0 &&
                    previous >= 0 &&
                    (abs(value - previous) < 2 || slice.atMillis < sentUntil)
        )
            return
        if (value == 0) {
            if (!halt(fence::isCurrent)) throw IOException("Tempo Stop unconfirmed")
        } else
            wire.put(
                "target-speed",
                buildJsonObject { put("targetSpeed", value) },
                false,
                fence::isCurrent,
            )
        previous = value
        readout.value = DeviceOutput.Tempo(Percent(value))
        sentUntil = slice.atMillis + 250
    }

    override val checkConnection: suspend () -> Boolean = {
        !closed.get() && recognizes(wire.state())
    }

    private fun recognizes(state: JsonObject): Boolean =
        (state["operationalMode"] as? JsonPrimitive)?.contentOrNull in operationalModes

    private fun stopped(state: JsonObject): Boolean {
        val mode = (state["operationalMode"] as? JsonPrimitive)?.contentOrNull
        return mode == "ONLINE_CONNECTED" ||
            if (ultra) mode == "OSCILLATOR_PAUSED" else mode == "TARGET_SPEED_PAUSED"
    }

    private suspend fun halt(allowed: () -> Boolean): Boolean = coroutineScope {
        val closure = if (ultra) null else async { valves.settle(allowed) }
        val motor = stopMotor(allowed)
        val closed = closure?.await() ?: true
        if (motor && !ultra) readout.value = DeviceOutput.Tempo(Percent(0))
        motor && closed
    }

    private suspend fun stopMotor(allowed: () -> Boolean): Boolean =
        try {
            val state =
                wire.put(if (ultra) "oscillate/stop" else "target-speed/stop", null, true, allowed)
            stopped(state) || allowed() && stopped(wire.state(priorityRead = true))
        } catch (_: IOException) {
            false
        }

    override suspend fun stop(fence: OutputFence): StopEvidence = coroutineScope {
        val previousFlight = flight.get()
        val retry = uncertain.getAndSet(false) || previousFlight != null
        val cancel = previousFlight?.let {
            launch {
                delay(300.milliseconds)
                if (flight.get() === it) it.cancel()
            }
        }
        val resend =
            if (retry)
                async {
                    delay(750.milliseconds)
                    halt(fence::isCurrent)
                }
            else null
        val first = halt(fence::isCurrent)
        val evidence = resend?.await() ?: first
        cancel?.cancel()
        if (evidence && fence.isCurrent()) StopEvidence.Acknowledged
        else StopEvidence.Unconfirmed(UnconfirmedCause.NoReply)
    }

    public fun close() {
        closed.set(true)
        health.forEach { it.cancel() }
    }

    public companion object {
        private val operationalModes =
            setOf(
                "ONLINE_CONNECTED",
                "GO_TO",
                "OSCILLATOR_PLAYING",
                "OSCILLATOR_PAUSED",
                "TARGET_SPEED_PLAYING",
                "TARGET_SPEED_PAUSED",
                "LOCAL_SCRIPT_PLAYING",
                "LOCAL_SCRIPT_PAUSED",
                "SYNC_SCRIPT_PLAYING",
                "SYNC_SCRIPT_PAUSED",
            )

        /**
         * Read-only vendor lookup for a previously lost VacuGlide. No session or event stream is
         * opened.
         */
        public suspend fun reachable(key: Secret, http: OkHttpClient): Boolean =
            try {
                val (normal, priority) = AutoblowCloud.clients(http)
                AutoblowCloud(DeviceFamily.VacuGlide, key, normal, priority).discover()
                true
            } catch (_: IOException) {
                false
            }

        /**
         * Resolve one regional endpoint, check state, stop, then attach bounded health and button
         * listeners.
         */
        public suspend fun connect(
            row: SavedDevice,
            key: Secret,
            http: OkHttpClient,
            clock: Clock,
            scope: CoroutineScope,
            config: () -> DeviceSettings,
            lost: () -> Unit,
            button: (AutoblowButton) -> Unit,
        ): AutoblowSession {
            val (normal, priority) = AutoblowCloud.clients(http)
            val wire = AutoblowCloud(row.ref.family, key, normal, priority)
            wire.discover()
            val owner = AutoblowSession(row.ref, wire, clock, scope, config, lost, button)
            try {
                owner.initialize()
                return owner
            } catch (e: Throwable) {
                owner.close()
                throw e
            }
        }

        /**
         * Detached recovery performs lookup and Stop only. No device event or playback loop is
         * opened.
         */
        public suspend fun recover(
            row: SavedDevice,
            key: Secret,
            http: OkHttpClient,
            clock: Clock,
            scope: CoroutineScope,
            fence: OutputFence,
        ): StopEvidence {
            val (normal, priority) = AutoblowCloud.clients(http)
            val wire = AutoblowCloud(row.ref.family, key, normal, priority)
            return try {
                wire.discover()
                AutoblowSession(row.ref, wire, clock, scope, { row.settings }, {}, {}).stop(fence)
            } catch (_: IOException) {
                StopEvidence.Unconfirmed(UnconfirmedCause.LinkLost)
            }
        }
    }
}
