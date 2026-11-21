// Ported from internal/handyble/{session,session_rpc}.go at 1b80d72e.
package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.transport.ble.BluetoothConnector
import com.magichandy.mobile.transport.ble.BluetoothLink
import com.magichandy.mobile.transport.ble.BluetoothSetupWriteFailure
import com.magichandy.mobile.transport.ble.BluetoothWriteFenced
import com.magichandy.mobile.transport.ble.GattProfile
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Verified firmware-4 GATT session. Only the engine may submit motion. */
public class HandyBluetoothSession
private constructor(private val wire: BluetoothWire, owner: DeviceTransport) :
    DeviceTransport by owner {
    override suspend fun readBattery(): Percent? = wire.battery()

    /** Permanently fence this session, attempt RPC 715 and then retire its link. */
    public suspend fun disconnect(): StopEvidence = wire.disconnect()

    public companion object {
        /**
         * Connect to the selected device only. Readiness requires the profile and a correlated RPC
         * 715 reply. Supplying [clock] enables the optional firmware clock probe. [readMounting]
         * reads a nonblocking configuration snapshot at the start of each stream.
         */
        public suspend fun connect(
            device: DeviceRef,
            result: ScanResultId,
            connector: BluetoothConnector,
            scope: CoroutineScope,
            clock: Clock? = null,
            readMounting: () -> Boolean = { false },
        ): HandyBluetoothSession {
            require(
                device.family == DeviceFamily.Handy && device.connection == ConnectionKind.Bluetooth
            )
            val wire = openWire(result, connector, scope, clock)
            try {
                if (wire.stop { true } != StopEvidence.Acknowledged)
                    throw IOException("Handy initialization Stop unconfirmed")
                wire.synchronizeClock()
                return HandyBluetoothSession(
                    wire,
                    HandyTransport(
                        device,
                        wire,
                        readMounting = readMounting,
                    ),
                )
            } catch (failure: Throwable) {
                wire.close()
                throw failure
            }
        }

        private suspend fun openWire(
            id: ScanResultId,
            connector: BluetoothConnector,
            scope: CoroutineScope,
            clock: Clock?,
        ): BluetoothWire {
            var last: IOException? = null
            repeat(3) {
                var link: BluetoothLink? = null
                try {
                    val profile = GattProfile.Handy
                    val opened = bounded(5.seconds) { connector.connect(id, profile, 5.seconds) }
                    link = opened
                    val limit =
                        minOf(bounded(2.seconds) { opened.requestMtu(517) } - 3, profile.maxBytes)
                    if (limit < 48) throw IOException("Handy MTU too small")
                    val characteristics = bounded(2.seconds) { opened.discover(profile.service) }
                    if (!characteristics.containsAll(listOf(profile.write, profile.notify)))
                        throw IOException("Handy characteristics missing")
                    val events = bounded(2.seconds) { opened.notifications(profile.notify) }
                    return BluetoothWire(opened, limit, events, scope, clock)
                } catch (failure: IOException) {
                    DiagnosticReporter.record(DiagnosticKind.DeviceConnectionFailed)
                    link?.retire()
                    if (failure is BluetoothSetupWriteFailure) throw failure
                    last = failure
                } catch (cancelled: CancellationException) {
                    link?.retire()
                    throw cancelled
                } catch (failure: Exception) {
                    link?.retire()
                    throw failure
                }
            }
            throw last ?: IOException("Handy connection failed")
        }
    }
}

private suspend fun <T : Any> bounded(timeout: Duration, block: suspend () -> T): T =
    withTimeoutOrNull(timeout) { block() } ?: throw IOException("Bluetooth operation timed out")

