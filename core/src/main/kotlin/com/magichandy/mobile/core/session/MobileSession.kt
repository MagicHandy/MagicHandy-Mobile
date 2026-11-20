// Ported from internal/httpapi/{chat,motion,stop_admission}.go at 9a4c36cf.
package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.BackendNotice
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.InterruptCause
import com.magichandy.mobile.core.api.LimitKind
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.SignInFailure
import com.magichandy.mobile.core.api.SignInState
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
import com.magichandy.mobile.core.ports.SavedModel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class MobileSession(dependencies: CoreDependencies) : MagicHandyCore {
    private val parent = dependencies.scope.coroutineContext
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
    private val chat = ChatTurns(d, engine, ui)

    private data class Queued(val epoch: Epoch, val intent: UserIntent)

    private val queue = Channel<Queued>(Channel.UNLIMITED)
    private var signIn: Job? = null

    init {
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
                if (last != s.epoch) {
                    chat.stopEarlierThan(s.epoch)
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
            d.settings.state.collect { doc -> ui.update { it.preferences(doc.values) } }
        }
        d.scope.launch(d.dispatchers.session) {
            d.models.state.collect { models -> ui.update { it.models(models) } }
        }
        d.scope.launch(d.dispatchers.session) { d.chats.state.collect { chat.showArchive() } }
        d.scope.launch(d.dispatchers.session) {
            d.devices.bluetoothOn.collect { on ->
                ui.update { it.copy(devices = it.devices.copy(isBluetoothOn = on)) }
            }
        }
        d.scope.launch(d.dispatchers.session) {
            d.devices.losses.collect { devices.disconnect(it, lost = true) }
        }
        observeNetwork()
    }

    override fun emergencyStop(source: StopSource) {
        safety.emergencyStop(source)
        record(DiagnosticKind.StopRequested)
    }

    private fun record(kind: DiagnosticKind) {
        val at = d.clock.now()
        d.scope.launch(d.dispatchers.io) {
            try {
                d.diagnostics.record(at, kind)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The diagnostics sink itself is unavailable. Surface that without recursing.
                ui.update { it.copy(backendNotice = BackendNotice.StorageUnavailable) }
            }
        }
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
        queue.trySend(Queued(engine.epoch, intent))
    }

    private suspend fun handle(epoch: Epoch, intent: UserIntent) {
        when (intent) {
            UserIntent.AcknowledgeAdultsOnly -> patch(PreferenceField.AdultsOnly.edit(true))
            UserIntent.AcknowledgeCoyoteNotes -> patch(PreferenceField.CoyoteNotes.edit(true))
            is UserIntent.SendMessage -> chat.send(epoch, intent.text)
            UserIntent.SendAgain -> chat.send(epoch, null, retry = true)
            UserIntent.NewChat -> chat.newChat()
            is UserIntent.OpenChat -> chat.open(intent.chat)
            UserIntent.StartMotion,
            UserIntent.ResumeMotion -> devices.start(epoch, null)
            is UserIntent.TestMotion -> devices.start(epoch, intent.device)
            UserIntent.PauseMotion -> engine.stop(null)
            is UserIntent.StepLimit -> step(intent)
            is UserIntent.Connect -> devices.connect(intent.device)
            is UserIntent.Disconnect -> devices.disconnect(intent.device)
            is UserIntent.SetInNextRun -> setMembership(intent)
            is UserIntent.SetLead -> devices.setLead(intent.device)
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
            is UserIntent.RemoveConnection -> removeConnection(intent.connection)
            UserIntent.StartChatGptSignIn -> startSignIn()
            UserIntent.CancelSignIn -> cancelSignIn()
            // These intents become available when their complete backend milestone is wired.
            is UserIntent.SetEdge,
            UserIntent.StartAutopilot,
            UserIntent.PauseAutopilot,
            UserIntent.ResumeAutopilot,
            UserIntent.TurnOffAutopilot,
            is UserIntent.StartScan,
            UserIntent.StopScan,
            is UserIntent.ConnectScanResult,
            is UserIntent.AddCloudHandy,
            is UserIntent.AddAutoblowDevice,
            is UserIntent.AddIntiface,
            is UserIntent.Rename,
            is UserIntent.SetReversed,
            is UserIntent.CheckConnection,
            is UserIntent.Remove,
            is UserIntent.SetCheckInInterval,
            is UserIntent.SetValveSteps,
            is UserIntent.AnswerCheckIn,
            is UserIntent.CheckServer,
            is UserIntent.SelectServerModel,
            is UserIntent.SaveServer,
            is UserIntent.SaveOpenRouter,
            UserIntent.CheckForUpdate -> unavailable()
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

    private suspend fun removeConnection(id: ConnectionId) {
        chat.interrupt(InterruptCause.ProviderError)
        val handle = d.models.state.value.models[id]?.credential
        d.models.remove(id)
        if (handle != null) d.vault.remove(handle)
    }

    private fun cancelSignIn() {
        signIn?.cancel()
        setSignIn(SignInState.Failed(SignInFailure.Cancelled))
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
        if (next != l && engine.state.value.members.isNotEmpty()) engine.stop(null)
        patch(PreferenceField.OutputLimits.edit(next))
    }

    private fun startSignIn() {
        val action = d.signIn ?: return unavailable()
        signIn?.cancel()
        setSignIn(SignInState.WaitingForBrowser)
        signIn =
            d.scope.launch(d.dispatchers.session) {
                val failure =
                    try {
                        action.signIn()
                    } catch (_: IOException) {
                        record(DiagnosticKind.AccountUnavailable)
                        SignInFailure.NetworkError
                    }
                if (!isActive) return@launch
                if (failure != null) {
                    setSignIn(SignInState.Failed(failure))
                    return@launch
                }
                val info =
                    ModelConnection(
                        ConnectionId("chatgpt"),
                        "ChatGPT",
                        ProviderKind.ChatGpt,
                        "gpt-6.1-terra",
                        "Terra",
                        CapabilityLevel.Advanced,
                        true,
                        true,
                        ConnectionStatus.Ready,
                    )
                try {
                    d.models.save(SavedModel(info, d.signInCredential))
                    setSignIn(SignInState.Idle)
                } catch (_: IOException) {
                    storeFailed()
                    setSignIn(SignInState.Failed(SignInFailure.NetworkError))
                }
            }
    }

    private fun setSignIn(value: SignInState) {
        ui.update { it.copy(models = it.models.copy(signIn = value)) }
    }

    private fun unavailable() {
        ui.update { it.copy(backendNotice = BackendNotice.FeatureUnavailable) }
    }

    private fun storeFailed() {
        record(DiagnosticKind.StoreUnavailable)
        ui.update { it.copy(backendNotice = BackendNotice.StorageUnavailable) }
    }

    private fun observeNetwork() {
        val network = d.network.observe()
        var previous = network.value
        d.scope.launch(d.dispatchers.session) {
            network.collect { current ->
                if (previous != current) {
                    previous = current
                    chat.interrupt(InterruptCause.ConnectionLost)
                    val owners =
                        engine.state.value.owners.values.filter {
                            it.device.connection in
                                setOf(
                                    ConnectionKind.HandyCloud,
                                    ConnectionKind.AutoblowCloud,
                                    ConnectionKind.IntifaceOnNetwork,
                                )
                        }
                    owners.forEach { devices.disconnect(it.device.id, lost = true) }
                    if (current.isOnline) safety.retryRecovery()
                }
                ui.update {
                    it.copy(devices = it.devices.copy(isOnline = current.isOnline))
                        .models(d.models.state.value)
                }
            }
        }
    }
}
