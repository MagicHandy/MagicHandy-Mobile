// Ported from internal/transport/{cloud_client,handy_bluetooth_transport}.go at 9a4c36cf.
package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface HandyWire {
    val maxPoints: Int
    val stopTimeout: Duration

    suspend fun command(command: HandyCommand, allowed: () -> Boolean): WriteResult

    suspend fun stop(allowed: () -> Boolean): StopEvidence
}

internal class HandyTransport(override val device: DeviceRef, private val wire: HandyWire) :
    DeviceTransport {
    override val stopTimeout
        get() = wire.stopTimeout

    private val motion = Mutex()
    private val retired = MutableStateFlow(false)
    private var stream = 0L
    private var epoch: Epoch? = null
    private var tail = -1L
    private var lastTime = -1L

    override suspend fun write(batch: MotionBatch, fence: OutputFence): WriteResult {
        if (!fence.isCurrent() || retired.value) return WriteResult.Stale
        return motion.withLock {
            if (!fence.isCurrent() || retired.value) return@withLock WriteResult.Stale
            // An owner that timed out cannot resume the old stream on its next frame.
            val result =
                try {
                    sendBatch(batch, fence)
                } catch (cancelled: CancellationException) {
                    retired.value = true
                    throw cancelled
                } catch (_: IOException) {
                    DiagnosticReporter.record(DiagnosticKind.TransportWriteFailed)
                    WriteResult.Uncertain
                }
            if (result == WriteResult.Uncertain) retired.value = true
            result
        }
    }

    private suspend fun sendBatch(batch: MotionBatch, fence: OutputFence): WriteResult {
        var sent = false
        suspend fun failure(command: HandyCommand): WriteResult? {
            val result =
                if (fence.isCurrent()) wire.command(command, fence::isCurrent)
                else WriteResult.Stale
            return when (result) {
                WriteResult.Accepted -> {
                    sent = true
                    null
                }
                WriteResult.Stale -> if (sent) WriteResult.Uncertain else WriteResult.Stale
                WriteResult.Uncertain -> result
            }
        }
        if (batch.isFirst) {
            if (epoch == fence.epoch) return WriteResult.Uncertain
            failure(
                    HandyCommand.Stroke(
                        batch.limits.strokeLow.value.toDouble(),
                        batch.limits.strokeHigh.value.toDouble(),
                    )
                )
                ?.let {
                    return it
                }
            epoch = fence.epoch
            stream = stream % 0xffff_ffffL + 1
            tail = -1
            lastTime = -1
            failure(HandyCommand.Setup(stream))?.let {
                return it
            }
        }
        if (
            epoch != fence.epoch ||
                batch.frames.first().timeMillis <= lastTime ||
                batch.frames.last().timeMillis > 0xffff_ffffL
        )
            return WriteResult.Uncertain
        for (frames in batch.frames.chunked(wire.maxPoints)) {
            val newTail = tail + frames.size
            if (newTail > 0xffff_ffffL) return WriteResult.Uncertain
            failure(HandyCommand.Add(frames, newTail, tail < 0))?.let {
                return it
            }
            tail = newTail
            lastTime = frames.last().timeMillis
        }
        if (batch.isFirst)
            failure(HandyCommand.Play(0))?.let {
                return it
            }
        return WriteResult.Accepted
    }

    override suspend fun stop(fence: OutputFence): StopEvidence = wire.stop(fence::isCurrent)
}
