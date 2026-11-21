// Ported from internal/motion/engine.go and internal/httpapi/stop_admission.go at 1b80d72e.
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
import com.magichandy.mobile.core.ports.RunId
import com.magichandy.mobile.core.ports.RunIntent
import com.magichandy.mobile.core.ports.RunJournal
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.score.TempoScore
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
    val onConfirmed: (() -> Unit)? = null,
    val send: suspend (OutputFence) -> StopEvidence,
) {
    constructor(owner: DeviceTransport) : this(owner.device, owner.stopTimeout, send = owner::stop)
}

internal class StopAttempt(
    val epoch: Epoch,
    val target: StopTarget,
    val pressedAt: Duration,
    val wholeBridge: Boolean = true,
)

/** Captured by the output job before its first write. Pause fences only this output stream. */
internal data class PlaybackStream(val epoch: Epoch, val generation: Long)

internal data class EndedRun(val id: RunId, val at: Instant, val lostDevice: String?)

internal data class EngineState(
    val epoch: Epoch = Epoch(0),
    val owners: Map<DeviceId, DeviceTransport> = emptyMap(),
    val retired: Map<DeviceId, DeviceTransport> = emptyMap(),
    val members: Set<DeviceId> = emptySet(),
    val streamGeneration: Long = 0,
    val pausedPosition: PlaybackPosition? = null,
    val edgeRequested: Boolean = false,
    val beforeTempoHold: TempoLane? = null,
    val score: ScoreRequest? = null,
    val vibration: VibrationLane? = null,
    val tempo: TempoLane? = null,
    val previousTempo: TempoScore? = null,
    val air: AirAdjustment? = null,
    val airBalance: Int = 0,
    val pausedAt: Duration? = null,
    val journal: RunJournal? = null,
    val confirmed: Set<DeviceId> = emptySet(),
    val reconciled: Set<DeviceId> = emptySet(),
    val inFlight: Map<Any, DeviceId> = emptyMap(),
    val work: Map<Job, WorkKind> = emptyMap(),
    val pending: Map<DeviceId, StopAttempt> = emptyMap(),
    val receipt: StopReceipt? = null,
    val evidence: StopReceipt? = null,
    val publishesEvidence: Boolean = false,
    val endedRun: EndedRun? = null,
) {
    fun plays(stream: PlaybackStream): Boolean =
        epoch == stream.epoch &&
            streamGeneration == stream.generation &&
            members.isNotEmpty() &&
            pausedPosition == null

    val canResume: Boolean
        get() =
            pausedPosition != null &&
                members.isNotEmpty() &&
                confirmed.containsAll(members) &&
                pending.isEmpty() &&
                inFlight.isEmpty() &&
                members.none { it in retired }

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
    lostDevice: String? = null,
): EngineState {
    val pending = pending.toMutableMap()
    for ((id, target) in targets) {
        if (
            id !in pending ||
                id in retry ||
                id in reconciled ||
                (!isolated && pending[id]?.wholeBridge == false)
        )
            pending[id] = StopAttempt(next, target, elapsed, !isolated)
    }
    val evidence = nextReceipt(source, targets, isolated, at)
    val previousRecord = record ?: journal
    val roster =
        (previousRecord?.devices.orEmpty() +
                evidence.outcomes
                    .filter { it.outcome != StopOutcome.Confirmed }
                    .map {
                        DeviceRef(it.device, it.name, it.family, it.connection)
                    } +
                targets.values.map { it.device })
            .distinctBy { it.id }
    val recoveryRecord =
        when {
            previousRecord != null -> previousRecord.copy(devices = roster)
            roster.isEmpty() -> null
            else ->
                RunJournal(
                    RunId("stop-$at-${evidence.episodeId}"),
                    roster,
                    RunIntent.Stop,
                    false,
                    at,
                )
        }
    return copy(
        epoch = next,
        endedRun =
            if (members.isNotEmpty() && journal != null) EndedRun(journal.runId, at, lostDevice)
            else endedRun,
        members = emptySet(),
        pausedPosition = null,
        edgeRequested = false,
        beforeTempoHold = null,
        score = null,
        vibration = null,
        previousTempo = tempo?.plan?.score ?: previousTempo,
        tempo = null,
        air = null,
        airBalance = 0,
        pausedAt = null,
        retired =
            if (retire != null && owners[retire.device.id] === retire)
                retired + (retire.device.id to retire)
            else retired,
        journal = recoveryRecord,
        reconciled = reconciled - targets.keys,
        confirmed =
            if (record != null && record.runId != journal?.runId) emptySet()
            else confirmed - targets.keys,
        work = if (isolated) work else emptyMap(),
        pending = pending,
        evidence = evidence,
        publishesEvidence = source != null || record != null && receipt != null,
        receipt = if (source != null || record != null && receipt != null) evidence else receipt,
    )
}

internal fun EngineState.withEdge(expected: Epoch, enabled: Boolean): EngineState =
    if (
        expected == epoch &&
            members.isNotEmpty() &&
            pausedPosition == null &&
            journal?.intended == RunIntent.Start
    )
        copy(edgeRequested = enabled)
    else this

internal fun EngineState.pausing(
    expected: Epoch,
    position: PlaybackPosition,
    elapsed: Duration,
    at: Instant,
): EngineState? {
    if (
        epoch != expected ||
            members.isEmpty() ||
            pausedPosition != null ||
            journal?.intended != RunIntent.Start
    )
        return null
    val targets = owners.filterKeys(members::contains).mapValues { StopTarget(it.value) }
    return copy(
        pausedPosition = position,
        pausedAt = elapsed,
        journal = journal.copy(intended = RunIntent.Paused, at = at),
        streamGeneration = streamGeneration + 1,
        pending = targets.mapValues { StopAttempt(expected, it.value, elapsed, false) },
        confirmed = emptySet(),
        evidence = nextReceipt(null, targets, true, at),
        publishesEvidence = false,
        work = work.filterValues { it != WorkKind.Output },
    )
}

internal fun EngineState.resuming(
    expected: Epoch,
    record: RunJournal,
    now: Duration,
): EngineState? {
    if (
        !canResume ||
            epoch != expected ||
            journal?.runId != record.runId ||
            journal.devices != record.devices
    )
        return null
    val held = pausedAt?.let { now - it } ?: Duration.ZERO
    return copy(
        pausedPosition = null,
        pausedAt = null,
        tempo = tempo?.copy(since = tempo.since + held),
        vibration = vibration?.copy(since = vibration.since + held),
        air = null,
        journal = record,
        confirmed = emptySet(),
        evidence = null,
    )
}

private val receiptSequence = java.util.concurrent.atomic.AtomicLong()

internal fun EngineState.nextReceipt(
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
        receiptSequence.incrementAndGet(),
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
