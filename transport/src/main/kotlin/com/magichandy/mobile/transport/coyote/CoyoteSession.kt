// Ported from internal/transport/coyote.go and internal/estim/strength.go at 1b80d72e.
package com.magichandy.mobile.transport.coyote

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.transport.ble.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

public class CoyoteSession
private constructor(
    override val device: DeviceRef,
    private val link: BluetoothLink,
    private val scope: CoroutineScope,
    private val settings: () -> DeviceSettings.Coyote,
) : TimedDevice, CoyoteControls {
    override val interval: kotlin.time.Duration = 100.milliseconds
    override val stopTimeout: kotlin.time.Duration = 1500.milliseconds
    private val profile = GattProfile.Coyote
    private val actual = MutableStateFlow<DeviceOutput.Channels?>(null)
    override val reported: StateFlow<DeviceOutput.Channels?> = actual.asStateFlow()
    override val feedback: StateFlow<DeviceOutput?>
        get() = reported

    private val stopping = MutableStateFlow<Pair<Int, CompletableDeferred<Unit>>?>(null)

    private data class Step(
        val strength: Strength,
        val epoch: Epoch,
        val generation: Long,
        val sentAt: Long? = null,
    )

    private val change = MutableStateFlow<Step?>(null)
    private val zeroSequence = java.util.concurrent.atomic.AtomicInteger()
    private val sequence = java.util.concurrent.atomic.AtomicInteger()
    private val closed = AtomicBoolean()
    private var stream: Pair<Epoch, Long>? = null
    private var renderer = PulseRenderer()

    override val checkConnection: suspend () -> Boolean = {
        !closed.get() &&
            link.discover(profile.service).containsAll(listOf(profile.write, profile.notify))
    }

    private fun next() = sequence.updateAndGet { it % 15 + 1 }

    private fun receive(bytes: ByteArray) {
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
        if (stopping.value != null || closed.get()) return false
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

    override suspend fun output(slice: OutputSlice, fence: OutputFence): WriteResult {
        if (!fence.isCurrent()) return WriteResult.Stale
        if (stream != (fence.epoch to fence.streamGeneration)) {
            stream = fence.epoch to fence.streamGeneration
            renderer = PulseRenderer()
            link.setActive(true)
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
        link.write(profile.write, renderer.render(slice, command), false) {
            fence.isCurrent() && (sent == null || change.value === sent && canSend(command))
        }
        return WriteResult.Accepted
    }

    private suspend fun halt(admitted: () -> Boolean, wait: Boolean): StopEvidence {
        change.value = null
        actual.value = null
        val receipt = next() to CompletableDeferred<Unit>()
        zeroSequence.set(receipt.first)
        stopping.value = receipt
        return try {
            link.writeStop(profile.write, zero(receipt.first), false, admitted)
            link.setActive(false)
            if (
                wait &&
                    withTimeoutOrNull(1000) {
                        receipt.second.await()
                        true
                    } == true
            )
                StopEvidence.Acknowledged
            else StopEvidence.Unconfirmed(UnconfirmedCause.NoReply)
        } catch (_: IOException) {
            StopEvidence.Unconfirmed(UnconfirmedCause.WriteFailed)
        } finally {
            stopping.compareAndSet(receipt, null)
        }
    }

    override suspend fun stop(fence: OutputFence): StopEvidence = halt(fence::isCurrent, true)

    public fun close() {
        closed.set(true)
        link.retire()
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
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            events.collect(session::receive)
                        } catch (_: IOException) {
                            DiagnosticReporter.record(DiagnosticKind.BluetoothNotificationsFailed)
                        } finally {
                            if (isActive && !session.closed.get()) lost()
                        }
                    }
                    val cfg = settings()
                    require(cfg.maxStrengthA in 0..200 && cfg.maxStrengthB in 0..200)
                    written = true
                    opened.write(profile.write, limits(cfg.maxStrengthA, cfg.maxStrengthB), false) {
                        scope.isActive
                    }
                    if (
                        session.halt({ scope.isActive }, false) ==
                            StopEvidence.Unconfirmed(UnconfirmedCause.WriteFailed)
                    )
                        throw IOException("Coyote zero not submitted")
                    return session
                } catch (e: IOException) {
                    link?.retire()
                    if (written || e is BluetoothSetupWriteFailure) throw e
                    last = e
                } catch (e: Throwable) {
                    link?.retire()
                    throw e
                }
            }
            throw last ?: IOException("Coyote unavailable")
        }
    }
}
