// Ported from internal/transport/intiface_motion.go at 1b80d72e.
package com.magichandy.mobile.transport.intiface

import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.ports.BridgeSelectionRequired
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.LinearPoint
import com.magichandy.mobile.core.ports.LinearSampling
import com.magichandy.mobile.core.ports.LinkedStart
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.SavedDevice
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Delivers the engine's neutral endpoints to exactly one selected v3 LinearCmd feature. */
public class IntifaceLinear
private constructor(
    override val device: DeviceRef,
    private val lease: BridgeLease,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val limits: () -> Limits,
    private val reversed: () -> Boolean,
) : DeviceTransport {
    private val feature = lease.feature
    override val linearSampling: LinearSampling =
        LinearSampling(
            feature.gap.toLong(),
            feature.steps,
            feature.preferredMillis,
        )
    override val stopTimeout: Duration = 2.seconds
    private val wire = lease.socket.wire
    private var run: Run? = null

    private class Starved : IOException("Linear playback starved")

    private data class Segment(val start: Long, val end: Long, val value: Double)

    private class Run(
        val generation: Long,
        val fence: OutputFence,
        val reversed: Boolean,
        val scope: CoroutineScope,
    ) {
        val queue = ArrayDeque<Segment>()
        var anchor: LinearPoint? = null
        var tail: LinearPoint? = null
        var pending = 0
        var origin: Duration? = null
        var anchored = false
        var covering = 0L
    }

    init {
        lease.onInvalidate = {
            run?.scope?.cancel()
            run = null
        }
    }

    override val checkConnection: suspend () -> Boolean = {
        lease.socket
            .features()
            .singleOrNull { it.choice.identity == feature.choice.identity }
            ?.index == feature.index
    }
    override val linkedStart: LinkedStart = LinkedStart { offset, fence ->
        synchronized(wire) {
            val r = run
            if (!fence.isCurrent()) WriteResult.Stale
            else if (r == null || !r.anchored || !current(r)) WriteResult.Uncertain
            else {
                start(r, offset)
                WriteResult.Accepted
            }
        }
    }

    private fun current(r: Run) = run === r && lease.current(r.generation) && r.fence.isCurrent()

    override suspend fun write(batch: MotionBatch, fence: OutputFence): WriteResult {
        val r =
            synchronized(wire) {
                if (!fence.isCurrent()) return WriteResult.Stale
                if (!lease.session.usable(lease))
                    return if (fence.isCurrent()) WriteResult.Uncertain else WriteResult.Stale
                if (batch.isFirst) {
                    if (run != null) return WriteResult.Uncertain
                    run =
                        Run(
                            lease.generation,
                            fence,
                            reversed(),
                            CoroutineScope(
                                scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])
                            ),
                        )
                }
                val active = run ?: return WriteResult.Stale
                if (
                    !current(active) ||
                        active.fence.epoch != fence.epoch ||
                        active.fence.streamGeneration != fence.streamGeneration
                ) {
                    return if (fence.isCurrent()) WriteResult.Uncertain else WriteResult.Stale
                }
                active
            }
        return try {
            synchronized(wire) {
                if (!current(r)) {
                    if (batch.isFirst) discardUnsent(r)
                    return if (fence.isCurrent()) WriteResult.Uncertain else WriteResult.Stale
                }
                append(r, batch.linearPoints, r.reversed)
            }
            if (batch.isFirst) anchor(r, batch.linearPoints.first(), batch.deferPlay)
            WriteResult.Accepted
        } catch (_: FencedBridgeRequest) {
            if (!fence.isCurrent()) {
                synchronized(wire) { if (batch.isFirst) discardUnsent(r) }
                DiagnosticReporter.record(DiagnosticKind.WriteFenced)
                WriteResult.Stale
            } else {
                fail(r)
                WriteResult.Uncertain
            }
        } catch (_: IOException) {
            fail(r)
            WriteResult.Uncertain
        }
    }

    private fun discardUnsent(r: Run) {
        if (run === r) {
            r.scope.cancel()
            run = null
        }
    }

    private fun append(r: Run, points: List<LinearPoint>, reverse: Boolean) {
        if (
            points.isEmpty() && r.tail == null ||
                r.queue.size + points.size - (if (r.tail == null) 1 else 0) > 64
        )
            throw IOException("Linear queue bounds exceeded")
        for (p in points) {
            val next = if (reverse) p.copy(position = 100 - p.position) else p
            if (r.anchor == null) r.anchor = next
            r.tail?.let { prev ->
                if (
                    next.timeMillis - prev.timeMillis !in linearSampling.intervalMillis..0xffffffffL
                )
                    throw IOException("Invalid linear point interval")
                r.queue.addLast(Segment(prev.timeMillis, next.timeMillis, next.position))
            }
            r.tail = next
        }
    }

    private suspend fun anchor(r: Run, first: LinearPoint, deferred: Boolean) {
        val duration = maxOf(250L, feature.gap.toLong())
        val (sent, ticket) =
            synchronized(wire) {
                // First-point direction was captured by append along with the later endpoints.
                val p = requireNotNull(r.anchor)
                val accepted = move(r, p.position, duration)
                clock.elapsed() to accepted
            }
        ticket.await(650)
        delay((sent + duration.milliseconds - clock.elapsed()).coerceAtLeast(Duration.ZERO))
        synchronized(wire) {
            if (!current(r)) throw IOException("Linear anchor superseded")
            r.anchored = true
            if (!deferred) start(r, first.timeMillis)
        }
    }

    private fun start(r: Run, offset: Long) {
        if (r.origin != null) return
        r.origin = clock.elapsed() - offset.milliseconds
        r.scope.launch { pace(r) }
    }

    private fun move(r: Run, value: Double, millis: Long): ButtplugSocket.Ticket {
        val window = limits()
        val physical =
            (window.strokeLow.value +
                value * (window.strokeHigh.value - window.strokeLow.value) / 100.0) / 100.0
        val data = buildJsonObject {
            put("DeviceIndex", feature.index)
            putJsonArray("Vectors") {
                addJsonObject {
                    put("Index", feature.choice.actuatorIndex)
                    put("Duration", millis)
                    put("Position", physical)
                }
            }
        }
        return lease.socket.begin("LinearCmd", data = data, allowed = { current(r) })
    }

    private suspend fun pace(r: Run) {
        try {
            while (true) {
                val deadline =
                    synchronized(wire) {
                        if (!current(r)) return
                        val origin = requireNotNull(r.origin)
                        val elapsed = clock.elapsed() - origin
                        while (
                            r.queue.firstOrNull()?.let { it.end.milliseconds <= elapsed } == true
                        ) r.queue.removeFirst()
                        val s = r.queue.firstOrNull()
                        when {
                            s == null && elapsed < r.covering.milliseconds ->
                                origin + r.covering.milliseconds
                            s == null -> throw Starved()
                            s.start.milliseconds > elapsed -> origin + s.start.milliseconds
                            else -> {
                                sendSegment(r, s, elapsed)
                                r.covering = s.end
                                r.queue.removeFirst()
                                origin + s.end.milliseconds
                            }
                        }
                    }
                // Preparing and dispatching a request consumes part of its remaining interval.
                // A later append starts at or beyond this segment's end and cannot move it earlier.
                delay(deadline - clock.elapsed())
            }
        } catch (_: FencedBridgeRequest) {
            if (!r.fence.isCurrent()) DiagnosticReporter.record(DiagnosticKind.WriteFenced)
            else fail(r, DiagnosticKind.TransportWriteFailed)
        } catch (failure: IOException) {
            fail(
                r,
                if (failure is Starved) DiagnosticKind.IntifaceStarved
                else DiagnosticKind.TransportWriteFailed,
            )
        }
    }

    private fun sendSegment(r: Run, s: Segment, at: Duration) {
        val late = at - s.start.milliseconds
        val duration = s.end - s.start
        // The bridge accepts whole milliseconds. Round the remaining time up at the wire boundary.
        val remaining = duration - late.inWholeMilliseconds
        if (late > 100.milliseconds || late > duration.milliseconds / 4 || remaining < feature.gap)
            throw Starved()
        if (r.pending >= 8) throw IOException("Linear acknowledgement window full")
        val ticket = move(r, s.value, remaining)
        r.pending++
        r.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                ticket.await(650)
            } catch (_: IOException) {
                fail(r)
            } finally {
                synchronized(wire) { r.pending-- }
            }
        }
    }

    private suspend fun fail(r: Run, kind: DiagnosticKind = DiagnosticKind.TransportWriteFailed) {
        synchronized(wire) {
            if (!current(r)) return
            lease.invalidate()
        }
        withContext(NonCancellable) {
            DiagnosticReporter.record(kind)
            try {
                lease.stopDevice()
            } catch (_: IOException) {
                DiagnosticReporter.record(DiagnosticKind.TransportStopFailed)
            } finally {
                lease.lost()
            }
        }
    }

    override suspend fun stop(fence: OutputFence): StopEvidence {
        return try {
            val sent =
                if (fence.wholeBridge) lease.session.stopAll(fence.epoch, fence::isCurrent)
                else {
                    lease.invalidate()
                    lease.stopDevice(fence::isCurrent)
                    true
                }
            StopEvidence.Unconfirmed(
                if (sent) UnconfirmedCause.BridgeOnly else UnconfirmedCause.WriteFailed
            )
        } catch (_: IOException) {
            StopEvidence.Unconfirmed(UnconfirmedCause.WriteFailed)
        }
    }

    public suspend fun close() {
        lease.close()
    }

    public companion object {
        public suspend fun connect(
            hub: IntifaceHub,
            row: SavedDevice,
            scope: CoroutineScope,
            clock: Clock,
            limits: () -> Limits,
            reversed: () -> Boolean,
            lost: () -> Unit,
        ): IntifaceLinear {
            if (row.ref.family !in setOf(DeviceFamily.Handy, DeviceFamily.LinearActuator))
                throw BridgeSelectionRequired()
            val lease =
                hub.claim(
                    row.address ?: throw BridgeSelectionRequired(),
                    row.bridgeIdentity ?: throw BridgeSelectionRequired(),
                    row.ref.family,
                    lost,
                )
            return IntifaceLinear(row.ref, lease, scope, clock, limits, reversed)
        }
    }
}
