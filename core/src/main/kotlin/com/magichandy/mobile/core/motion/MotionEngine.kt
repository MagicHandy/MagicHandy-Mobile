// Ported from internal/motion/engine.go and internal/httpapi/stop_admission.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.RunIntent
import com.magichandy.mobile.core.ports.RunJournal
import com.magichandy.mobile.core.ports.WriteResult
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One atomic state transition fences work, closes admission and publishes the initial receipt. */
internal class MotionEngine(
    private val scope: CoroutineScope,
    stopDispatcher: CoroutineDispatcher,
    private val stopIo: CoroutineDispatcher,
    private val clock: Clock,
    private val record: (DiagnosticKind) -> Unit,
) {
    private val current = MutableStateFlow(EngineState())
    private val stops = StopDelivery(scope, stopDispatcher, stopIo, clock, record, current)
    val state = current.asStateFlow()
    val epoch
        get() = current.value.epoch

    fun attach(owner: DeviceTransport) {
        require(owner.stopTimeout.isPositive() && owner.stopTimeout.isFinite())
        current.update { s ->
            require(s.owners[owner.device.id].let { it == null || it === owner })
            require(s.retired[owner.device.id] !== owner) {
                "Reconnect with a fresh transport generation"
            }
            s.copy(
                owners = s.owners + (owner.device.id to owner),
                retired = s.retired - owner.device.id,
            )
        }
    }

    suspend fun detach(owner: DeviceTransport): Boolean {
        while (true) {
            val s = current.value
            if (s.owners[owner.device.id] !== owner) return false
            if (owner.device.id in s.pending) {
                state.first { owner.device.id !in it.pending }
                continue
            }
            if (owner.device.id in s.members) {
                stop(null, expected = s.epoch)
                continue
            }
            if (current.compareAndSet(s, s.copy(owners = s.owners - owner.device.id))) return true
        }
    }

    /**
     * Called only after start prerequisites have succeeded. Exactly the captured owners may write.
     */
    fun admit(expected: Epoch, owners: Collection<DeviceTransport>, record: RunJournal): Boolean {
        val ids = owners.map { it.device.id }.toSet()
        require(record.devices.map { it.id }.toSet() == ids)
        while (true) {
            val s = current.value
            if (
                !s.canStart ||
                    expected != s.epoch ||
                    owners.isEmpty() ||
                    owners.any { s.owners[it.device.id] !== it || s.retired[it.device.id] === it }
            )
                return false
            if (
                current.compareAndSet(
                    s,
                    s.copy(
                        members = ids,
                        journal = record,
                        confirmed = emptySet(),
                        reconciled = emptySet(),
                        receipt = null,
                        evidence = null,
                        publishesEvidence = false,
                    ),
                )
            )
                return true
        }
    }

    fun launchWork(
        expected: Epoch,
        kind: WorkKind,
        dispatcher: CoroutineDispatcher,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY, block = block)
        while (true) {
            val s = current.value
            if (s.epoch != expected) {
                job.cancel()
                return job
            }
            if (current.compareAndSet(s, s.copy(work = s.work + (job to kind)))) break
        }
        job.invokeOnCompletion { current.update { it.copy(work = it.work - job) } }
        job.start()
        return job
    }

    /** Race the transaction's last admission check against Stop's epoch advance. */
    fun commitTurn(expected: Epoch, job: Job): Boolean {
        while (true) {
            val s = current.value
            if (s.epoch != expected || s.work[job] != WorkKind.Turn) return false
            if (current.compareAndSet(s, s.copy(work = s.work - job))) return true
        }
    }

    fun stream(expected: Epoch): PlaybackStream =
        PlaybackStream(expected, current.value.streamGeneration)

    fun updateAutopilotJournal(expected: Epoch, original: RunJournal, next: RunJournal): Boolean {
        while (true) {
            val s = current.value
            if (s.epoch != expected || s.journal != original || s.members.isEmpty()) return false
            if (current.compareAndSet(s, s.copy(journal = next))) return true
        }
    }

    fun setEdge(expected: Epoch, enabled: Boolean) {
        current.update { it.edgeTempo(expected, enabled, clock.elapsed()) }
    }

    fun selectScore(expected: Epoch, record: RunJournal, score: ScoreRequest): Boolean {
        while (true) {
            val s = current.value
            if (
                s.epoch != expected ||
                    s.journal?.runId != record.runId ||
                    s.members.isEmpty() ||
                    s.pausedPosition != null ||
                    s.journal.intended != RunIntent.Start
            )
                return false
            if (current.compareAndSet(s, s.copy(score = score, edgeRequested = false))) return true
        }
    }

    /**
     * Close output admission before independently halting the participating owners. Keep the turn
     * and roster.
     */
    fun pause(expected: Epoch, position: PlaybackPosition): RunJournal? {
        while (true) {
            val s = current.value
            val journal = s.journal ?: return null
            val paused = s.pausing(expected, position, clock.elapsed(), clock.now()) ?: return null
            if (!current.compareAndSet(s, paused)) continue
            paused.pending.values.forEach(stops::send)
            scope.launch(stopIo) {
                s.work.filterValues { it == WorkKind.Output }.keys.forEach { it.cancel() }
            }
            return journal
        }
    }

    /** Reopen the same roster only after the service and journal have admitted a fresh stream. */
    fun resume(expected: Epoch, record: RunJournal): Boolean {
        while (true) {
            val s = current.value
            val next = s.resuming(expected, record, clock.elapsed()) ?: return false
            if (current.compareAndSet(s, next)) return true
        }
    }

    suspend fun write(
        stream: PlaybackStream,
        owner: DeviceTransport,
        batch: MotionBatch,
        prepare: Boolean = false,
    ): WriteResult =
        admittedWrite(stream, owner) { fence ->
            if (prepare)
                owner.startupPosition?.let { reconcileStartup(it, batch, fence) }
                    ?: WriteResult.Accepted
            else owner.write(batch, fence)
        }

    suspend fun startBuffered(
        stream: PlaybackStream,
        owner: DeviceTransport,
        origin: kotlin.time.Duration,
    ): WriteResult =
        admittedWrite(stream, owner) { fence ->
            requireNotNull(owner.linkedStart)
                .play((clock.elapsed() - origin).inWholeMilliseconds.coerceAtLeast(0), fence)
        }

    suspend fun output(
        stream: PlaybackStream,
        owner: com.magichandy.mobile.core.ports.TimedDevice,
        slice: com.magichandy.mobile.core.ports.OutputSlice,
    ): WriteResult = admittedWrite(stream, owner) { fence -> owner.output(slice, fence) }

    fun vibration(
        expected: Epoch,
        envelope: com.magichandy.mobile.core.score.VibrationEnvelope,
    ): Boolean {
        while (true) {
            val s = current.value
            if (s.epoch != expected || s.members.isEmpty() || s.pausedPosition != null) return false
            if (
                current.compareAndSet(
                    s,
                    s.copy(vibration = VibrationLane(envelope, clock.elapsed())),
                )
            )
                return true
        }
    }

    fun tempo(
        expected: Epoch,
        score: com.magichandy.mobile.core.score.TempoScore?,
        steps: Int,
        source: com.magichandy.mobile.core.api.MotionSource,
    ): Boolean {
        require(steps in -2..2)
        while (true) {
            val s = current.value
            val next = s.withTempo(expected, score, steps, source, clock.elapsed()) ?: return false
            if (current.compareAndSet(s, next)) return true
        }
    }

    private suspend fun admittedWrite(
        stream: PlaybackStream,
        owner: DeviceTransport,
        operation: suspend (OutputFence) -> WriteResult,
    ): WriteResult {
        val fence =
            OutputFence(stream.epoch, stream.generation) {
                val s = current.value
                s.plays(stream) &&
                    owner.device.id in s.members &&
                    s.owners[owner.device.id] === owner
            }
        val ticket = Any()
        while (true) {
            val s = current.value
            if (!fence.isCurrent()) return WriteResult.Stale
            if (
                current.compareAndSet(
                    s,
                    s.copy(inFlight = s.inFlight + (ticket to owner.device.id)),
                )
            )
                break
        }
        try {
            if (!fence.isCurrent()) return WriteResult.Stale
            val result =
                try {
                    operation(fence)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    record(DiagnosticKind.TransportWriteFailed)
                    WriteResult.Uncertain
                }
            if (
                result == WriteResult.Uncertain ||
                    (result == WriteResult.Accepted && !fence.isCurrent())
            )
                recover(owner)
            return result
        } catch (cancelled: CancellationException) {
            recover(owner)
            throw cancelled
        } finally {
            current.update { it.copy(inFlight = it.inFlight - ticket) }
        }
    }

    private fun recover(owner: DeviceTransport) {
        stop(
            null,
            targets = { it.owners.values.map(::StopTarget) + StopTarget(owner) },
            forceRetry = setOf(owner.device.id),
            retire = owner,
        )
    }

    fun stop(
        source: StopSource?,
        expected: Epoch? = null,
        targets: (EngineState) -> List<StopTarget> = { it.owners.values.map(::StopTarget) },
        forceRetry: Set<DeviceId> = emptySet(),
        journal: RunJournal? = null,
        retire: DeviceTransport? = null,
        disconnecting: DeviceTransport? = null,
        stream: PlaybackStream? = null,
    ) {
        while (true) {
            val old = current.value
            if (expected != null && old.epoch != expected) return
            if (stream != null && !old.plays(stream)) return
            if (disconnecting != null && old.owners[disconnecting.device.id] !== disconnecting)
                return
            val isolated = disconnecting != null && old.members.isEmpty()
            val next = if (isolated) old.epoch else Epoch(old.epoch.value + 1)
            val selected =
                if (isolated) listOf(StopTarget(requireNotNull(disconnecting))) else targets(old)
            val byId = selected.associateBy { it.device.id }
            val retry = if (old.pausedPosition == null) forceRetry else forceRetry + old.members
            val stopped =
                old.stopped(
                    source,
                    next,
                    byId,
                    retry.intersect(byId.keys),
                    journal,
                    retire,
                    isolated,
                    clock.elapsed(),
                    clock.now(),
                )
            if (!current.compareAndSet(old, stopped)) continue
            stopped.pending.values
                .filter { old.pending[it.target.device.id] !== it }
                .forEach(stops::send)
            if (!isolated && old.work.isNotEmpty())
                scope.launch(stopIo) { old.work.keys.forEach { it.cancel() } }
            return
        }
    }

    fun reconcile(expected: Epoch, id: DeviceId, interrupted: Boolean): Boolean {
        while (true) {
            val old = current.value
            if (expected != old.epoch || id in old.reconciled || old.members.isNotEmpty())
                return false
            val latest = old.evidence?.outcomes?.firstOrNull { it.device == id }
            if (latest?.outcome == StopOutcome.Confirmed) return false
            if (
                !interrupted &&
                    old.receipt?.outcomes.orEmpty().none {
                        it.device == id && it.outcome != StopOutcome.Confirmed
                    }
            )
                return false
            val receipt = old.receipt?.without(id)
            val updated =
                old.copy(
                    reconciled = old.reconciled + id,
                    evidence = old.evidence?.without(id),
                    // A fully reconciled receipt has no rows. An empty receipt would say "No
                    // device".
                    receipt = receipt?.takeUnless { it.outcomes.isEmpty() },
                )
            if (current.compareAndSet(old, updated)) return true
        }
    }

    fun dismissReceipt() {
        current.update { s ->
            if (
                s.pending.keys.all { it in s.reconciled } &&
                    s.receipt?.outcomes.orEmpty().all { it.outcome == StopOutcome.Confirmed }
            )
                s.copy(receipt = null, publishesEvidence = false)
            else s
        }
    }
}
