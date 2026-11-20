// Ported from internal/deviceapp/lifecycle.go and internal/httpapi/playback_devices.go at 9a4c36cf.
package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.BackendNotice
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceOutput
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.InterruptedRun
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.MotionSource
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StartRefusal
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.motion.ManualPlayback
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.RunIntent
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class Devices(
    private val d: CoreDependencies,
    private val engine: MotionEngine,
    private val safety: RunSafety,
    private val ui: MutableStateFlow<AppState>,
    private val record: (DiagnosticKind) -> Unit,
) {
    private val playback = ManualPlayback(d, engine)
    private var lead: DeviceId? = null

    private class Connection(val scope: CoroutineScope) {
        var opening: Job? = null
        var closing = false
    }

    // Accessed only on the serialized session dispatcher, including after a suspended connect.
    private val connections = mutableMapOf<DeviceId, Connection>()

    fun connect(id: DeviceId) {
        val row = ui.value.devices.devices.firstOrNull { it.id == id } ?: return
        if (
            !ui.value.firstRun.hasAcknowledgedAdultsOnly ||
                row.family == DeviceFamily.Coyote && !ui.value.devices.hasAcknowledgedCoyoteNotes
        )
            return
        val existing = connections[id]
        if (existing != null) {
            val state = engine.state.value
            val retired = state.retired[id]
            if (!existing.closing && retired != null && state.owners[id] === retired) {
                d.scope.launch(d.dispatchers.session, start = CoroutineStart.UNDISPATCHED) {
                    disconnect(id)
                    connect(id)
                }
            }
            return
        }
        val context = d.scope.coroutineContext
        val connection =
            Connection(CoroutineScope(context + SupervisorJob(context[Job]) + d.dispatchers.stopIo))
        connections[id] = connection
        edit(id) { it.copy(status = DeviceStatus.Connecting) }
        connection.opening = connection.scope.launch(d.dispatchers.session) { open(id, connection) }
    }

    private suspend fun open(id: DeviceId, connection: Connection) {
        var attached = false
        try {
            val ready =
                withContext(d.dispatchers.io) {
                    try {
                        d.runHost.ensureRunning()
                    } catch (_: IOException) {
                        record(DiagnosticKind.ServiceUnavailable)
                        false
                    }
                }
            if (!ready) {
                refused(StartRefusal.ServiceUnavailable)
                return
            }
            val owner = withContext(d.dispatchers.io) { d.devices.connect(id, connection.scope) }
            if (connection.closing || owner == null || owner.device.id != id) return
            engine.attach(owner)
            attached = true
            edit(id) {
                it.copy(status = DeviceStatus.Connected, membership = RunMembership.NextRun)
            }
            if (lead == null) setLead(id)
            if (
                engine.state.value.receipt?.outcomes.orEmpty().any {
                    it.device == id && it.outcome != StopOutcome.Confirmed
                }
            )
                safety.retryRecovery()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            record(DiagnosticKind.DeviceConnectionFailed)
        } finally {
            if (!attached && !connection.closing) {
                connection.closing = true
                withContext(NonCancellable) { release(id, connection, lost = true) }
            }
        }
    }

    suspend fun disconnect(id: DeviceId, lost: Boolean = false) {
        val connection = connections[id] ?: return
        if (connection.closing) return
        connection.closing = true
        val owner = engine.state.value.owners[id]
        if (owner == null) {
            connection.opening?.cancelAndJoin()
            release(id, connection, lost)
            return
        }
        if (lost && id in engine.state.value.members) {
            // Preserve the target in the Stop fan-out before removing its live handle.
            engine.stop(null)
            ui.update { s ->
                s.copy(
                    banners =
                        listOf(
                            Banner.DeviceLost(
                                id,
                                owner.device.name,
                                owner.device.connection,
                                owner.device.afterLoss,
                            )
                        ) + s.banners.filterNot { it is Banner.DeviceLost && it.device == id }
                )
            }
        } else if (!lost) {
            engine.stop(null, disconnecting = owner)
        }
        engine.detach(owner)
        release(id, connection, lost)
    }

    private suspend fun release(id: DeviceId, connection: Connection, lost: Boolean) {
        try {
            withContext(d.dispatchers.io) { d.devices.disconnect(id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            record(DiagnosticKind.DeviceConnectionFailed)
        } finally {
            connection.scope.cancel()
            connections.remove(id)
        }
        edit(id) {
            it.copy(
                status = if (lost) DeviceStatus.Lost else DeviceStatus.NotConnected,
                output = null,
                membership = RunMembership.NotInRun,
            )
        }
        if (id == lead) setLead(engine.state.value.owners.keys.firstOrNull())
    }

    suspend fun reconnectSaved() {
        val saved = safety.interrupted.value?.devices.orEmpty().map { it.id }
        val unresolved =
            engine.state.value.receipt
                ?.outcomes
                .orEmpty()
                .filter { it.outcome != StopOutcome.Confirmed }
                .map { it.device }
        safety.retryRecovery()
        for (id in (saved + unresolved).distinct()) {
            if (id !in engine.state.value.owners) connect(id)
        }
    }

    fun setLead(id: DeviceId?) {
        if (engine.state.value.members.isNotEmpty()) return
        val row = ui.value.devices.devices.firstOrNull { it.id == id }
        if (id != null && (row == null || id !in engine.state.value.owners)) return
        lead = id
        val tempo = row?.family == DeviceFamily.VacuGlide
        val modes = if (tempo) listOf(MotionMode.Tempo, MotionMode.Off) else strokeModes
        ui.update { s ->
            val selected = d.settings.state.value.values.mode
            s.copy(
                settings =
                    s.settings.copy(
                        availableModes = modes,
                        motionMode = if (selected in modes) selected else modes.first(),
                    )
            )
        }
    }

    fun edit(id: DeviceId, change: (DeviceSummary) -> DeviceSummary) {
        ui.update { s ->
            s.copy(
                devices =
                    s.devices.copy(
                        devices = s.devices.devices.map { if (it.id == id) change(it) else it }
                    )
            )
        }
    }

    suspend fun start(epoch: Epoch, test: DeviceId?) {
        if (!ui.value.firstRun.hasAcknowledgedAdultsOnly) return
        if (ui.value.settings.motionMode == MotionMode.Off && test == null) {
            refused(StartRefusal.MotionModeOff)
            return
        }
        if (engine.state.value.members.isNotEmpty()) {
            ui.update { it.copy(backendNotice = BackendNotice.RunAlreadyActive) }
            return
        }
        val state = ui.value
        val ids =
            state.devices.devices
                .filter {
                    it.status == DeviceStatus.Connected &&
                        if (test != null) it.id == test else it.membership != RunMembership.NotInRun
                }
                .map { it.id }
        val leadId = lead?.takeIf { it in ids } ?: ids.firstOrNull()
        val owners = ids.mapNotNull(engine.state.value.owners::get)
        when (
            val start =
                safety.start(
                    epoch,
                    owners,
                    if (test != null) RunIntent.TestMotor else RunIntent.Start,
                )
        ) {
            is StartDecision.Refused -> refused(start.cause)
            StartDecision.Superseded ->
                if (engine.epoch == epoch && !engine.state.value.canStart)
                    ui.update { it.copy(backendNotice = BackendNotice.ReconcileDevices) }
            StartDecision.Admitted ->
                playback.play(epoch, owners, state.run.limits, test != null) { pace ->
                    showPlaying(epoch, ids, leadId, pace)
                }
        }
    }

    private fun showPlaying(epoch: Epoch, ids: List<DeviceId>, leadId: DeviceId?, pace: Percent) {
        ui.update { state ->
            if (engine.epoch != epoch) return@update state
            val rows =
                state.devices.devices.map { row ->
                    if (row.id !in ids) row
                    else
                        row.copy(
                            status = DeviceStatus.Playing,
                            membership = RunMembership.InRun(row.id == leadId),
                            output = output(row, pace, state),
                        )
                }
            state.copy(
                run =
                    state.run.copy(
                        phase = RunPhase.Playing,
                        source = MotionSource.You,
                        commandedPace = pace,
                    ),
                devices = state.devices.copy(devices = rows),
                banners = state.banners.filterNot { it is Banner.StartRefused },
            )
        }
    }

    private fun output(row: DeviceSummary, pace: Percent, s: AppState): DeviceOutput =
        when (val config = row.settings) {
            is DeviceSettings.Handy,
            DeviceSettings.AutoblowUltra ->
                DeviceOutput.Stroke(pace, null, s.run.limits.strokeLow, s.run.limits.strokeHigh)
            is DeviceSettings.Vibrator ->
                DeviceOutput.Level(
                    Percent(minOf(pace.value, config.maxLevel.value)),
                    config.maxLevel,
                    true,
                )
            is DeviceSettings.Coyote -> DeviceOutput.Channels(0, 0)
            is DeviceSettings.VacuGlide -> DeviceOutput.Tempo(pace)
        }

    fun projectSafety() {
        ui.update { s ->
            val engineState = engine.state.value
            val record = safety.interrupted.value
            val receipt = engineState.receipt
            val interrupted = record?.let { journal ->
                val known =
                    (s.interruptedRun?.outcomes.orEmpty() +
                            engineState.evidence?.outcomes.orEmpty())
                        .associateBy { it.device }
                val outcomes =
                    journal.devices
                        .filterNot { it.id in engineState.reconciled }
                        .mapNotNull { known[it.id] }
                        .sortedWith(
                            compareBy(
                                {
                                    it.outcome == StopOutcome.Confirmed ||
                                        it.family != DeviceFamily.VacuGlide
                                },
                                { it.outcome },
                            )
                        )
                InterruptedRun(journal.at, outcomes, journal.autopilot)
            }
            val updated = s.copy(stopReceipt = receipt, interruptedRun = interrupted)
            if (engineState.members.isNotEmpty()) updated
            else
                updated.copy(
                    run =
                        s.run.copy(
                            phase = RunPhase.Idle,
                            commandedPace = null,
                            source = null,
                            isPlanningHeld = false,
                        ),
                    devices =
                        s.devices.copy(
                            devices =
                                s.devices.devices.map { row ->
                                    if (
                                        row.id in engineState.retired &&
                                            row.id in engineState.owners
                                    )
                                        row.copy(
                                            status = DeviceStatus.Lost,
                                            membership = RunMembership.NotInRun,
                                            output = null,
                                        )
                                    else afterStop(row)
                                }
                        ),
                )
        }
    }

    private fun afterStop(row: DeviceSummary): DeviceSummary =
        when (row.status) {
            DeviceStatus.Playing,
            DeviceStatus.Paused ->
                row.copy(
                    status = DeviceStatus.Connected,
                    membership = RunMembership.NextRun,
                    output = null,
                )
            DeviceStatus.NotConnected,
            DeviceStatus.Connected,
            DeviceStatus.Connecting,
            DeviceStatus.NeedsSetup,
            DeviceStatus.Lost -> row
        }

    fun refused(cause: StartRefusal) {
        ui.update {
            it.copy(
                banners =
                    listOf(Banner.StartRefused(cause)) +
                        it.banners.filterNot { b -> b is Banner.StartRefused }
            )
        }
    }
}
