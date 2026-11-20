// Ported from internal/deviceapp/lifecycle.go and internal/httpapi/playback_devices.go at 1b80d72e.
package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceOutput
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.StartRefusal
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.ports.DiagnosticKind
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
    val playback = DevicePlayback(d, engine, safety, ui, { lead }, ::refused)
    private var lead: DeviceId? = null

    private class Connection(val scope: CoroutineScope) {
        var checking: Job? = null
        var opening: Job? = null
        var closing = false
    }

    // Accessed only on the serialized session dispatcher, including after a suspended connect.
    private val connections = mutableMapOf<DeviceId, Connection>()

    fun check(id: DeviceId) {
        val owner = engine.state.value.owners[id] ?: return connect(id)
        val connection = connections[id] ?: return
        if (connection.closing || connection.checking?.isActive == true) return
        val read = owner.checkConnection ?: return
        connection.checking =
            connection.scope.launch(d.dispatchers.session) {
                val reachable =
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(5000) {
                            withContext(d.dispatchers.io) { read() }
                        } == true
                    } catch (_: IOException) {
                        false
                    } catch (_: SecurityException) {
                        false
                    }
                if (!reachable && !connection.closing && engine.state.value.owners[id] === owner)
                    d.scope.launch(d.dispatchers.session) {
                        if (engine.state.value.owners[id] === owner) lose(listOf(id))
                    }
            }
    }

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
            owner.feedback?.let { flow ->
                connection.scope.launch(d.dispatchers.session) {
                    flow.collect { value ->
                        if (
                            value != null &&
                                engine.state.value.owners[id] === owner &&
                                id in engine.state.value.members
                        )
                            ui.update { s ->
                                val pace = (value as? DeviceOutput.Tempo)?.tempo
                                s.copy(
                                    run =
                                        if (id == lead && pace != null)
                                            s.run.copy(
                                                commandedPace = pace,
                                                source =
                                                    engine.state.value.tempo?.source
                                                        ?: s.run.source,
                                            )
                                        else s.run,
                                    devices =
                                        s.devices.copy(
                                            devices =
                                                s.devices.devices.map { row ->
                                                    if (row.id == id) row.copy(output = value)
                                                    else row
                                                }
                                        ),
                                )
                            }
                    }
                }
            }
            edit(id) {
                it.copy(status = DeviceStatus.Connected, membership = RunMembership.NextRun)
            }
            if (lead == null) setLead(id)
            if (
                safety.awaitsReturn(id) ||
                    engine.state.value.receipt?.outcomes.orEmpty().any {
                        it.device == id && it.outcome != StopOutcome.Confirmed
                    }
            )
                safety.retryRecovery(id)
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

    suspend fun lose(ids: List<DeviceId>) {
        val state = engine.state.value
        val participants = ids.filter { it in state.members }.mapNotNull(state.owners::get)
        if (participants.isNotEmpty()) {
            participants.forEach { safety.lost(it.device) }
            engine.stop(null, expected = state.epoch)
            ui.update { s ->
                s.copy(
                    banners =
                        participants.map { owner ->
                            val ref = owner.device
                            Banner.DeviceLost(ref.id, ref.name, ref.connection, ref.afterLoss)
                        } + s.banners.filterNot { it is Banner.DeviceLost && it.device in ids }
                )
            }
        }
        for (id in ids) disconnect(id, lost = true)
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
            safety.lost(owner.device)
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

    suspend fun setReversed(id: DeviceId, isReversed: Boolean) {
        val settings =
            ui.value.devices.devices.firstOrNull { it.id == id }?.settings as? DeviceSettings.Handy
                ?: return
        if (settings.isReversed == isReversed) return
        if (d.devices.setHandyReversed(id, isReversed))
            edit(id) { it.copy(settings = settings.copy(isReversed = isReversed)) }
    }

    fun projectSafety() {
        ui.update { it.withRunEvidence(engine.state.value, safety.interrupted.value, lead) }
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
