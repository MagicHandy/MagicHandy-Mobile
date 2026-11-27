package com.magichandy.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.StartupState
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.api.VoiceControls
import com.magichandy.mobile.ui.chat.ChatScreen
import com.magichandy.mobile.ui.components.AppFrame
import com.magichandy.mobile.ui.components.BottomTabs
import com.magichandy.mobile.ui.components.MainTab
import com.magichandy.mobile.ui.components.isTyping
import com.magichandy.mobile.ui.devices.AddDeviceOrigin
import com.magichandy.mobile.ui.devices.AddDeviceScreen
import com.magichandy.mobile.ui.devices.DeviceDetailScreen
import com.magichandy.mobile.ui.devices.DevicesScreen
import com.magichandy.mobile.ui.live.LiveScreen
import com.magichandy.mobile.ui.models.AddServerScreen
import com.magichandy.mobile.ui.models.ChatGptScreen
import com.magichandy.mobile.ui.models.ModelExits
import com.magichandy.mobile.ui.models.ModelsScreen
import com.magichandy.mobile.ui.models.OpenRouterScreen
import com.magichandy.mobile.ui.models.ProviderOrigin
import com.magichandy.mobile.ui.navigation.AppNavigator
import com.magichandy.mobile.ui.navigation.Route
import com.magichandy.mobile.ui.navigation.SheetPage
import com.magichandy.mobile.ui.navigation.rememberAppNavigator
import com.magichandy.mobile.ui.onboarding.BeforeYouStartScreen
import com.magichandy.mobile.ui.personas.PersonaEditorScreen
import com.magichandy.mobile.ui.personas.PersonasScreen
import com.magichandy.mobile.ui.settings.AppSettingsScreen
import com.magichandy.mobile.ui.settings.AppSheets
import com.magichandy.mobile.ui.settings.SheetExits
import com.magichandy.mobile.ui.setup.ChooseAiExits
import com.magichandy.mobile.ui.setup.ChooseAiScreen
import com.magichandy.mobile.ui.setup.CompanionStepScreen
import com.magichandy.mobile.ui.setup.ReadyExits
import com.magichandy.mobile.ui.setup.ReadyStepScreen
import com.magichandy.mobile.ui.stop.ReceiptScrim
import com.magichandy.mobile.ui.stop.RunInterruptedScreen
import com.magichandy.mobile.ui.stop.StopReceiptPanel
import com.magichandy.mobile.ui.stop.dismissalKey
import com.magichandy.mobile.ui.stop.isDismissed
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import com.magichandy.mobile.ui.voice.ListeningChip
import com.magichandy.mobile.ui.voice.VoiceAnnouncer
import com.magichandy.mobile.ui.voice.VoiceSettingsScreen
import com.magichandy.mobile.ui.voice.VoiceUi
import com.magichandy.mobile.ui.voice.rememberVoiceUi

/**
 * The whole UI. It observes [core] and sends it intents; Stop goes straight to the core.
 *
 * @param openLive true after the ongoing notification was tapped. The root opens Live on the normal
 *   back stack and then calls [onLiveOpened].
 */
@Composable
fun MagicHandyRoot(
    core: MagicHandyCore,
    voice: VoiceControls = core.voice,
    openLive: Boolean = false,
    onLiveOpened: () -> Unit = {},
) {
    val state by core.state.collectAsStateWithLifecycle()
    val onIntent: (UserIntent) -> Unit = remember(core) { { core.dispatch(it) } }
    val onStop: (StopSource) -> Unit = remember(core) { { core.emergencyStop(it) } }
    val calls = remember(core) { CoreCalls.of(core) }
    LocalizedUi(state.app.language) {
        MagicHandyTheme(state.app.palette) {
            AppContent(state, onIntent, onStop, calls, openLive, onLiveOpened, voice)
        }
    }
}

/**
 * The frame and everything in it, for a given [state]. Stateless apart from navigation and which
 * Stop receipt was last dismissed.
 *
 * @param calls the core's calls that aren't intents; previews leave them doing nothing.
 * @param openLive see [MagicHandyRoot].
 * @param voice voice input; previews leave it not offered.
 */
