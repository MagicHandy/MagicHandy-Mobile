package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.StartRefusal
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.motion.MotionEngine
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    init {
        scope.launch(storage) { initialize() }
        scope.launch(storage) {
            engine.state
                .map { s -> s.journal?.runId to (s.resolved - s.inFlight.values.toSet()) }
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

    private fun stopSaved(record: RunJournal) {
        engine.stop(
            null,
            targets = { state ->
                record.devices
                    .filterNot { state.journal?.runId == record.runId && it.id in state.resolved }
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
            store.reconcile(id, confirmed)
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
                if (!state.canStart) return@withLock StartDecision.Superseded
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
    suspend fun retryRecovery() =
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
                if (saved != null && saved.version == 1 && engine.state.value.members.isEmpty())
                    stopSaved(saved)
            }
        }

    fun dismissInterrupted() {
        restored.value = null
    }
}
