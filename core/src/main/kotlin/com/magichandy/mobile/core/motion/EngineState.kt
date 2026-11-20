// Ported from internal/motion/engine.go and internal/httpapi/stop_admission.go at 9a4c36cf.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStopOutcome
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopReceipt
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.RunJournal
import com.magichandy.mobile.core.ports.StopEvidence
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.Job

internal enum class WorkKind {
    Turn,
    Planner,
    Output,
}

internal data class StopTarget(
    val device: DeviceRef,
    val timeout: Duration,
    val send: suspend (OutputFence) -> StopEvidence,
) {
    constructor(owner: DeviceTransport) : this(owner.device, owner.stopTimeout, owner::stop)
}

internal class StopAttempt(val epoch: Epoch, val target: StopTarget, val pressedAt: Duration)

internal data class EngineState(
    val epoch: Epoch = Epoch(0),
    val owners: Map<DeviceId, DeviceTransport> = emptyMap(),
    val retired: Map<DeviceId, DeviceTransport> = emptyMap(),
    val members: Set<DeviceId> = emptySet(),
    val journal: RunJournal? = null,
    val confirmed: Set<DeviceId> = emptySet(),
    val reconciled: Set<DeviceId> = emptySet(),
    val inFlight: Map<Any, DeviceId> = emptyMap(),
    val work: Map<Job, WorkKind> = emptyMap(),
    val pending: Map<DeviceId, StopAttempt> = emptyMap(),
    val receipt: StopReceipt? = null,
    val evidence: StopReceipt? = null,
    val publishesEvidence: Boolean = false,
) {
    val resolved: Set<DeviceId>
        get() = confirmed + reconciled

    val canStart: Boolean
        get() =
            members.isEmpty() &&
                inFlight.isEmpty() &&
                journal?.devices.orEmpty().all { it.id in resolved } &&
                pending.isEmpty() &&
                receipt?.outcomes.orEmpty().none { it.outcome != StopOutcome.Confirmed }
}

internal fun EngineState.stopped(
    source: StopSource?,
    next: Epoch,
    targets: Map<DeviceId, StopTarget>,
    retry: Set<DeviceId>,
    record: RunJournal?,
    retire: DeviceTransport?,
    isolated: Boolean,
    elapsed: Duration,
    at: Instant,
): EngineState {
    val pending = pending.toMutableMap()
    for ((id, target) in targets) {
        if (id !in pending || id in retry || id in reconciled)
            pending[id] = StopAttempt(next, target, elapsed)
    }
    val evidence = nextReceipt(source, targets, isolated, at)
    return copy(
        epoch = next,
        members = emptySet(),
        retired =
            if (retire != null && owners[retire.device.id] === retire)
                retired + (retire.device.id to retire)
            else retired,
        journal = record ?: journal,
        reconciled = reconciled - targets.keys,
        confirmed =
            if (record != null && record.runId != journal?.runId) emptySet() else confirmed - retry,
        work = if (isolated) work else emptyMap(),
        pending = pending,
        evidence = evidence,
        publishesEvidence = source != null,
        receipt = if (source != null) evidence else receipt,
    )
}

private fun EngineState.nextReceipt(
    source: StopSource?,
    targets: Map<DeviceId, StopTarget>,
    isolated: Boolean,
    at: Instant,
): StopReceipt {
    val outcomes =
        evidence
            ?.outcomes
            .orEmpty()
            .filter { it.outcome != StopOutcome.Confirmed }
            .associateBy { it.device }
            .toMutableMap()
    for ((id, target) in targets) {
        if (outcomes[id]?.outcome != StopOutcome.Unconfirmed)
            outcomes[id] = target.device.outcome(StopOutcome.Pending)
    }
    return StopReceipt(
        (receipt?.pressCount ?: 0) + if (source != null) 1 else 0,
        at,
        outcomes.values.sortedBy { it.outcome },
        (!isolated && WorkKind.Planner in work.values) || receipt?.didTurnOffAutopilot == true,
        (!isolated && WorkKind.Turn in work.values) || receipt?.didCancelReply == true,
    )
}

internal fun StopReceipt.without(id: DeviceId) =
    copy(outcomes = outcomes.filterNot { it.device == id })

internal fun StopReceipt.withOutcome(outcome: DeviceStopOutcome): StopReceipt =
    copy(
        outcomes =
            outcomes
                .map { if (it.device == outcome.device) outcome else it }
                .sortedBy { it.outcome }
    )

internal fun DeviceRef.outcome(
    status: StopOutcome,
    elapsed: Duration? = null,
    cause: UnconfirmedCause? = null,
) = DeviceStopOutcome(id, name, family, connection, status, elapsed, cause, afterLoss)
