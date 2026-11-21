// Ported from internal/deviceapp/lifecycle.go and internal/httpapi/playback_devices.go at 1b80d72e.
package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceConnectionFailure
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
import com.magichandy.mobile.core.ports.BridgeSelectionRequired
import com.magichandy.mobile.core.ports.DeviceConnectionException
import com.magichandy.mobile.core.ports.DeviceLinkLoss
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import java.io.IOException
import kotlin.time.Duration
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
import kotlinx.coroutines.withTimeoutOrNull

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
        var failure: DeviceConnectionFailure? = null
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
                val started = d.clock.elapsed()
                val reachable =
                    try {
                        val handyCloud = owner.device.connection == ConnectionKind.HandyCloud
                        val result =
                            withTimeoutOrNull(if (handyCloud) 8500L else 5000L) {
                                withContext(d.dispatchers.io) { read() }
                            }
                        if (result == null && handyCloud)
                            connection.failure =
                                DeviceConnectionFailure(DeviceConnectionFailure.Reason.TimedOut)
                        result == true
                    } catch (e: DeviceConnectionException) {
                        connection.failure = e.failure
                        false
                    } catch (_: IOException) {
                        false
                    } catch (_: SecurityException) {
                        false
                    }
                if (reachable)
                    updateReadings(
                        owner,
                        (d.clock.elapsed() - started).takeIf {
                            owner.device.connection == ConnectionKind.HandyCloud ||
                                owner.device.connection == ConnectionKind.AutoblowCloud
                        },
                    )
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
        edit(id) { it.copy(status = DeviceStatus.Connecting, connectionFailure = null) }
        connection.opening =
            connection.scope.launch(d.dispatchers.session) { open(id, row.connection, connection) }
    }

    suspend fun retryConnection(id: DeviceId) {
        val row = ui.value.devices.devices.singleOrNull { it.id == id } ?: return
        if (row.connection != ConnectionKind.HandyCloud) return
        if (row.canRetryWhileConnecting) {
            if (connections[id]?.closing != false || id in engine.state.value.owners) return
            disconnect(id)
        } else if (row.status != DeviceStatus.Lost && row.status != DeviceStatus.NotConnected)
            return
        connect(id)
    }

    private suspend fun verifiedOwner(id: DeviceId, connection: Connection): DeviceTransport {
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
            throw DeviceConnectionException(
                DeviceConnectionFailure(DeviceConnectionFailure.Reason.ServiceStartFailed)
            )
        }
        return withContext(d.dispatchers.io) { d.devices.connect(id, connection.scope) }
            ?: throw IOException("Device connection unavailable")
    }

    private suspend fun open(id: DeviceId, kind: ConnectionKind, connection: Connection) {
        var attached = false
        var needsSelection = false
        try {
            val owner =
                if (kind == ConnectionKind.HandyCloud) {
                    // One deadline covers service admission and every cloud exchange in this
                    // attempt.
                    withTimeoutOrNull(3000L) { verifiedOwner(id, connection) }
                        ?: throw DeviceConnectionException(
                            DeviceConnectionFailure(DeviceConnectionFailure.Reason.TimedOut)
                        )
                } else verifiedOwner(id, connection)
            if (connection.closing || !d.runHost.isRunning) return
            if (owner.device.id != id) {
                record(DiagnosticKind.DeviceConnectionFailed)
                return
            }
            engine.attach(owner)
            attached = true
            owner.feedback?.let { flow ->
                connection.scope.launch(d.dispatchers.session) {
                    flow.collect { value ->
                        if (
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
                it.copy(
                    status = DeviceStatus.Connected,
                    membership = RunMembership.NextRun,
                    connectionFailure = null,
                )
            }
            ui.update { state ->
                state.copy(
                    banners = state.banners.filterNot { it is Banner.DeviceLost && it.device == id }
                )
            }
            refreshLead()
            connection.scope.launch(d.dispatchers.session) { updateReadings(owner) }
            if (
                safety.awaitsReturn(id) ||
                    engine.state.value.receipt?.outcomes.orEmpty().any {
                        it.device == id && it.outcome != StopOutcome.Confirmed
                    }
            )
                safety.retryRecovery(id)
        } catch (_: BridgeSelectionRequired) {
            needsSelection = true
            record(DiagnosticKind.DeviceConnectionFailed)
        } catch (e: DeviceConnectionException) {
            connection.failure = e.failure
            record(DiagnosticKind.DeviceConnectionFailed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            record(DiagnosticKind.DeviceConnectionFailed)
        } finally {
            if (!attached && !connection.closing) {
                connection.closing = true
                withContext(NonCancellable) { release(id, connection, lost = true) }
                if (needsSelection) edit(id) { it.copy(status = DeviceStatus.NeedsSetup) }
            }
        }
    }

    private suspend fun updateReadings(owner: DeviceTransport, latency: Duration? = null) {
        val battery =
            try {
                withContext(d.dispatchers.io) { owner.readBattery() }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        if (engine.state.value.owners[owner.device.id] === owner)
            edit(owner.device.id) {
                it.copy(battery = battery, linkLatency = latency ?: owner.measuredLatency)
            }
    }

    suspend fun linkLost(loss: DeviceLinkLoss) {
        val active = connections[loss.device] ?: return
        if (loss.generation == null || active.scope.coroutineContext[Job] === loss.generation)
            lose(listOf(loss.device))
    }

    suspend fun lose(ids: List<DeviceId>) {
        val original = ids.associateWith(connections::get)
        val state = engine.state.value
        val participants = ids.filter { it in state.members }.mapNotNull(state.owners::get)
        if (participants.isNotEmpty()) {
            participants.forEach { safety.lost(it.device) }
            engine.stop(null, expected = state.epoch, lostDevice = participants.first().device.name)
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
        for ((id, connection) in original) {
            if (connection != null && connections[id] === connection) disconnect(id, lost = true)
        }
    }

    suspend fun releaseAfterHostLoss() {
        for (id in connections.keys.toList()) disconnect(id, alreadyStopped = true)
    }

    suspend fun disconnect(id: DeviceId, lost: Boolean = false, alreadyStopped: Boolean = false) {
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
            engine.stop(null, lostDevice = owner.device.name)
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
        } else if (!lost && !alreadyStopped) {
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
                connectionFailure = if (lost) connection.failure else null,
                output = null,
                battery = null,
                linkLatency = null,
                membership = RunMembership.NotInRun,
            )
        }
        refreshLead()
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

    fun refreshLead() {
        val eligible =
            ui.value.devices.devices.filter {
                it.status == DeviceStatus.Connected && it.membership == RunMembership.NextRun
            }
        setLead(eligible.firstOrNull { it.id == lead }?.id ?: eligible.firstOrNull()?.id)
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
        val prior = ui.value.devices.devices.firstOrNull { it.id == id }?.settings
        val next =
            when (prior) {
                is DeviceSettings.Handy -> prior.copy(isReversed = isReversed)
                is DeviceSettings.LinearActuator -> prior.copy(isReversed = isReversed)
                else -> return
            }
        if (prior != next && d.devices.setHandyReversed(id, isReversed))
            edit(id) { it.copy(settings = next) }
    }

    fun projectSafety() {
        ui.update { before ->
            val s = engine.state.value
            val projected = before.withRunEvidence(s, safety.interrupted.value, lead)
            projected.copy(
                stopReceipt =
                    projected.stopReceipt?.takeUnless {
                        it.isNoDevice && !safety.inspectionComplete.value
                    },
                devices =
                    projected.devices.copy(
                        awaitingReturn = safety.awaitingReturn.value.keys - s.reconciled
                    ),
            )
        }
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
