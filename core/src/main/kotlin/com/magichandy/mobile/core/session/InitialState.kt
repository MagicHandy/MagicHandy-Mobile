package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.AppSettingsState
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.AutopilotState
import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.ChatState
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.DevicesState
import com.magichandy.mobile.core.api.FinishTarget
import com.magichandy.mobile.core.api.FirstRunState
import com.magichandy.mobile.core.api.ModelsState
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.RunState
import com.magichandy.mobile.core.api.ScanState
import com.magichandy.mobile.core.api.SendBlock
import com.magichandy.mobile.core.api.ServerCheck
import com.magichandy.mobile.core.api.SessionSettings
import com.magichandy.mobile.core.api.SignInState
import com.magichandy.mobile.core.api.UpdateCheck
import com.magichandy.mobile.core.ports.ModelCatalog
import com.magichandy.mobile.core.ports.NetworkState
import com.magichandy.mobile.core.ports.Preferences
import com.magichandy.mobile.core.ports.SavedModel

internal val defaultPersona = PersonaRef(PersonaId(""), "MagicHandy", "")
internal val strokeModes = listOf(MotionMode.Layered, MotionMode.Simple, MotionMode.Off)

internal fun initialState(d: CoreDependencies): AppState {
    val p = d.settings.state.value.values
    val devices =
        d.devices.saved.map { (ref, settings) ->
            DeviceSummary(
                ref.id,
                ref.name,
                ref.family,
                ref.connection,
                DeviceStatus.NotConnected,
                RunMembership.NotInRun,
                null,
                null,
                null,
                settings,
                ref.afterLoss,
            )
        }
    val archive = d.chats.state.value
    val sorted = archive.chats.values.sortedByDescending { it.summary.lastActivity }
    val selected = sorted.firstOrNull()
    val initial =
        AppState(
            FirstRunState(p.adultsOnly),
            ChatState(
                selected?.summary?.id,
                selected?.summary?.title.orEmpty(),
                defaultPersona.name,
                null,
                selected?.messages.orEmpty(),
                ReplyState.Idle,
                sorted.map { it.summary },
                SendBlock.NoChatModel,
            ),
            RunState(RunPhase.Idle, null, null, p.limits, emptyList(), false),
            AutopilotState(AutopilotStatus.Off, null, null, p.frequency, p.checkIns),
            DevicesState(
                devices,
                d.devices.bluetoothOn.value,
                d.network.observe().value.isOnline,
                ScanState.Idle,
                p.coyoteNotes,
            ),
            SessionSettings(
                p.mode,
                strokeModes,
                p.style,
                defaultPersona,
                listOf(defaultPersona),
                p.memoryOn,
                archive.memory[defaultPersona.id].orEmpty().size,
            ),
            ModelsState(emptyList(), null, null, ServerCheck.Idle, SignInState.Idle),
            AppSettingsState(p.palette, p.language, d.versionName, UpdateCheck.NotChecked),
            null,
            null,
            emptyList(),
        )
    return initial.preferences(p).models(d.models.state.value, d.network.observe().value)
}

internal fun AppState.preferences(p: Preferences): AppState =
    copy(
        firstRun = FirstRunState(p.adultsOnly),
        models = models.copy(refusalFallback = p.refusalFallback),
        devices = devices.copy(hasAcknowledgedCoyoteNotes = p.coyoteNotes),
        app =
            app.copy(
                palette = p.palette,
                language = p.language,
                hasAskedForNotifications = p.askedNotifications,
            ),
        run = run.copy(limits = p.limits),
        autopilot =
            autopilot.copy(
                changeFrequency = p.frequency,
                checkIns = p.checkIns,
                finishTarget = p.finishAfter?.let { FinishTarget(it, null) },
            ),
        settings =
            settings.copy(
                motionMode =
                    p.mode.takeIf { it in settings.availableModes }
                        ?: settings.availableModes.first(),
                style = p.style,
                isMemoryOn = p.memoryOn,
            ),
    )

internal fun SavedModel.hasRoute(network: NetworkState): Boolean {
    if (info.provider !in ChatProposal.localProviders) return network.isOnline
    if (network.hasLocalRoute) return true
    // Only literal loopback remains reachable through a default-network handover.
    val host = address?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: return false
    return host.equals("localhost", ignoreCase = true) ||
        host == "[::1]" ||
        host.matches(Regex("127(\\.[0-9]{1,3}){3}"))
}

internal fun AppState.models(catalog: ModelCatalog, network: NetworkState): AppState {
    val projected =
        catalog.models.mapValues { (_, model) ->
            if (model.info.status == ConnectionStatus.Ready && !model.hasRoute(network))
                model.info.copy(status = ConnectionStatus.Unreachable)
            else model.info
        }
    val active = projected[catalog.chat]
    val blocked =
        when {
            active == null || active.status == ConnectionStatus.NeedsSignIn -> SendBlock.NoChatModel
            active.status != ConnectionStatus.Ready -> SendBlock.ModelUnreachable
            else -> null
        }
    return copy(
        models =
            models.copy(
                connections = projected.values.toList(),
                chatConnection = catalog.chat,
                plannerConnection = catalog.planner,
            ),
        chat =
            chat.copy(
                modelName = active?.shortName,
                sendBlockedBy = blocked,
            ),
    )
}
