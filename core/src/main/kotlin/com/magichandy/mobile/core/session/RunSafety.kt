package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.StartRefusal
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.motion.PlaybackPosition
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
    scope: CoroutineScope,
    private val storage: CoroutineDispatcher,
) {
    private val serial = Mutex()
    private var inspected = false
    private val restored = MutableStateFlow<RunJournal?>(null)
    val interrupted = restored.asStateFlow()
    private val unavailable = MutableStateFlow(false)
    val journalUnavailable = unavailable.asStateFlow()
    private val lostPersistent = MutableStateFlow<Map<DeviceId, DeviceRef>>(emptyMap())

    fun awaitsReturn(id: DeviceId) = id in lostPersistent.value

    fun lost(ref: DeviceRef) {
        if (ref.family == com.magichandy.mobile.core.api.DeviceFamily.VacuGlide)
            lostPersistent.update { it + (ref.id to ref) }
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
                        ref.family == com.magichandy.mobile.core.api.DeviceFamily.VacuGlide &&
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
                    s.journal?.runId to
                        if (s.members.isEmpty())
                            s.resolved - s.inFlight.values.toSet() - (lost.keys - s.reconciled)
                        else emptySet()
                }
                .distinctUntilChanged()
                .collect { (id, confirmed) ->
                    if (id != null && confirmed.isNotEmpty())
                        serial.withLock { persistEvidence(id, confirmed) }
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
        return true
    }

    private fun stopSaved(record: RunJournal, returning: Set<DeviceId> = emptySet()) {
        engine.stop(
            null,
            targets = { state ->
                record.devices
                    .filterNot {
                        it.id !in returning &&
                            state.journal?.runId == record.runId &&
                            it.id in state.resolved
                    }
                    .map { ref ->
                        state.owners[ref.id]?.let(::StopTarget) ?: savedTarget(ref)
                    }
            },
            forceRetry = record.devices.map { it.id }.toSet(),
            journal = record,
        )
    }

    fun emergencyStop(source: StopSource) {
        engine.stop(
            source,
            targets = { state ->
                state.owners.values.map(::StopTarget) +
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
                        }
            },
        )
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

    suspend fun pause(expected: Epoch, position: PlaybackPosition) {
        val record = engine.pause(expected, position) ?: return
        withContext(storage) {
            serial.withLock {
                if (engine.epoch == expected && engine.state.value.pausedPosition != null)
                    transition(record, RunIntent.Paused)
            }
        }
    }

    suspend fun autopilot(expected: Epoch, on: Boolean): Boolean =
        withContext(storage) {
            serial.withLock {
                val current = engine.state.value
                val record = current.journal ?: return@withLock current.epoch == expected
                if (current.members.isEmpty() || current.epoch != expected) return@withLock false
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
                    val retry = returning.keys - state.reconciled
                    stopSaved(saved, retry)
                    lostPersistent.update { it - retry - state.reconciled }
                } else if (returning.isNotEmpty()) {
                    engine.stop(
                        null,
                        expected = state.epoch,
                        targets = { returning.values.map(::savedTarget) },
                        forceRetry = returning.keys,
                    )
                    lostPersistent.update { it - returning.keys }
                }
            }
        }

    fun dismissInterrupted() {
        restored.value = null
    }
}
