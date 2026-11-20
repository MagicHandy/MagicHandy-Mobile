// Ported from internal/handyble/{session,session_rpc}.go at 9a4c36cf.
package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.transport.ble.BluetoothConnector
import com.magichandy.mobile.transport.ble.BluetoothLink
import com.magichandy.mobile.transport.ble.GattProfile
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    /** Permanently fence this session, attempt RPC 715 and then retire its link. */
    public suspend fun disconnect(): StopEvidence = wire.disconnect()

    public companion object {
        /**
         * Connect to the selected device only. Readiness requires the profile and a correlated RPC
         * 715 reply.
         */
        public suspend fun connect(
            device: DeviceRef,
            result: ScanResultId,
            connector: BluetoothConnector,
            scope: CoroutineScope,
        ): HandyBluetoothSession {
            require(
                device.family == DeviceFamily.Handy && device.connection == ConnectionKind.Bluetooth
            )
            val wire = openWire(result, connector, scope)
            try {
                if (wire.stop { true } != StopEvidence.Acknowledged)
                    throw IOException("Handy initialization Stop unconfirmed")
                return HandyBluetoothSession(wire, HandyTransport(device, wire))
            } catch (failure: Throwable) {
                wire.close()
                throw failure
            }
        }

        private suspend fun openWire(
            id: ScanResultId,
            connector: BluetoothConnector,
            scope: CoroutineScope,
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
                    return BluetoothWire(opened, limit, events, scope)
                } catch (failure: IOException) {
                    DiagnosticReporter.record(DiagnosticKind.DeviceConnectionFailed)
                    link?.retire()
                    last = failure
                } catch (cancelled: CancellationException) {
                    link?.retire()
                    throw cancelled
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
) : HandyWire {
    override val maxPoints = minOf(20, (limit - 25) / 11)
    override val stopTimeout = 2.seconds
    private val gatt = Mutex()
    private val sequence = AtomicLong()
    private val closed = AtomicBoolean()
    private val disconnecting = AtomicBoolean()
    private val replies = ConcurrentHashMap<Long, CompletableDeferred<RpcReply>>()
    private val reader =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                events.collect { bytes ->
                    decodeReply(bytes)?.let { reply -> replies[reply.id]?.complete(reply) }
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

    override suspend fun command(command: HandyCommand, allowed: () -> Boolean): WriteResult =
        request(command, { !disconnecting.get() && allowed() })

    private suspend fun request(
        command: HandyCommand,
        allowed: () -> Boolean,
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
            if (!submit(bytes, ::valid, refusedAtWrite, sent)) return refused()
            val response = bounded(700.milliseconds) { reply.await() }
            return if (valid() && !response.rejected && response.method == command.method)
                WriteResult.Accepted
            else WriteResult.Uncertain
        } catch (failure: IOException) {
            if (refusedAtWrite.get()) {
                DiagnosticReporter.record(DiagnosticKind.WriteFenced)
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
                        writeNative(bytes) { valid().also { if (!it) refused.set(true) } }
                        if (!refused.get()) sent()
                        !refused.get()
                    } catch (failure: IOException) {
                        // Retire an uncertain generation before handing its slot to Stop.
                        if (!refused.get()) close()
                        throw failure
                    }
                }
            }
        }

    private suspend fun writeNative(bytes: ByteArray, admitted: () -> Boolean) {
        // The platform callback may ignore cancellation. Its owner outlives the bounded wait.
        val operation = scope.async {
            try {
                link.write(GattProfile.Handy.write, bytes, true, admitted)
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
