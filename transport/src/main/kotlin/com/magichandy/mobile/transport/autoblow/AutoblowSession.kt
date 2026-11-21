// Ported from internal/transport/autoblow{,_control,_session,_ultra}.go at 1b80d72e.
package com.magichandy.mobile.transport.autoblow

import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceOutput
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.OutputSlice
import com.magichandy.mobile.core.ports.SavedDevice
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.TimedDevice
import com.magichandy.mobile.core.ports.WriteResult
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
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
    private val topSpeed: () -> Int = { 100 },
) : TimedDevice {
    private val ultra = device.family == DeviceFamily.AutoblowUltra
    private val readout = MutableStateFlow<DeviceOutput?>(null)
    override val feedback: StateFlow<DeviceOutput?>
        get() = readout

    private val valves =
        ValveSteps(wire) { (config() as? DeviceSettings.VacuGlide)?.mayStepValve == true }
    override val interval: Duration = if (ultra) 20.milliseconds else 100.milliseconds
    override val stopTimeout: Duration = 5.seconds
    private var stream: Pair<Epoch, Long>? = null
    private var sentUntil = -1L
    private var previous = -1
    @Volatile
    override var measuredLatency: Duration? = null
        private set

    private var halfRoundTrip = 0L
    private val uncertain = AtomicBoolean()
    private val flight = AtomicReference<Job?>()
    private val closed = AtomicBoolean()
    private val buttonFeedLost = AtomicBoolean()
    private val health = mutableListOf<Job>()

    internal suspend fun initialize() {
        val start = clock.elapsed()
        if (!recognizes(wire.state())) throw IOException("Device mode unavailable")
        val roundTrip = clock.elapsed() - start
        measuredLatency = roundTrip
        halfRoundTrip = (roundTrip.inWholeMilliseconds / 2).coerceIn(0, 300)
        if (halt { true } != HaltResult.Confirmed) throw IOException("Device idle unconfirmed")
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
        val opened = CompletableDeferred<Boolean>()
        health += scope.launch {
            try {
                wire.events({ opened.complete(true) }) {
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
            opened.complete(false)
            if (isActive && !closed.get()) {
                buttonFeedLost.set(true)
                lost()
            }
        }
        if (withTimeoutOrNull(5.seconds) { opened.await() } != true || buttonFeedLost.get())
            throw IOException("Device button connection unavailable")
    }

    private enum class TempoOutcome {
        NoOp,
        Mutated,
        FencedBeforeWrite,
    }

    override suspend fun output(slice: OutputSlice, fence: OutputFence): WriteResult {
        if (!fence.isCurrent()) return WriteResult.Stale
        if (closed.get() || buttonFeedLost.get())
            return if (fence.isCurrent()) WriteResult.Uncertain else WriteResult.Stale
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
                val motor = tempo(slice, fence)
                if (motor == TempoOutcome.FencedBeforeWrite) return WriteResult.Stale
                when (valves.apply(slice, fence)) {
                    ValveOutcome.FencedAfterWrite -> {
                        uncertain.set(true)
                        WriteResult.Uncertain
                    }
                    ValveOutcome.FencedBeforeWrite ->
                        if (motor == TempoOutcome.Mutated) {
                            if (fence.isCurrent()) WriteResult.Accepted
                            else {
                                uncertain.set(true)
                                WriteResult.Uncertain
                            }
                        } else WriteResult.Stale
                    ValveOutcome.NoOp ->
                        if (motor == TempoOutcome.Mutated || fence.isCurrent()) WriteResult.Accepted
                        else WriteResult.Stale
                    ValveOutcome.Applied -> WriteResult.Accepted
                }
            }
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

    private suspend fun position(slice: OutputSlice, fence: OutputFence): WriteResult {
        if (slice.atMillis + halfRoundTrip < sentUntil)
            return if (fence.isCurrent()) WriteResult.Accepted else WriteResult.Stale
        val initial = previous < 0
        val end = if (initial) slice.atMillis else slice.legEnd(600)
        val min = slice.limits.strokeLow.value
        val position =
            (min + slice.position(end) * (slice.limits.strokeHigh.value - min) / 100.0).roundToInt()
        val duration = maxOf(100, end - slice.atMillis)
        val speed =
            if (initial) 30
            else (abs(position - previous) * 250.0 / duration).roundToInt().coerceIn(1, 100)
        val nextSentUntil = if (initial) 100 else maxOf(end, slice.atMillis + 100)
        if (position != previous) {
            val requestedSpeed = minOf(speed, slice.limits.topSpeed.value, topSpeed())
            if (requestedSpeed < 1) return WriteResult.Stale
            val denied = AtomicBoolean()
            val submitted =
                try {
                    wire.put(
                        "goto",
                        buildJsonObject {
                            put("position", position)
                            put("speed", requestedSpeed)
                        },
                        false,
                        {
                            (fence.isCurrent() && requestedSpeed <= topSpeed()).also {
                                if (!it) denied.set(true)
                            }
                        },
                    )
                    !denied.get()
                } catch (failure: IOException) {
                    if (failure.isFencedAutoblow() || denied.get()) false else throw failure
                }
            if (!submitted) {
                DiagnosticReporter.record(DiagnosticKind.WriteFenced)
                return WriteResult.Stale
            }
            previous = position
            sentUntil = nextSentUntil
            return WriteResult.Accepted
        }
        sentUntil = nextSentUntil
        return if (fence.isCurrent()) WriteResult.Accepted else WriteResult.Stale
    }

    private suspend fun tempo(slice: OutputSlice, fence: OutputFence): TempoOutcome {
        val settings = config() as DeviceSettings.VacuGlide
        val range = settings.tempoRange
        val ceiling = minOf(range.last, slice.limits.topSpeed.value, topSpeed())
        val value =
            if (slice.tempo.value == 0 || ceiling < range.first) 0
            else
                (range.first + (slice.tempo.value - 1) * (range.last - range.first) / 99.0)
                    .roundToInt()
                    .coerceIn(range.first, ceiling)
        if (
            value == previous ||
                value != 0 &&
                    previous in range.first..ceiling &&
                    ((abs(value - previous) < 2 && value != range.first && value != ceiling) ||
                        slice.atMillis < sentUntil)
        )
            return TempoOutcome.NoOp
        if (value == 0) {
            when (halt(fence::isCurrent)) {
                HaltResult.Confirmed -> Unit
                HaltResult.FencedBeforeWrite -> {
                    DiagnosticReporter.record(DiagnosticKind.WriteFenced)
                    return TempoOutcome.FencedBeforeWrite
                }
                HaltResult.Unconfirmed -> throw IOException("Tempo Stop unconfirmed")
            }
        } else {
            val denied = AtomicBoolean()
            val submitted =
                try {
                    wire.put(
                        "target-speed",
                        buildJsonObject { put("targetSpeed", value) },
                        false,
                        {
                            val current = (config() as DeviceSettings.VacuGlide).tempoRange
                            (fence.isCurrent() && value in current && value <= topSpeed()).also {
                                if (!it) denied.set(true)
                            }
                        },
                    )
                    !denied.get()
                } catch (failure: IOException) {
                    if (failure.isFencedAutoblow() || denied.get()) false else throw failure
                }
            if (!submitted) {
                DiagnosticReporter.record(DiagnosticKind.WriteFenced)
                return TempoOutcome.FencedBeforeWrite
            }
        }
        previous = value
        readout.value = DeviceOutput.Tempo(Percent(value))
        sentUntil = slice.atMillis + 250
        return TempoOutcome.Mutated
    }

    override val checkConnection: suspend () -> Boolean = {
        !closed.get() && !buttonFeedLost.get() && recognizes(wire.state())
    }

    private fun recognizes(state: JsonObject): Boolean =
        (state["operationalMode"] as? JsonPrimitive)?.contentOrNull in operationalModes

    private fun stopped(state: JsonObject): Boolean {
        val mode = (state["operationalMode"] as? JsonPrimitive)?.contentOrNull
        return mode == "ONLINE_CONNECTED" ||
            if (ultra) mode == "OSCILLATOR_PAUSED" else mode == "TARGET_SPEED_PAUSED"
    }

    private suspend fun halt(allowed: () -> Boolean): HaltResult = coroutineScope {
        val closure = if (ultra) null else async { valves.settle(allowed) }
        val motor = stopMotor(allowed)
        val closed = closure?.await() ?: HaltResult.Confirmed
        if (motor == HaltResult.Confirmed && !ultra) readout.value = DeviceOutput.Tempo(Percent(0))
        if (ultra) motor else combineHalt(motor, closed)
    }

    private suspend fun stopMotor(allowed: () -> Boolean): HaltResult {
        val denied = AtomicBoolean()
        return try {
            val state =
                wire.put(
                    if (ultra) "oscillate/stop" else "target-speed/stop",
                    null,
                    true,
                    { allowed().also { if (!it) denied.set(true) } },
                )
            if (denied.get()) HaltResult.FencedBeforeWrite
            else if (stopped(state) || allowed() && stopped(wire.state(priorityRead = true)))
                HaltResult.Confirmed
            else HaltResult.Unconfirmed
        } catch (failure: IOException) {
            if (failure.isFencedAutoblow() || denied.get()) HaltResult.FencedBeforeWrite
            else HaltResult.Unconfirmed
        }
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
        if (evidence == HaltResult.Confirmed && fence.isCurrent()) StopEvidence.Acknowledged
        else
            StopEvidence.Unconfirmed(
                if (
                    evidence == HaltResult.FencedBeforeWrite &&
                        (!retry || first == HaltResult.FencedBeforeWrite)
                )
                    UnconfirmedCause.WriteFailed
                else UnconfirmedCause.NoReply
            )
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
            topSpeed: () -> Int = { 100 },
            button: (AutoblowButton) -> Unit,
        ): AutoblowSession {
            val (normal, priority) = AutoblowCloud.clients(http)
            val wire = AutoblowCloud(row.ref.family, key, normal, priority)
            wire.discover()
            val owner = AutoblowSession(row.ref, wire, clock, scope, config, lost, button, topSpeed)
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