@Composable
fun AppContent(
    state: AppState,
    onIntent: (UserIntent) -> Unit,
    onStop: (StopSource) -> Unit,
    calls: CoreCalls = CoreCalls.None,
    openLive: Boolean = false,
    onLiveOpened: () -> Unit = {},
    voice: VoiceControls = NoVoice,
) {
    val nav = rememberAppNavigator()
    val voiceUi =
        rememberVoiceUi(
            voice,
            openSetup = { nav.openSheet(SheetPage.VoiceSetup) },
            openPicker = { nav.pushSheet(SheetPage.VoicePicker) },
            openModelSettings = { nav.openSheet(SheetPage.VoiceModel) },
            openSettings = { nav.push(Route.VoiceSettings) },
        )
    val receipt = state.stopReceipt
    LaunchedEffect(receipt?.episodeId, receipt?.at) { if (receipt != null) nav.closeSheet() }
    val interrupted = state.interruptedRun
    val launchScreen = initialScreen(state)
    val isGated = launchScreen != InitialScreen.App
    // After Before you start, setup's other steps run as routes until Finish setup.
    val inSetup = state.firstRun.hasAcknowledgedAdultsOnly && !state.firstRun.isSetupDone
    // A tap on the ongoing notification opens Live (board V-System), unless a gate or setup is
    // showing.
    val stackInSetup = nav.stack.any { it is Route.Setup }
    LaunchedEffect(openLive, isGated, inSetup, stackInSetup) {
        if (openLive && !isGated && !inSetup && !stackInSetup) {
            nav.openLive()
            onLiveOpened()
        }
    }
    BackHandler(enabled = !isGated && !nav.isAtDestination) { nav.pop() }
    // The core keeps a receipt while any device is unconfirmed, and Devices goes on showing those
    // devices, so Done hides the panel here until Stop is pressed again or another device turns
    // out unconfirmed.
    var dismissedReceipt by rememberSaveable { mutableStateOf("") }
    val dismissReceipt = {
        if (receipt != null) dismissedReceipt = dismissalKey(receipt)
        onIntent(UserIntent.DismissStopReceipt)
    }
    val shownReceipt = receipt?.takeIf { interrupted == null && !isDismissed(it, dismissedReceipt) }
    val stopLabel =
        stringResource(
            if (shownReceipt != null && !shownReceipt.isNoDevice) R.string.stop_again
            else R.string.stop
        )
    val showTabs = !isGated && !inSetup && nav.isAtDestination
    // Chat's voice bar has End; everywhere else, and under a sheet, the chip does.
    val showsChip =
        voiceUi.state.listening != null && (nav.current != Route.Chat || nav.sheet != null)
    AppFrame(
        stopLabel = stopLabel,
        contentBackground =
            if (!isGated && nav.current == Route.Live) {
                MagicHandyTheme.colors.chrome
            } else {
                MagicHandyTheme.colors.ground
            },
        onStop = onStop,
        bottomBar =
            if (showTabs) {
                {
                    BottomTabs(
                        selected =
                            if (nav.current == Route.Devices) MainTab.Devices else MainTab.Chat,
                        isSettingsOpen = nav.sheets.firstOrNull() == SheetPage.Settings,
                        onSelect = { tab ->
                            nav.selectDestination(
                                if (tab == MainTab.Devices) Route.Devices else Route.Chat
                            )
                        },
                        onSettings = {
                            if (nav.sheets.firstOrNull() == SheetPage.Settings) {
                                nav.closeSheet()
                            } else {
                                nav.openSheet(SheetPage.Settings)
                            }
                        },
                    )
                }
            } else {
                null
            },
        aboveTabs = if (showsChip) ({ ListeningChip(voiceUi) }) else null,
        overlay = {
            VoiceAnnouncer(voiceUi)
            if (!isGated) {
                AppSheets(
                    state = state,
                    nav = nav,
                    onIntent = onIntent,
                    calls = calls,
                    exits =
                        SheetExits(
                            openAppSettings = { nav.push(Route.AppSettings) },
                            openModels = { nav.push(Route.Models) },
                            openPersonas = { nav.push(Route.Personas) },
                            addDevice = { nav.push(Route.AddDevice(it)) },
                        ),
                    voice = voiceUi,
                )
            }
            if (shownReceipt != null && !shownReceipt.isNoDevice) ReceiptScrim(dismissReceipt)
            // Toasts and the receipt stack upwards from the footer, and the receipt scrolls
            // rather than pushing past the top of the screen.
            Column(
                Modifier.matchParentSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = MagicHandyTheme.dimens.xl),
                verticalArrangement = Arrangement.Bottom,
            ) {
                state.backendNotice?.let { BackendNoticeToast(it) }
                if (shownReceipt != null) {
                    val endedBy = voiceUi.state.endedByStop
                    StopReceiptPanel(
                        shownReceipt,
                        onDone = dismissReceipt,
                        modifier = Modifier.weight(1f, fill = false),
                        hasEndedVoice = endedBy != null && endedBy == state.stopEpoch,
                    )
                }
            }
        },
    ) {
        when (launchScreen) {
            InitialScreen.Recovery -> RunInterruptedScreen(requireNotNull(interrupted), onIntent)
            InitialScreen.Loading -> Unit
            InitialScreen.StorageError ->
                Text(
                    stringResource(R.string.startup_storage_failed),
                    modifier = Modifier.padding(MagicHandyTheme.dimens.l),
                    color = MagicHandyTheme.colors.text,
                )
            InitialScreen.Consent ->
                BeforeYouStartScreen(onContinue = { onIntent(UserIntent.AcknowledgeAdultsOnly) })
            InitialScreen.App ->
                if (inSetup != stackInSetup) {
                    LaunchedEffect(inSetup) {
                        nav.resetTo(if (inSetup) SetupStart else listOf(Route.Chat))
                    }
                } else
                    RouteStates(nav) { route ->
                        RouteContent(route, state, nav, onIntent, calls, voiceUi)
                    }
        }
    }
}

