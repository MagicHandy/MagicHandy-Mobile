// Ported from internal/transport/{cloud_client,handy_bluetooth_transport}.go at 1b80d72e.
package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.SliderReading
import com.magichandy.mobile.core.ports.StartupPosition
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

    suspend fun readPosition(allowed: () -> Boolean): SliderReading? = null
}

internal class HandyTransport(
    override val device: DeviceRef,
    private val wire: HandyWire,
    initiallyStopped: Boolean = false,
    private val readMounting: () -> Boolean = { false },
) : DeviceTransport {
    override val checkConnection: suspend () -> Boolean = {
        !retired.value &&
            wire.command(HandyCommand.State) { !retired.value } == WriteResult.Accepted
    }

    override val stopTimeout
        get() = wire.stopTimeout

    private val motion = Mutex()
    private val retired = MutableStateFlow(false)
    private var stream = 0L
    private var epoch: Epoch? = null
    private var streamGeneration = -1L
    private var stroke: HandyCommand.Stroke? = null
    private var reversed = false
    private var tail = -1L
    private var lastTime = -1L
    private val idle = MutableStateFlow(initiallyStopped)
    private var prepared: Pair<Epoch, Long>? = null
    override val linkedStart =
        com.magichandy.mobile.core.ports.LinkedStart { offset, fence ->
            motion.withLock {
                if (
                    !fence.isCurrent() ||
                        retired.value ||
                        epoch != fence.epoch ||
                        streamGeneration != fence.streamGeneration
                )
                    WriteResult.Stale
                else if (offset !in 0..lastTime || offset > Int.MAX_VALUE) WriteResult.Uncertain
                else wire.command(HandyCommand.Play(offset.toInt()), fence::isCurrent)
            }
        }
    override val startupPosition: StartupPosition =
        object : StartupPosition {
            override val reversed
                get() = this@HandyTransport.reversed

            override suspend fun halt(fence: OutputFence): StopEvidence {
                if (!fence.isCurrent())
                    return StopEvidence.Unconfirmed(
                        com.magichandy.mobile.core.api.UnconfirmedCause.WriteFailed
                    )
                val token = fence.epoch to fence.streamGeneration
                if (prepared != token) {
                    this@HandyTransport.reversed = readMounting()
                    prepared = token
                }
                return if (idle.value) StopEvidence.Acknowledged else stop(fence)
            }

            override suspend fun read(fence: OutputFence) = wire.readPosition(fence::isCurrent)

            override suspend fun position(batch: MotionBatch, fence: OutputFence): WriteResult =
                motion.withLock {
                    if (!fence.isCurrent()) return@withLock WriteResult.Stale
                    idle.value = false
                    val writer = BatchWriter(wire, fence)
                    val range =
                        HandyCommand.Stroke(
                            batch.limits.strokeLow.value.toDouble(),
                            batch.limits.strokeHigh.value.toDouble(),
                        )
                    writer.send(range)?.let {
                        return@withLock it
                    }
                    stream = stream % maximumWireValue + 1
                    writer.send(HandyCommand.Setup(stream))?.let {
                        return@withLock it
                    }
                    tail = -1
                    append(writer, batch)?.let {
                        return@withLock it
                    }
                    writer.send(HandyCommand.Play(0)) ?: WriteResult.Accepted
                }
        }

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
        val sameStream = epoch == fence.epoch && streamGeneration == fence.streamGeneration
        val previousTail = if (batch.isFirst) -1L else tail
        if (
            batch.isFirst == sameStream ||
                (!batch.isFirst && batch.frames.first().timeMillis <= lastTime) ||
                batch.frames.last().timeMillis > maximumWireValue ||
                previousTail + batch.frames.size > maximumWireValue
        )
            return WriteResult.Uncertain

        val writer = BatchWriter(wire, fence)
        idle.value = false
        val low = batch.limits.strokeLow.value.toDouble()
        val high = batch.limits.strokeHigh.value.toDouble()
        if (batch.isFirst || stroke?.low != low || stroke?.high != high) {
            val range = HandyCommand.Stroke(low, high)
            writer.send(range)?.let {
                return it
            }
            stroke = range
        }
        if (batch.isFirst) {
            stream = stream % maximumWireValue + 1
            writer.send(HandyCommand.Setup(stream))?.let {
                return it
            }
            epoch = fence.epoch
            streamGeneration = fence.streamGeneration
            if (prepared != (fence.epoch to fence.streamGeneration)) reversed = readMounting()
            tail = -1
        }
        append(writer, batch)?.let {
            return it
        }
        if (batch.isFirst && !batch.deferPlay)
            writer.send(HandyCommand.Play(0))?.let {
                return it
            }
        return WriteResult.Accepted
    }

    private suspend fun append(writer: BatchWriter, batch: MotionBatch): WriteResult? {
        for (offset in batch.frames.indices step wire.maxPoints) {
            val slice =
                batch.frames.subList(offset, minOf(batch.frames.size, offset + wire.maxPoints))
            val frames =
                if (reversed) slice.map { it.copy(position = Percent(100 - it.position.value)) }
                else slice
            val newTail = tail + frames.size
            writer.send(HandyCommand.Add(frames, newTail, tail < 0))?.let {
                return it
            }
            tail = newTail
            lastTime = frames.last().timeMillis
        }
        return null
    }

    override suspend fun stop(fence: OutputFence): StopEvidence =
        wire.stop(fence::isCurrent).also { idle.value = it == StopEvidence.Acknowledged }
}

private const val maximumWireValue = 0xffff_ffffL

/** Fence every command in a batch. A stale suffix after a submitted mutation requires recovery. */
private class BatchWriter(private val wire: HandyWire, private val fence: OutputFence) {
    private var sent = false

    suspend fun send(command: HandyCommand): WriteResult? {
        val result =
            if (fence.isCurrent()) wire.command(command, fence::isCurrent) else WriteResult.Stale
        return when (result) {
            WriteResult.Accepted -> {
                sent = true
                null
            }
            WriteResult.Stale -> if (sent) WriteResult.Uncertain else WriteResult.Stale
            WriteResult.Uncertain -> result
        }
    }
}
