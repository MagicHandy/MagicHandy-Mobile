package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.StartRefusal
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.motion.PlaybackPosition
import com.magichandy.mobile.core.motion.PlaybackStream
import com.magichandy.mobile.core.motion.StopTarget
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.RecoveryStops
import com.magichandy.mobile.core.ports.RunHost
import com.magichandy.mobile.core.ports.RunId
import com.magichandy.mobile.core.ports.RunIntent
import com.magichandy.mobile.core.ports.RunJournal
import com.magichandy.mobile.core.ports.RunJournalStore
import com.magichandy.mobile.core.ports.StopEvidence
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface StartDecision {
    data object Admitted : StartDecision

    data object Superseded : StartDecision

    data class Refused(val cause: StartRefusal) : StartDecision
}

/** Serializes journal operations only. The engine's Stop operation never acquires this mutex. */
internal class RunSafety(
    private val engine: MotionEngine,
    private val store: RunJournalStore,
    private val host: RunHost,
    private val recovery: RecoveryStops,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val storage: CoroutineDispatcher,
) {
    private val serial = Mutex()
    @Volatile private var inspected = false
    val inspectionComplete = MutableStateFlow(false)
    private val restored = MutableStateFlow<RunJournal?>(null)
    val interrupted = restored.asStateFlow()
    private val unavailable = MutableStateFlow(false)
    val journalUnavailable = unavailable.asStateFlow()

    internal class ReturnStop(val ref: DeviceRef)

    private val lostPersistent = MutableStateFlow<Map<DeviceId, ReturnStop>>(emptyMap())

    val awaitingReturn = lostPersistent.asStateFlow()

    fun checkedLocally(id: DeviceId) {
        lostPersistent.update { it - id }
    }

    fun awaitsReturn(id: DeviceId) = id in lostPersistent.value

    fun lost(ref: DeviceRef) {
        if (ref.family == DeviceFamily.VacuGlide)
            lostPersistent.update { it + (ref.id to ReturnStop(ref)) }
    }

    init {
        scope.launch(storage) { initialize() }
        scope.launch(storage) {
            combine(engine.state, lostPersistent) { s, lost ->
                    val unresolved =
                        s.evidence
                            ?.outcomes
                            .orEmpty()
                            .filter { it.outcome == StopOutcome.Unconfirmed }
                            .map { it.device }
                            .toSet()
                    s.journal?.devices.orEmpty().filter { ref ->
                        ref.family == DeviceFamily.VacuGlide &&
                            ref.id !in s.reconciled &&
                            (ref.id in lost || ref.id in unresolved)
                    }
                }
                .distinctUntilChanged()
                .collectLatest { missing ->
                    coroutineScope {
                        missing.forEach { ref ->
                            launch {
                                recovery.reachableAgain(ref).collect { retryRecovery(ref.id) }
                            }
                        }
                    }
                }
        }

        scope.launch(storage) {
            combine(engine.state, lostPersistent) { s, lost ->
                    val record = s.journal?.takeIf { s.members.isEmpty() } ?: return@combine null
                    val resolved =
                        s.resolved - s.inFlight.values.toSet() - (lost.keys - s.reconciled)
                    record.runId to (record.devices.map { it.id }.toSet() - resolved)
                }
                .distinctUntilChanged()
                .collect { change ->
                    if (change != null) serial.withLock { syncStopped(change.first) }
                }
        }
    }

    suspend fun initialize() =
        withContext(storage) {
            serial.withLock { inspect() }
        }

    private suspend fun inspect(): Boolean {
        if (inspected) return true
        val record =
            try {
                store.read()
            } catch (_: IOException) {
                DiagnosticReporter.record(DiagnosticKind.JournalUnavailable)
                unavailable.value = true
                return false
            }
        if (record != null && record.version != 1) {
            DiagnosticReporter.record(DiagnosticKind.JournalUnavailable)
            unavailable.value = true
            return false
        }
        if (record != null) {
            restored.value = record
            stopSaved(record)
        }
        unavailable.value = false
        inspected = true
        inspectionComplete.value = true
        return true
    }

    private fun stopSaved(record: RunJournal, returning: Map<DeviceId, ReturnStop> = emptyMap()) {
        engine.stop(
            null,
            targets = { state ->
                (record.devices + returning.values.map { it.ref })
                    .distinctBy { it.id }
                    .filterNot {
                        it.id !in returning &&
                            state.journal?.runId == record.runId &&
                            it.id in state.resolved
                    }
                    .map { ref ->
                        val target = state.owners[ref.id]?.let(::StopTarget) ?: savedTarget(ref)
                        returning[ref.id]?.let { trackReturn(target, it) } ?: target
                    }
            },
            forceRetry = record.devices.map { it.id }.toSet() + returning.keys,
            journal = record,
        )
    }

    private fun trackReturn(target: StopTarget, obligation: ReturnStop): StopTarget =
        target.copy(
            onConfirmed = {
                val id = target.device.id
                lostPersistent.update { lost -> if (lost[id] === obligation) lost - id else lost }
            }
        )

    fun emergencyStop(
        source: StopSource,
        expected: Epoch? = null,
        onFenced: ((Epoch) -> Unit)? = null,
    ) {
        val obligations = lostPersistent.value
        engine.stop(
            source,
            expected = expected,
            forceRetry = obligations.keys,
            onFenced = onFenced,
            targets = { state ->
                state.owners.values.map { owner ->
                    val target = StopTarget(owner)
                    obligations[owner.device.id]?.let { trackReturn(target, it) } ?: target
                } +
                    state.evidence
                        ?.outcomes
                        .orEmpty()
                        .filter {
                            it.device !in state.owners &&
                                it.outcome != StopOutcome.Confirmed &&
                                it.connection in
                                    setOf(ConnectionKind.HandyCloud, ConnectionKind.AutoblowCloud)
                        }
                        .map {
                            savedTarget(DeviceRef(it.device, it.name, it.family, it.connection))
                        } +
                    obligations.values
                        .filter { it.ref.id !in state.owners && it.ref.id !in state.reconciled }
                        .map { savedTarget(it.ref) }
            },
        )
        if (!inspected) scope.launch(storage) { initialize() }
    }

    /**
     * Wait only after the direct Stop has fenced output. Flush recovery evidence before returning.
     */
    suspend fun awaitSettled() {
        initialize()
        val settled = engine.state.first { it.pending.isEmpty() && it.inFlight.isEmpty() }
        val record = settled.journal ?: return
        withContext(storage) { serial.withLock { syncStopped(record.runId) } }
    }

    private fun savedTarget(ref: DeviceRef): StopTarget =
        StopTarget(ref, 5.seconds) { fence ->
            when (ref.connection) {
                ConnectionKind.HandyCloud,
                ConnectionKind.AutoblowCloud -> recovery.stopSavedCloud(ref, fence)
                ConnectionKind.Bluetooth,
                ConnectionKind.IntifaceOnPhone,
                ConnectionKind.IntifaceOnNetwork ->
                    StopEvidence.Unconfirmed(UnconfirmedCause.AppStopped)
            }
        }

    private suspend fun syncStopped(id: RunId) {
        if (!inspect()) return
        val state = engine.state.value
        val record = state.journal?.takeIf { it.runId == id && state.members.isEmpty() } ?: return
        val retained = lostPersistent.value.keys - state.reconciled
        val resolved = state.resolved - state.inFlight.values.toSet() - retained
        val outstanding = record.devices.filterNot { it.id in resolved }
        try {
            if (
                outstanding.isNotEmpty() && !store.retainStops(record.copy(devices = outstanding))
            ) {
                unavailable.value = true
                return
            }
            persistEvidence(id, resolved)
        } catch (_: IOException) {
            DiagnosticReporter.record(DiagnosticKind.JournalUnavailable)
            unavailable.value = true
        }
    }

    suspend fun canForget(device: DeviceId): Boolean =
        withContext(storage) {
            serial.withLock {
                if (!inspect()) return@withLock false
                val s = engine.state.value
                if (device in lostPersistent.value && device !in s.reconciled) return@withLock false
                if (device in s.pending || device in s.inFlight.values) return@withLock false
                val record = store.read()
                record?.devices.orEmpty().none { it.id == device } ||
                    record?.runId == s.journal?.runId && device in s.resolved
            }
        }

    private suspend fun persistEvidence(id: RunId, confirmed: Set<DeviceId>): Boolean =
        try {
            val now = engine.state.value
            if (now.journal?.runId == id && now.members.isEmpty()) {
                val eligible =
                    confirmed.intersect(now.resolved) -
                        now.inFlight.values.toSet() -
                        (lostPersistent.value.keys - now.reconciled)
                if (eligible.isNotEmpty()) store.reconcile(id, eligible)
            }
            unavailable.value = false
            true
        } catch (_: IOException) {
            DiagnosticReporter.record(DiagnosticKind.JournalUnavailable)
            unavailable.value = true
            false
        }

    suspend fun start(
        expected: Epoch,
        owners: List<DeviceTransport>,
        intended: RunIntent,
        autopilot: Boolean = false,
    ): StartDecision =
        withContext(storage) {
            serial.withLock {
                if (!inspect())
                    return@withLock StartDecision.Refused(StartRefusal.JournalUnavailable)
                if (engine.epoch != expected) return@withLock StartDecision.Superseded
                if (owners.isEmpty())
                    return@withLock StartDecision.Refused(StartRefusal.NoDeviceInRun)
                val state = engine.state.value
                if (
                    !state.canStart ||
                        state.journal?.devices.orEmpty().any {
                            awaitsReturn(it.id) && it.id !in state.reconciled
                        }
                )
                    return@withLock StartDecision.Superseded
                if (state.journal != null && !persistEvidence(state.journal.runId, state.resolved))
                    return@withLock StartDecision.Refused(StartRefusal.JournalUnavailable)
                val running =
                    try {
                        host.ensureRunning()
                    } catch (_: IOException) {
                        DiagnosticReporter.record(DiagnosticKind.ServiceUnavailable)
                        false
                    }
                if (!running) return@withLock StartDecision.Refused(StartRefusal.ServiceUnavailable)
                if (engine.epoch != expected) return@withLock StartDecision.Superseded
                val record =
                    RunJournal(
                        RunId(UUID.randomUUID().toString()),
                        owners.map { it.device },
                        intended,
                        autopilot,
                        clock.now(),
                    )
                // Once replacement has begun, finish the admission-or-Stop decision even if the
                // caller disappears.
                withContext(NonCancellable) { arm(expected, owners, record) }
            }
        }

    suspend fun pause(
        expected: Epoch,
        position: PlaybackPosition,
        missedResume: PlaybackStream? = null,
    ) {
        val record = engine.pause(expected, position, missedResume) ?: return
        // A failed resume pauses from the output job that this same fence cancels.
        withContext(NonCancellable + storage) {
            serial.withLock {
                val current = engine.state.value
                if (
                    current.epoch == expected &&
                        current.journal?.runId == record.runId &&
                        current.pausedPosition != null
                )
                    transition(current.journal, RunIntent.Paused)
            }
        }
    }

    suspend fun autopilot(expected: Epoch, on: Boolean): Boolean =
        withContext(storage) {
            serial.withLock {
                val current = engine.state.value
                val record = current.journal ?: return@withLock current.epoch == expected
                if (current.epoch != expected) return@withLock false
                if (current.members.isEmpty()) return@withLock current.canStart
                if (record.autopilot == on) return@withLock true
                val next =
                    transition(record.copy(autopilot = on), record.intended)
                        ?: return@withLock false
                engine.updateAutopilotJournal(expected, record, next)
            }
        }

    suspend fun resume(expected: Epoch): StartDecision =
        withContext(storage) {
            serial.withLock {
                val state = engine.state.value
                val record = state.journal ?: return@withLock StartDecision.Superseded
                if (state.epoch != expected || !state.canResume)
                    return@withLock StartDecision.Superseded
                val ready =
                    try {
                        host.ensureRunning()
                    } catch (_: IOException) {
                        DiagnosticReporter.record(DiagnosticKind.ServiceUnavailable)
                        false
                    }
                if (!ready) return@withLock StartDecision.Refused(StartRefusal.ServiceUnavailable)
                if (engine.epoch != expected) return@withLock StartDecision.Superseded
                val updated =
                    transition(record, RunIntent.Start)
                        ?: return@withLock StartDecision.Refused(StartRefusal.JournalUnavailable)
                if (engine.resume(expected, updated)) StartDecision.Admitted
                else StartDecision.Superseded
            }
        }

    private suspend fun transition(record: RunJournal, intended: RunIntent): RunJournal? {
        val next = record.copy(intended = intended, at = clock.now())
        val saved =
            try {
                store.transition(next)
            } catch (_: IOException) {
                false
            }
        unavailable.value = !saved
        if (!saved) DiagnosticReporter.record(DiagnosticKind.JournalUnavailable)
        return if (saved) next else null
    }

    private suspend fun arm(
        expected: Epoch,
        owners: List<DeviceTransport>,
        record: RunJournal,
    ): StartDecision {
        val armed =
            try {
                store.armIfEmpty(record)
            } catch (_: IOException) {
                false
            }
        if (!armed) {
            DiagnosticReporter.record(DiagnosticKind.JournalUnavailable)
            // An ambiguous storage failure may have installed the record.
            inspected = false
            unavailable.value = true
            return StartDecision.Refused(StartRefusal.JournalUnavailable)
        }
        if (engine.admit(expected, owners, record)) return StartDecision.Admitted
        stopSaved(record)
        return StartDecision.Superseded
    }

    /** Retry only unresolved saved devices when the connection returns, with no start authority. */
    suspend fun retryRecovery(returned: DeviceId? = null) =
        withContext(storage) {
            serial.withLock {
                val saved =
                    try {
                        store.read()
                    } catch (_: IOException) {
                        DiagnosticReporter.record(DiagnosticKind.JournalUnavailable)
                        unavailable.value = true
                        return@withLock
                    }
                val state = engine.state.value
                if (state.members.isNotEmpty()) return@withLock
                val returning =
                    lostPersistent.value.filterKeys { returned == null || it == returned }
                if (saved != null && saved.version == 1) {
                    stopSaved(saved, returning.filterKeys { it !in state.reconciled })
                } else if (returning.isNotEmpty()) {
                    engine.stop(
                        null,
                        expected = state.epoch,
                        targets = { live ->
                            returning.values.map { obligation ->
                                val target =
                                    live.owners[obligation.ref.id]?.let(::StopTarget)
                                        ?: savedTarget(obligation.ref)
                                trackReturn(target, obligation)
                            }
                        },
                        forceRetry = returning.keys,
                    )
                }
            }
        }

    fun dismissInterrupted() {
        restored.value = null
    }
}