private class BluetoothWire(
    private val link: BluetoothLink,
    private val limit: Int,
    events: Flow<ByteArray>,
    private val scope: CoroutineScope,
    clock: Clock?,
) : TimedHandyWire(clock) {
    private var clockReading: HandyClockReading? = null
    override val maxPoints = minOf(20, (limit - 25) / 11)
    override val stopTimeout = 2.seconds

    suspend fun battery() = link.readBattery()

    override suspend fun readPosition(allowed: () -> Boolean) = link.readSliderPosition(allowed)

    private val gatt = Mutex()
    private val sequence = AtomicLong()

    private data class PlayingStream(val id: Long, val unhealthy: Boolean = false)

    private val active = AtomicReference<PlayingStream?>()
    private var preparedStream: Long? = null
    private val closed = AtomicBoolean()
    private val disconnecting = AtomicBoolean()
    private val replies = ConcurrentHashMap<Long, CompletableDeferred<RpcReply>>()
    private val reader =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                events.collect { bytes ->
                    decodeReply(bytes, ::reported)?.let { reply ->
                        replies[reply.id]?.complete(reply)
                    }
                }
            } catch (_: IOException) {
                DiagnosticReporter.record(DiagnosticKind.BluetoothNotificationsFailed)
            } finally {
                retire()
            }
        }

    private fun retire() {
        if (closed.compareAndSet(false, true)) link.retire()
        replies.values.forEach { it.completeExceptionally(IOException("Handy link retired")) }
    }

    fun close() {
        retire()
        reader.cancel()
    }

    suspend fun synchronizeClock() {
        val timer = clock ?: return
        fun allowed() = !closed.get() && !disconnecting.get()
        clockReading =
            synchronizeBluetoothClock(
                timer,
                read = {
                    var time: Long? = null
                    request(
                        HandyCommand.ClockGet,
                        ::allowed,
                        received = { time = it.deviceTimeMillis },
                    )
                    time
                },
                set = { request(it, ::allowed) == WriteResult.Accepted },
            )
        if (closed.get()) throw IOException("Handy link lost during clock synchronization")
        if (clockReading == null) DiagnosticReporter.record(DiagnosticKind.ClockSyncUnavailable)
    }

    private fun setActiveBestEffort(active: Boolean) {
        try {
            link.setActive(active)
        } catch (_: SecurityException) {
            /* Best-effort priority hint; revoked Bluetooth access cannot fail playback or Stop. */
        } catch (_: IOException) {
            /* Best-effort priority hint; link failure is reported by GATT I/O. */
        }
    }

    override suspend fun playTimestamp(allowed: () -> Boolean): Long? = clock?.let {
        clockReading?.at(it.elapsed())
    }

    override suspend fun dispatch(command: HandyCommand, allowed: () -> Boolean): WriteResult {
        if (!allowed()) return WriteResult.Stale
        if (command is HandyCommand.Setup && !disconnecting.get()) {
            active.set(null)
            preparedStream = command.stream
            setActiveBestEffort(true)
        }
        if (command is HandyCommand.Add && command.refilling && active.get()?.unhealthy == true)
            return WriteResult.Uncertain
        var state: HandyPlayback? = null
        val result =
            request(
                command,
                { !disconnecting.get() && allowed() },
                received = { reply ->
                    state = reply.playback?.takeIf { it.stream == preparedStream }?.state
                },
            )
        if (result != WriteResult.Accepted) return result
        if (state?.rejects(command) == true) return WriteResult.Uncertain
        if (command is HandyCommand.Play) active.set(preparedStream?.let(::PlayingStream))
        return if (
            command is HandyCommand.Add && command.refilling && active.get()?.unhealthy == true
        )
            WriteResult.Uncertain
        else WriteResult.Accepted
    }

    private fun reported(observation: HandyPlaybackReport) {
        if (observation.state != HandyPlayback.Playing)
            active.updateAndGet { stream ->
                if (stream?.id == observation.stream) stream.copy(unhealthy = true) else stream
            }
    }

    private suspend fun request(
        command: HandyCommand,
        allowed: () -> Boolean,
        received: (RpcReply) -> Unit = {},
        sent: () -> Unit = {},
    ): WriteResult {
        fun refused() = if (!allowed()) WriteResult.Stale else WriteResult.Uncertain
        fun valid() = !closed.get() && allowed()
        if (!valid()) return refused()
        val id = sequence.incrementAndGet()
        if (id >= 0xffff_ffffL) {
            close()
            return WriteResult.Uncertain
        }
        val bytes = command.encode(id)
        if (bytes.size > limit) throw IOException("Handy RPC exceeds MTU")
        val reply = CompletableDeferred<RpcReply>()
        replies[id] = reply
        val refusedAtWrite = AtomicBoolean()
        try {
            if (
                !submit(
                    bytes,
                    command == HandyCommand.StopCurrentMode,
                    ::valid,
                    refusedAtWrite,
                    sent,
                )
            ) {
                if (!allowed()) DiagnosticReporter.record(DiagnosticKind.WriteFenced)
                return refused()
            }
            val response = withTimeoutOrNull(700.milliseconds) { reply.await() }
            if (response == null) {
                if (command == HandyCommand.ClockGet || command is HandyCommand.ClockSet)
                    return WriteResult.Uncertain
                throw IOException("Handy acknowledgement timed out")
            }
            if (!valid() || response.rejected || response.method != command.method)
                return WriteResult.Uncertain
            received(response)
            return WriteResult.Accepted
        } catch (failure: IOException) {
            if (failure is BluetoothWriteFenced || refusedAtWrite.get()) {
                if (!allowed()) DiagnosticReporter.record(DiagnosticKind.WriteFenced)
                return refused()
            }
            close()
            throw failure
        } finally {
            replies.remove(id)
        }
    }

    private suspend fun submit(
        bytes: ByteArray,
        stop: Boolean,
        valid: () -> Boolean,
        refused: AtomicBoolean,
        sent: () -> Unit,
    ): Boolean =
        // The device lane owns the GATT slot through release, even if ordinary output is parked.
        withContext(scope.coroutineContext.minusKey(Job)) {
            gatt.withLock {
                if (!valid()) return@withLock false
                withContext(NonCancellable) {
                    try {
                        writeNative(bytes, stop) { valid().also { if (!it) refused.set(true) } }
                        if (!refused.get()) sent()
                        !refused.get()
                    } catch (failure: IOException) {
                        // Retire an uncertain generation before handing its slot to Stop.
                        if (failure !is BluetoothWriteFenced && !refused.get()) close()
                        throw failure
                    }
                }
            }
        }

    private suspend fun writeNative(bytes: ByteArray, stop: Boolean, admitted: () -> Boolean) {
        // The platform callback may ignore cancellation. Its owner outlives the bounded wait.
        val operation = scope.async {
            try {
                if (stop) link.writeStop(GattProfile.Handy.write, bytes, true, admitted)
                else link.write(GattProfile.Handy.write, bytes, true, admitted)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure
            }
        }
        try {
            bounded(1.seconds) {
                operation.await()?.let { throw it }
                Unit
            }
        } finally {
            operation.cancel()
        }
    }

    override suspend fun stop(allowed: () -> Boolean): StopEvidence {
        var sent = false
        val acknowledged =
            try {
                withTimeoutOrNull(stopTimeout) {
                    request(HandyCommand.StopCurrentMode, allowed) { sent = true } ==
                        WriteResult.Accepted
                }
                    ?: run {
                        close()
                        false
                    }
            } catch (_: IOException) {
                DiagnosticReporter.record(DiagnosticKind.TransportStopFailed)
                false
            }
        if (acknowledged) setActiveBestEffort(false)
        return if (acknowledged) StopEvidence.Acknowledged
        else
            StopEvidence.Unconfirmed(
                if (sent) UnconfirmedCause.NoReply else UnconfirmedCause.WriteFailed
            )
    }

    suspend fun disconnect(): StopEvidence {
        disconnecting.set(true)
        return try {
            stop { true }
        } finally {
            close()
        }
    }
}
