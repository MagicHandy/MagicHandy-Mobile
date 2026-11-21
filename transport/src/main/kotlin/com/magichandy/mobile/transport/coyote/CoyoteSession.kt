// Ported from internal/transport/coyote.go and internal/estim/strength.go at 1b80d72e.
package com.magichandy.mobile.transport.coyote

import com.magichandy.mobile.core.api.CoyoteChannel
import com.magichandy.mobile.core.api.DeviceOutput
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.ports.CoyoteControls
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.OutputSlice
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.TimedDevice
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.transport.ble.BluetoothConnector
import com.magichandy.mobile.transport.ble.BluetoothLink
import com.magichandy.mobile.transport.ble.BluetoothSetupWriteFailure
import com.magichandy.mobile.transport.ble.BluetoothWriteFenced
import com.magichandy.mobile.transport.ble.GattProfile
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

public class CoyoteSession
private constructor(
    override val device: DeviceRef,
    private val link: BluetoothLink,
    private val scope: CoroutineScope,
    private val settings: () -> DeviceSettings.Coyote,
) : TimedDevice, CoyoteControls {
    override val interval: Duration = 100.milliseconds
    override val stopTimeout: Duration = 1500.milliseconds
    private val profile = GattProfile.Coyote
    private val actual = MutableStateFlow<DeviceOutput.Channels?>(null)
    private val stopping = MutableStateFlow<Pair<Int, CompletableDeferred<Unit>>?>(null)

    private data class Step(
        val strength: Strength,
        val epoch: Epoch,
        val generation: Long,
        val sentAt: Long? = null,
    )

    private val change = MutableStateFlow<Step?>(null)
    override val reported: StateFlow<DeviceOutput.Channels?> =
        combine(actual, change) { measured, pending ->
                measured?.copy(isAwaitingReport = pending != null)
            }
            .stateIn(scope, SharingStarted.Eagerly, null)
    override val feedback: StateFlow<DeviceOutput?>
        get() = reported

    private val zeroSequence = java.util.concurrent.atomic.AtomicInteger()
    private val sequence = java.util.concurrent.atomic.AtomicInteger()

    private enum class LinkState {
        Connecting,
        Ready,
        Closed,
    }

    private val state = AtomicReference(LinkState.Connecting)
    private var reader: Job? = null
    private var stream: Pair<Epoch, Long>? = null
    private var renderer = PulseRenderer()

    override suspend fun readBattery(): Percent? = link.readBattery()

    // The verified GATT generation and its subscribed B1 feed own liveness. Rediscovering services
    // during playback would put a three-second native operation ahead of a physical Stop.
    override val checkConnection: suspend () -> Boolean = { state.get() == LinkState.Ready }

    private fun next() = sequence.updateAndGet { it % 15 + 1 }

    private fun receive(bytes: ByteArray) {
        if (state.get() == LinkState.Closed) return
        if (bytes.size < 4 || bytes[0] != 0xb1.toByte()) return
        val seq = bytes[1].toInt() and 255
        val a = bytes[2].toInt() and 255
        val b = bytes[3].toInt() and 255
        if (seq > 15 || a > 200 || b > 200) return
        val pending = change.value
        val stepReply = seq != 0 && pending?.sentAt != null && pending.strength.sequence == seq
        val zeroReply = seq != 0 && a == 0 && b == 0 && zeroSequence.compareAndSet(seq, 0)
        if (seq != 0 && !stepReply && !zeroReply) return
        actual.value = DeviceOutput.Channels(a, b)
        if (zeroReply) stopping.value?.takeIf { it.first == seq }?.second?.complete(Unit)
        if (stepReply) change.compareAndSet(pending, null)
    }

    override fun step(
        channel: CoyoteChannel,
        increase: Boolean,
        epoch: Epoch,
        generation: Long,
    ): Boolean {
        val measured = actual.value ?: return false
        if (stopping.value != null || state.get() != LinkState.Ready) return false
        val cfg = settings()
        val isA = channel == CoyoteChannel.A
        val current = if (isA) measured.strengthA else measured.strengthB
        if (
            increase && current >= (if (isA) cfg.maxStrengthA else cfg.maxStrengthB) ||
                !increase && current == 0
        )
            return false
        val mode = if (increase) 1 else 2
        val s = if (isA) Strength(next(), mode, 1) else Strength(next(), modeB = mode, b = 1)
        return change.compareAndSet(null, Step(s, epoch, generation))
    }

    private fun canSend(strength: Strength): Boolean {
        val measured = actual.value ?: return false
        val ceiling = settings()
        return when {
            strength.modeA == 1 -> measured.strengthA < ceiling.maxStrengthA
            strength.modeB == 1 -> measured.strengthB < ceiling.maxStrengthB
            strength.modeA == 2 -> measured.strengthA > 0
            strength.modeB == 2 -> measured.strengthB > 0
            else -> false
        }
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

    override suspend fun output(slice: OutputSlice, fence: OutputFence): WriteResult {
        if (!fence.isCurrent()) return WriteResult.Stale
        if (state.get() != LinkState.Ready) return WriteResult.Uncertain
        if (stream != (fence.epoch to fence.streamGeneration)) {
            stream = fence.epoch to fence.streamGeneration
            renderer = PulseRenderer()
            setActiveBestEffort(true)
        }
        val pending =
            change.value?.let {
                if (it.epoch == fence.epoch && it.generation == fence.streamGeneration) it
                else {
                    change.compareAndSet(it, null)
                    null
                }
            }
        val sent =
            when {
                pending == null -> null
                pending.sentAt == null && !canSend(pending.strength) -> {
                    change.compareAndSet(pending, null)
                    null
                }
                pending.sentAt == null ->
                    pending.copy(sentAt = slice.atMillis).takeIf {
                        change.compareAndSet(pending, it)
                    }
                slice.atMillis - pending.sentAt > 1500 -> {
                    if (change.compareAndSet(pending, null)) actual.value = null
                    null
                }
                else -> null
            }
        val command = sent?.strength ?: Strength()
        val bytes =
            if (actual.value == null) packet(Strength()) else renderer.render(slice, command)
        val denied = AtomicBoolean()
        val submitted =
            try {
                link.write(profile.write, bytes, false) {
                    (fence.isCurrent() &&
                            (sent == null || change.value === sent && canSend(command)))
                        .also { if (!it) denied.set(true) }
                }
                !denied.get()
            } catch (failure: IOException) {
                if (failure is BluetoothWriteFenced || denied.get()) false else throw failure
            }
        if (!submitted) {
            if (sent != null) change.compareAndSet(sent, null)
            DiagnosticReporter.record(DiagnosticKind.WriteFenced)
            return WriteResult.Stale
        }
        return WriteResult.Accepted
    }

    private suspend fun halt(admitted: () -> Boolean): StopEvidence {
        change.value = null
        actual.value = null
        val receipt = next() to CompletableDeferred<Unit>()
        zeroSequence.set(receipt.first)
        stopping.value = receipt
        val denied = AtomicBoolean()
        return try {
            link.writeStop(profile.write, zero(receipt.first), false) {
                admitted().also { if (!it) denied.set(true) }
            }
            if (denied.get()) return StopEvidence.Unconfirmed(UnconfirmedCause.WriteFailed)
            val evidence =
                if (
                    withTimeoutOrNull(1000) {
                        receipt.second.await()
                        true
                    } == true
                )
                    StopEvidence.Acknowledged
                else StopEvidence.Unconfirmed(UnconfirmedCause.NoReply)
            setActiveBestEffort(false)
            evidence
        } catch (_: IOException) {
            StopEvidence.Unconfirmed(UnconfirmedCause.WriteFailed)
        } finally {
            stopping.compareAndSet(receipt, null)
        }
    }

    override suspend fun stop(fence: OutputFence): StopEvidence = halt(fence::isCurrent)

    private fun retire(): LinkState {
        val previous = state.getAndSet(LinkState.Closed)
        if (previous != LinkState.Closed) {
            stopping.value?.second?.completeExceptionally(IOException("Coyote link closed"))
            link.retire()
        }
        return previous
    }

    public fun close() {
        retire()
        reader?.cancel()
    }

    public companion object {
        /**
         * Connect only the acknowledged, selected Coyote. No strength increase is part of setup.
         */
        public suspend fun connect(
            ref: DeviceRef,
            id: ScanResultId,
            connector: BluetoothConnector,
            scope: CoroutineScope,
            settings: () -> DeviceSettings.Coyote,
            lost: () -> Unit,
        ): CoyoteSession {
            val profile = GattProfile.Coyote
            var last: IOException? = null
            repeat(3) {
                var link: BluetoothLink? = null
                var candidate: CoyoteSession? = null
                var written = false
                try {
                    val opened = connector.connect(id, profile, 5.seconds)
                    link = opened
                    if (
                        opened.requestMtu(517) < 23 ||
                            !opened
                                .discover(profile.service)
                                .containsAll(listOf(profile.write, profile.notify))
                    )
                        throw IOException("Coyote profile missing")
                    val events = opened.notifications(profile.notify)
                    val session = CoyoteSession(ref, opened, scope, settings)
                    candidate = session
                    written = true
                    session.reader =
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                events.collect(session::receive)
                            } catch (_: IOException) {
                                DiagnosticReporter.record(
                                    DiagnosticKind.BluetoothNotificationsFailed
                                )
                            } finally {
                                if (session.retire() == LinkState.Ready && isActive) lost()
                            }
                        }
                    val cfg = settings()
                    require(cfg.maxStrengthA in 0..200 && cfg.maxStrengthB in 0..200)
                    val initializing = {
                        scope.isActive && session.state.get() == LinkState.Connecting
                    }
                    if (!initializing()) throw IOException("Coyote setup closed")
                    opened.write(profile.write, limits(cfg.maxStrengthA, cfg.maxStrengthB), false) {
                        initializing()
                    }
                    if (
                        session.halt(initializing) != StopEvidence.Acknowledged ||
                            !session.state.compareAndSet(LinkState.Connecting, LinkState.Ready)
                    )
                        throw IOException("Coyote did not confirm zero strength")
                    return session
                } catch (e: IOException) {
                    candidate?.close() ?: link?.retire()
                    if (written || e is BluetoothSetupWriteFailure) throw e
                    last = e
                } catch (e: Throwable) {
                    candidate?.close() ?: link?.retire()
                    throw e
                }
            }
            throw last ?: IOException("Coyote unavailable")
        }
    }
}
