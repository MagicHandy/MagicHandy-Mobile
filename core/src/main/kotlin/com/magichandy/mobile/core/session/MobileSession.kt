// Ported from internal/httpapi/{chat,motion,stop_admission}.go at 1b80d72e.
package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.BackendNotice
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.FormIntent
import com.magichandy.mobile.core.api.InterruptCause
import com.magichandy.mobile.core.api.LimitKind
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.StartRefusal
import com.magichandy.mobile.core.api.StepDirection
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.chat.isChatStopMessage
import com.magichandy.mobile.core.motion.MotionEngine
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.PreferenceEdit
import com.magichandy.mobile.core.ports.PreferenceField
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class MobileSession(dependencies: CoreDependencies) : MagicHandyCore {
    private val parent = dependencies.scope.coroutineContext
    private val reports =
        Channel<Pair<kotlin.time.Instant, DiagnosticKind>>(
            256,
            kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    private val d =
        dependencies.copy(
            scope =
                CoroutineScope(
                    parent +
                        SupervisorJob(parent[Job]) +
                        DiagnosticReporter(::record) +
                        CoroutineExceptionHandler { _, _ -> record(DiagnosticKind.CoreTaskFailed) }
                )
        )
    private val engine =
        MotionEngine(d.scope, d.dispatchers.stop, d.dispatchers.stopIo, d.clock, ::record)
    private val safety =
        RunSafety(engine, d.journal, d.runHost, d.devices, d.clock, d.scope, d.dispatchers.io)
    private val ui = MutableStateFlow(initialState(d))
    override val state = ui.asStateFlow()
    private val devices = Devices(d, engine, safety, ui, ::record)
    private val setup = DeviceSetup(d, ui, devices, engine)
    private val servers = ServerSetup(d, ui)
    private val checkIns = VacuCheckIns(d, ui, this)
    private val autopilot = Autopilot(d, engine, devices, safety, ui)
    private val chat =
        ChatTurns(
            d,
            engine,
            ui,
            ChatProposal(d, engine, ui, devices, this, autopilot),
        )

    private data class Queued(val epoch: Epoch, val intent: UserIntent)

    private val queue = Channel<Queued>(256)
    private val accounts = ModelAccounts(d, ui, ::record, ::unavailable, ::storeFailed)

    private val diagnostics = DiagnosticsExport(d)

    override suspend fun diagnosticsReport() = diagnostics.report(ui.value)

    private val tile = TileSettings(d, ::storeFailed)

    override fun updateStopTileAdded(added: Boolean) = tile.observe(added)

    private val forms = SessionForms(d, ui, engine, setup, servers, accounts, chat, ::record)

    override suspend fun submit(intent: FormIntent) = forms.submissions.submit(intent)

    init {
        d.scope.launch(d.dispatchers.io) {
            for ((at, kind) in reports) try {
                d.diagnostics.record(at, kind)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ui.update { it.copy(backendNotice = BackendNotice.StorageUnavailable) }
            }
        }
        d.scope.launch(d.dispatchers.session) {
            try {
                for ((epoch, intent) in queue) {
                    ui.update { it.copy(backendNotice = null) }
                    try {
                        handle(epoch, intent)
                    } catch (_: IOException) {
                        storeFailed()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        record(DiagnosticKind.CoreTaskFailed)
                    }
                }
            } finally {
                queue.cancel()
            }
        }
        d.scope.launch(d.dispatchers.session) {
            var last = engine.epoch
            var uncertainEpoch: Epoch? = null
            engine.state.collect { s ->
                diagnostics.observe(s.epoch, s.evidence ?: s.receipt)
                if (last != s.epoch) {
                    chat.stopEarlierThan(s.epoch)
                    autopilot.epochChanged(s.epoch)
                    last = s.epoch
                }
                if (
                    uncertainEpoch != s.epoch &&
                        s.receipt?.outcomes.orEmpty().any { it.outcome == StopOutcome.Unconfirmed }
                ) {
                    uncertainEpoch = s.epoch
                    record(DiagnosticKind.StopUnconfirmed)
                }
                devices.projectSafety()
            }
        }
        d.scope.launch(d.dispatchers.session) {
            safety.interrupted.collect { devices.projectSafety() }
        }
        d.scope.launch(d.dispatchers.session) {
            safety.journalUnavailable.collect {
                if (it) {
                    devices.refused(StartRefusal.JournalUnavailable)
                }
            }
        }
        d.scope.launch(d.dispatchers.session) {
            d.settings.state.collect { doc ->
                ui.update { it.preferences(doc.values) }
                autopilot.refresh()
            }
        }
        d.scope.launch(d.dispatchers.session) {
            d.models.state.collect { models ->
                ui.update { it.models(models, d.network.observe().value) }
                autopilot.refresh()
            }
        }
        d.scope.launch(d.dispatchers.session) { d.chats.state.collect { chat.showArchive() } }
        d.scope.launch(d.dispatchers.session) {
            ui.map { it.chat.reply is com.magichandy.mobile.core.api.ReplyState.Streaming }
                .distinctUntilChanged()
                .drop(1)
                .collect { autopilot.refresh() }
        }
        observeConnectivity(d, engine, devices, safety, ui, chat, autopilot)
        d.devices.configuration?.let { cfg ->
            d.scope.launch(d.dispatchers.session) { cfg.rows.collect(setup::refresh) }
        }
    }

    override fun emergencyStop(source: StopSource) {
        safety.emergencyStop(source)
        d.devices.stopOpenBridges(engine.epoch)
        record(DiagnosticKind.StopRequested)
    }

    override fun setRefusalFallback(connection: ConnectionId?) {
        d.scope.launch(d.dispatchers.session) {
            if (
                connection == null ||
                    d.models.state.value.models[connection]?.info?.provider in
                        ChatProposal.localProviders
            )
                patch(PreferenceField.RefusalFallback.edit(connection))
        }
    }

    override fun updateSystemAccess(notificationsAllowed: Boolean, batteryUnrestricted: Boolean) {
        ui.update {
            it.copy(
                app =
                    it.app.copy(
                        notificationsAllowed = notificationsAllowed,
                        hasSystemAccessSnapshot = true,
                        isBatteryUnrestricted = batteryUnrestricted,
                    )
            )
        }
    }

    override fun notificationPermissionRequested() {
        d.scope.launch(d.dispatchers.session) {
            patch(PreferenceField.AskedNotifications.edit(true))
        }
    }

    override fun appBackgrounded() {
        d.scope.launch(d.dispatchers.session) {
            if (engine.state.value.owners.isEmpty()) chat.interrupt(InterruptCause.AppInBackground)
        }
    }

    override fun trimMemory() {
        d.scope.launch(d.dispatchers.io) { d.chats.trimPages() }
    }

    override suspend fun intifaceLinearActuators(address: String) = setup.bridges.linearsAt(address)

    override suspend fun intifaceVibrators(address: String) = setup.bridges.vibratorsAt(address)

    override fun setCoyoteLimits(device: DeviceId, maximumA: Int, maximumB: Int) =
        setup.setCoyoteLimits(device, maximumA, maximumB)

    override fun stepCoyote(
        device: DeviceId,
        channel: com.magichandy.mobile.core.api.CoyoteChannel,
        increase: Boolean,
    ) = setup.stepCoyote(device, channel, increase)

    override fun stepTempoRange(device: DeviceId, direction: StepDirection) =
        setup.stepTempoRange(device, direction)

    private fun record(kind: DiagnosticKind) {
        reports.trySend(d.clock.now() to kind)
    }

    override fun dispatch(intent: UserIntent) {
        if (intent is UserIntent.SendMessage && isChatStopMessage(intent.text)) {
            emergencyStop(StopSource.TypedStop)
            return
        }
        if (
            intent is UserIntent.SetMotionMode &&
                intent.mode == MotionMode.Off &&
                engine.state.value.members.isNotEmpty()
        )
            engine.stop(null)
        if (queue.trySend(Queued(engine.epoch, intent)).isFailure) unavailable()
    }

    private suspend fun handle(epoch: Epoch, intent: UserIntent) {
        when (intent) {
            UserIntent.AcknowledgeAdultsOnly -> patch(PreferenceField.AdultsOnly.edit(true))
            UserIntent.AcknowledgeCoyoteNotes -> patch(PreferenceField.CoyoteNotes.edit(true))
            is UserIntent.SendMessage -> {
                chat.send(epoch, intent.text)
                autopilot.refresh()
            }
            UserIntent.SendAgain -> {
                chat.send(epoch, null, retry = true)
                autopilot.refresh()
            }
            UserIntent.NewChat -> chat.newChat()
            is UserIntent.OpenChat -> chat.open(intent.chat)
            UserIntent.StartMotion -> devices.playback.start(epoch, null)
            UserIntent.ResumeMotion -> {
                devices.playback.resume(epoch)
                autopilot.refresh()
            }
            is UserIntent.TestMotion -> devices.playback.start(epoch, intent.device)
            UserIntent.PauseMotion -> {
                devices.playback.pause(epoch)
                autopilot.refresh()
            }
            is UserIntent.SetEdge -> autopilot.edge(epoch, intent.isOn)
            is UserIntent.StepLimit -> step(intent)
            is UserIntent.Connect -> devices.connect(intent.device)
            is UserIntent.Disconnect -> devices.disconnect(intent.device)
            is UserIntent.SetInNextRun -> setMembership(intent)
            is UserIntent.SetLead -> devices.setLead(intent.device)
            is UserIntent.SetReversed -> devices.setReversed(intent.device, intent.isReversed)
            is UserIntent.ReconcileDevice -> reconcile(epoch, intent.device)
            UserIntent.DismissStopReceipt -> engine.dismissReceipt()
            UserIntent.DismissInterruptedRun -> safety.dismissInterrupted()
            UserIntent.ReconnectDevices -> devices.reconnectSaved()
            is UserIntent.SetMemory -> patch(PreferenceField.Memory.edit(intent.isOn))
            UserIntent.ClearMemory -> d.chats.clearMemory(defaultPersona.id)
            UserIntent.DeleteAllChatsAndMemory -> chat.clearAll()
            is UserIntent.SetPalette -> patch(PreferenceField.PaletteChoice.edit(intent.palette))
            is UserIntent.SetLanguage -> patch(PreferenceField.Language.edit(intent.language))
            is UserIntent.SetStyle -> patch(PreferenceField.Style.edit(intent.style))
            is UserIntent.SetMotionMode -> setMotionMode(intent.mode)
            is UserIntent.SetCheckIns -> patch(PreferenceField.CheckIn.edit(intent.checkIns))
            is UserIntent.SetChangeFrequency ->
                patch(PreferenceField.Frequency.edit(intent.frequency))
            is UserIntent.SetFinishTarget -> setFinishTarget(intent)
            is UserIntent.SetPersona -> if (intent.persona != defaultPersona.id) unavailable()
            is UserIntent.SetChatConnection -> d.models.bindChat(intent.connection)
            is UserIntent.SetPlannerConnection -> d.models.bindPlanner(intent.connection)
            UserIntent.StartChatGptSignIn -> accounts.startSignIn()
            UserIntent.CancelSignIn -> accounts.cancelSignIn()
            is UserIntent.StartScan -> setup.startScan(intent.family)
            UserIntent.StopScan -> setup.stopScan()
            is UserIntent.ConnectScanResult -> setup.select(intent)
            is FormIntent -> forms.submissions.dispatch(intent)
            is UserIntent.CheckConnection -> devices.check(intent.device)
            // These intents become available when their complete backend milestone is wired.
            UserIntent.StartAutopilot -> autopilot.start(epoch)
            UserIntent.PauseAutopilot -> autopilot.pause(true)
            UserIntent.ResumeAutopilot -> autopilot.pause(false)
            UserIntent.TurnOffAutopilot -> autopilot.off()
            is UserIntent.SetCheckInInterval ->
                if (intent.every == null || intent.every.isFinite() && intent.every.isPositive())
                    setup.edit(intent.device) { row ->
                        val v =
                            row.settings as? com.magichandy.mobile.core.api.DeviceSettings.VacuGlide
                        if (v == null) row
                        else row.copy(settings = v.copy(checkInEvery = intent.every))
                    }
            is UserIntent.SetValveSteps ->
                setup.edit(intent.device) { row ->
                    val v = row.settings as? com.magichandy.mobile.core.api.DeviceSettings.VacuGlide
                    if (v == null) row
                    else row.copy(settings = v.copy(mayStepValve = intent.isAllowed))
                }
            is UserIntent.AnswerCheckIn -> checkIns.answer(intent.device)
            is UserIntent.CheckServer -> servers.check(intent.address)
            is UserIntent.SelectServerModel -> servers.select(intent.model)
            UserIntent.CheckForUpdate -> accounts.checkRelease()
        }
    }

    private fun setMembership(intent: UserIntent.SetInNextRun) {
        if (engine.state.value.members.isNotEmpty()) return unavailable()
        devices.edit(intent.device) {
            it.copy(
                membership =
                    if (intent.isIncluded) RunMembership.NextRun else RunMembership.NotInRun
            )
        }
    }

    private fun reconcile(epoch: Epoch, id: DeviceId) {
        val interrupted =
            ui.value.interruptedRun?.outcomes.orEmpty().any {
                it.device == id && it.outcome != StopOutcome.Confirmed
            }
        if (engine.reconcile(epoch, id, interrupted)) record(DiagnosticKind.DeviceReconciled)
    }

    private suspend fun setFinishTarget(intent: UserIntent.SetFinishTarget) {
        val total = intent.total
        if (total == null || total.isPositive() && total.isFinite())
            patch(PreferenceField.FinishAfter.edit(total))
        else unavailable()
    }

    private suspend fun setMotionMode(mode: MotionMode) {
        if (mode !in ui.value.settings.availableModes) return unavailable()
        if (mode == MotionMode.Off && engine.state.value.members.isNotEmpty()) engine.stop(null)
        patch(PreferenceField.Mode.edit(mode))
    }

    private suspend fun patch(edit: PreferenceEdit) {
        if (!d.settings.patch(d.settings.state.value.revision, listOf(edit)))
            ui.update { it.copy(backendNotice = BackendNotice.SettingsConflict) }
        else ui.update { it.preferences(d.settings.state.value.values) }
    }

    private suspend fun step(intent: UserIntent.StepLimit) {
        val l = d.settings.state.value.values.limits
        val by = if (intent.direction == StepDirection.Up) 5 else -5
        val next =
            when (intent.limit) {
                LimitKind.TopSpeed ->
                    l.copy(topSpeed = Percent((l.topSpeed.value + by).coerceIn(1, 100)))
                LimitKind.StrokeLow ->
                    l.copy(
                        strokeLow =
                            Percent((l.strokeLow.value + by).coerceIn(0, l.strokeHigh.value - 1))
                    )
                LimitKind.StrokeHigh ->
                    l.copy(
                        strokeHigh =
                            Percent((l.strokeHigh.value + by).coerceIn(l.strokeLow.value + 1, 100))
                    )
            }
        patch(PreferenceField.OutputLimits.edit(next))
    }

    private fun unavailable() {
        ui.update { it.copy(backendNotice = BackendNotice.FeatureUnavailable) }
    }

    private fun storeFailed() {
        record(DiagnosticKind.StoreUnavailable)
        ui.update { it.copy(backendNotice = BackendNotice.StorageUnavailable) }
    }
}