internal enum class InitialScreen {
    Recovery,
    Loading,
    StorageError,
    Consent,
    App,
}

/** The independent run journal can identify a device needing Stop even if Room cannot open. */
internal fun initialScreen(state: AppState): InitialScreen =
    when {
        state.interruptedRun != null -> InitialScreen.Recovery
        state.startup == StartupState.Loading -> InitialScreen.Loading
        state.startup == StartupState.StorageUnavailable -> InitialScreen.StorageError
        !state.firstRun.hasAcknowledgedAdultsOnly -> InitialScreen.Consent
        else -> InitialScreen.App
    }

/** Voice input for previews and tests, which never offer it. */
private val NoVoice: VoiceControls = VoiceControls.Fixed()

/** Where setup resumes once Before you start is acknowledged: Choose your AI, above step 1. */
private val SetupStart = listOf(Route.Setup(1), Route.Setup(2))

/** Routes whose saved state is kept even while they're off the stack, as the tabs switch. */
private val Destinations = setOf(Route.Chat.key, Route.Devices.key)

/**
 * Shows the current route with the state it saved, such as a draft or a scroll position, kept while
 * it's on the stack: Chat's draft survives a trip to Choose your AI. A route that's popped starts
 * afresh next time, except the two destinations.
 */
@Composable
private fun RouteStates(nav: AppNavigator, content: @Composable (Route) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val keys = nav.stack.map { it.key }
    var kept by rememberSaveable { mutableStateOf(listOf<String>()) }
    LaunchedEffect(keys) {
        val gone = kept.filter { it !in keys && it !in Destinations }
        gone.forEach(holder::removeState)
        kept = (kept - gone.toSet() + keys).distinct()
    }
    val route = nav.current
    holder.SaveableStateProvider(route.key) { content(route) }
}

/** Moves setup on to [step] from the step before it, so a second tap does nothing. */
private fun AppNavigator.toSetupStep(step: Int) {
    if (current == Route.Setup(step - 1)) push(Route.Setup(step))
}

/**
 * After a provider is saved: setup goes on to Your companion, Choose your AI opened from Chat
 * returns to Chat, and Models shows the new connection.
 */
