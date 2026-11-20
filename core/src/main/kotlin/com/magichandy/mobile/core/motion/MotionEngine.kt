// Ported from internal/motion/engine.go and internal/httpapi/stop_admission.go at 9a4c36cf.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.RunJournal
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One atomic state transition fences work, closes admission and publishes the initial receipt. */
internal class MotionEngine(
    private val scope: CoroutineScope,
    private val stopDispatcher: CoroutineDispatcher,
    private val stopIo: CoroutineDispatcher,
    private val clock: Clock,
    private val record: (DiagnosticKind) -> Unit,
) {
    private val current = MutableStateFlow(EngineState())
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

    suspend fun write(expected: Epoch, owner: DeviceTransport, batch: MotionBatch): WriteResult {
        val fence =
            OutputFence(expected) {
                val s = current.value
                s.epoch == expected &&
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
                    owner.write(batch, fence)
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
    ) {
        while (true) {
            val old = current.value
            if (expected != null && old.epoch != expected) return
            if (disconnecting != null && old.owners[disconnecting.device.id] !== disconnecting)
                return
            val isolated = disconnecting != null && old.members.isEmpty()
            val next = if (isolated) old.epoch else Epoch(old.epoch.value + 1)
            val selected =
                if (isolated) listOf(StopTarget(requireNotNull(disconnecting))) else targets(old)
            val byId = selected.associateBy { it.device.id }
            val stopped =
                old.stopped(
                    source,
                    next,
                    byId,
                    forceRetry.intersect(byId.keys),
                    journal,
                    retire,
                    isolated,
                    clock.elapsed(),
                    clock.now(),
                )
            if (!current.compareAndSet(old, stopped)) continue
            stopped.pending.values
                .filter { old.pending[it.target.device.id] !== it }
                .forEach(::sendStop)
            if (!isolated && old.work.isNotEmpty())
                scope.launch(stopIo) { old.work.keys.forEach { it.cancel() } }
            return
        }
    }

    private fun sendStop(attempt: StopAttempt) {
        val id = attempt.target.device.id
        val fence = OutputFence(attempt.epoch) { current.value.pending[id] === attempt }
        val timeout = attempt.target.timeout
        val worker =
            scope.launch(stopIo, start = CoroutineStart.LAZY) {
                if (!fence.isCurrent()) return@launch
                val evidence =
                    try {
                        attempt.target.send(fence)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        record(DiagnosticKind.TransportStopFailed)
                        StopEvidence.Unconfirmed(UnconfirmedCause.WriteFailed)
                    }
                // Keep the absolute deadline live until an older submitted write is settled.
                if (evidence == StopEvidence.Acknowledged && id in current.value.inFlight.values) {
                    state.first { it.pending[id] !== attempt || id !in it.inFlight.values }
                }
                finish(attempt, evidence)
            }
        // A non-cooperative write cannot keep its receipt pending or stall the independent Stop
        // lane.
        val timer =
            scope.launch(stopDispatcher) {
                delay(
                    (timeout - (clock.elapsed() - attempt.pressedAt)).coerceAtLeast(Duration.ZERO)
                )
                finish(attempt, StopEvidence.Unconfirmed(UnconfirmedCause.NoReply))
                worker.cancel()
            }
        worker.invokeOnCompletion { cause ->
            if (cause != null)
                finish(attempt, StopEvidence.Unconfirmed(UnconfirmedCause.WriteFailed))
            timer.cancel()
        }
        worker.start()
    }

    private fun finish(attempt: StopAttempt, evidence: StopEvidence) {
        val ref = attempt.target.device
        val elapsed = clock.elapsed() - attempt.pressedAt
        val outcome =
            if (elapsed >= attempt.target.timeout)
                ref.outcome(StopOutcome.Unconfirmed, cause = UnconfirmedCause.NoReply)
            else
                when (evidence) {
                    is StopEvidence.Unconfirmed ->
                        ref.outcome(StopOutcome.Unconfirmed, cause = evidence.cause)
                    StopEvidence.Acknowledged -> ref.outcome(StopOutcome.Confirmed, elapsed)
                }
        current.update { s ->
            val evidence = s.evidence
            if (s.pending[ref.id] !== attempt || evidence == null) return@update s
            if (ref.id in s.reconciled) return@update s.copy(pending = s.pending - ref.id)
            val updated = evidence.withOutcome(outcome)
            val publish = s.publishesEvidence || outcome.outcome == StopOutcome.Unconfirmed
            s.copy(
                pending = s.pending - ref.id,
                confirmed =
                    if (outcome.outcome == StopOutcome.Confirmed) s.confirmed + ref.id
                    else s.confirmed,
                evidence = updated,
                publishesEvidence = publish,
                receipt =
                    if (publish) updated
                    else
                        s.receipt?.let { visible ->
                            if (
                                visible.outcomes.any {
                                    it.device == ref.id && it.outcome != StopOutcome.Confirmed
                                }
                            )
                                visible.withOutcome(outcome)
                            else visible
                        },
            )
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