private fun AppNavigator.providerSaved() {
    when (previous) {
        is Route.Setup -> {
            pop()
            push(Route.Setup(3))
        }
        Route.ChooseAi -> {
            pop()
            pop()
        }
        else -> pop()
    }
}

/** Leaves a persona's editor for Personas, which it was opened from. */
private fun AppNavigator.leavePersonaEditor() {
    if (Route.Personas in stack) popTo(Route.Personas) else pop()
}

/** Provider pages name the page Back returns to: Models, or Choose your AI. */
internal fun providerOrigin(previous: Route?): ProviderOrigin =
    if (previous is Route.Setup || previous == Route.ChooseAi) {
        ProviderOrigin.ChooseAi
    } else {
        ProviderOrigin.Models
    }

/** Add device pages name the page Back returns to: Devices, or Setup. */
internal fun addDeviceOrigin(previous: Route?): AddDeviceOrigin =
    if (previous is Route.Setup) AddDeviceOrigin.Setup else AddDeviceOrigin.Devices

@Composable
private fun RouteContent(
    route: Route,
    state: AppState,
    nav: AppNavigator,
    onIntent: (UserIntent) -> Unit,
    calls: CoreCalls,
    voice: VoiceUi,
) {
    val system = systemAccess(state.app)
    val origin = providerOrigin(nav.previous)
    when (route) {
        Route.Chat ->
            ChatScreen(
                state = state,
                isTyping = isTyping(),
                onIntent = onIntent,
                onOpenChats = { nav.openSheet(SheetPage.Chats) },
                onOpenLive = { nav.push(Route.Live) },
                onOpenDevices = { nav.selectDestination(Route.Devices) },
                onOpenSettings = { nav.openSheet(SheetPage.Settings) },
                onChooseAi = {
                    nav.push(Route.ChooseAi)
                    if (state.models.chatNeedsSignIn) nav.push(Route.ChatGptSetup)
                },
                voice = voice,
            )
        Route.Devices ->
            DevicesScreen(
                state = state,
                system = system,
                onIntent = onIntent,
                onOpenDevice = { nav.push(Route.DeviceDetail(it)) },
                onAddDevice = { nav.openSheet(SheetPage.DeviceType) },
                onOpenNotificationSettings = rememberSystemLinks().openNotificationSettings,
            )
        Route.Live ->
            LiveScreen(
                state = state,
                onIntent = onIntent,
                onStepCoyote = calls.stepCoyote,
                onBack = { nav.pop() },
                onOpenAutopilotSettings = { nav.openSheet(SheetPage.Autopilot) },
            )
        is Route.DeviceDetail -> {
            val device = state.devices.devices.firstOrNull { it.id == route.device }
            if (device == null) {
                LaunchedEffect(route) { nav.pop() }
            } else {
                val links = rememberSystemLinks()
                DeviceDetailScreen(
                    device = device,
                    isBatteryUnrestricted = system.isBatteryUnrestricted,
                    hasAcknowledgedCoyoteNotes = state.devices.hasAcknowledgedCoyoteNotes,
                    onIntent = onIntent,
                    submit = calls.submit,
                    onSetCoyoteLimits = calls.setCoyoteLimits,
                    onBack = { nav.pop() },
                    onOpenBatterySettings = links.openBatterySettings,
                    calls = calls,
                )
            }
        }
        is Route.AddDevice ->
            AddDeviceScreen(
                family = route.family,
                state = state,
                onIntent = onIntent,
                calls = calls,
                onBack = { nav.pop() },
                onDone = { nav.pop() },
                origin = addDeviceOrigin(nav.previous),
            )
        Route.AppSettings ->
            AppSettingsScreen(
                state = state,
                system = system,
                links = rememberSystemLinks(),
                diagnostics = rememberDiagnosticsShare(calls.diagnosticsReport),
                onIntent = onIntent,
                onBack = { nav.pop() },
                onOpenModels = { nav.push(Route.Models) },
                voice = voice,
            )
        Route.VoiceSettings -> VoiceSettingsScreen(voice, onBack = { nav.pop() })
        Route.Models ->
            ModelsScreen(
                state = state,
                submit = calls.submit,
                exits =
                    ModelExits(
                        back = { nav.pop() },
                        addServer = { nav.push(Route.AddServer) },
                        addOpenRouter = { nav.push(Route.OpenRouterSetup) },
                        addChatGpt = { nav.push(Route.ChatGptSetup) },
                        openRouterConnection = { nav.push(Route.OpenRouterConnection(it)) },
                    ),
            )
        Route.AddServer ->
            AddServerScreen(
                state,
                onIntent,
                calls.submit,
                onBack = { nav.pop() },
                origin = origin,
                onSaved = { nav.providerSaved() },
            )
        Route.OpenRouterSetup ->
            OpenRouterScreen(
                state.models,
                calls.submit,
                onIntent,
                onBack = { nav.pop() },
                origin = origin,
                onSaved = { nav.providerSaved() },
            )
        is Route.OpenRouterConnection -> {
            val connection = state.models.connections.firstOrNull { it.id == route.connection }
            if (connection == null) {
                // Removed, here or elsewhere: there's nothing left to change.
                LaunchedEffect(route) { nav.pop() }
            } else {
                OpenRouterScreen(
                    state.models,
                    calls.submit,
                    onIntent,
                    onBack = { nav.pop() },
                    editing = connection,
                )
            }
        }
        Route.ChatGptSetup ->
            ChatGptScreen(
                state,
                onIntent,
                onBack = { nav.pop() },
                origin = origin,
                onSaved = { nav.providerSaved() },
            )
        is Route.Setup -> SetupStep(route.step, state, nav, onIntent, calls)
        Route.ChooseAi ->
            ChooseAiScreen(
                ChooseAiExits(
                    back = { nav.pop() },
                    chatGpt = { nav.push(Route.ChatGptSetup) },
                    openRouter = { nav.push(Route.OpenRouterSetup) },
                    server = { nav.push(Route.AddServer) },
                )
            )
        Route.Personas ->
            PersonasScreen(
                state = state,
                onBack = { nav.pop() },
                onNew = { nav.push(Route.PersonaEditor(null)) },
                onEdit = { nav.push(Route.PersonaEditor(it)) },
            )
        is Route.PersonaEditor -> {
            val persona =
                route.persona?.let { id -> state.settings.personas.firstOrNull { it.id == id } }
            if (route.persona != null && persona == null) {
                // Deleted, here or elsewhere: there's nothing left to edit.
                LaunchedEffect(route) { nav.leavePersonaEditor() }
            } else {
                PersonaEditorScreen(
                    state = state,
                    persona = persona,
                    onIntent = onIntent,
                    submit = calls.submit,
                    onDone = { nav.leavePersonaEditor() },
                )
            }
        }
    }
}

/** One step of setup after Before you start's first visit. Back walks back through the steps. */
@Composable
private fun SetupStep(
    step: Int,
    state: AppState,
    nav: AppNavigator,
    onIntent: (UserIntent) -> Unit,
    calls: CoreCalls,
) {
    when (step) {
        1 -> BeforeYouStartScreen(onContinue = { nav.toSetupStep(2) }, hasAcknowledged = true)
        2 ->
            ChooseAiScreen(
                ChooseAiExits(
                    back = { nav.pop() },
                    chatGpt = { nav.push(Route.ChatGptSetup) },
                    openRouter = { nav.push(Route.OpenRouterSetup) },
                    server = { nav.push(Route.AddServer) },
                    skip = { nav.toSetupStep(3) },
                )
            )
        3 ->
            CompanionStepScreen(
                state = state,
                submit = calls.submit,
                onBack = { nav.pop() },
                onNext = { nav.toSetupStep(4) },
            )
        else ->
            ReadyStepScreen(
                state = state,
                exits =
                    ReadyExits(
                        back = { nav.pop() },
                        chooseAi = { nav.popTo(Route.Setup(2)) },
                        companion = { nav.popTo(Route.Setup(3)) },
                        addDevice = { nav.openSheet(SheetPage.DeviceType) },
                        finish = { onIntent(UserIntent.FinishSetup) },
                    ),
            )
    }
}
