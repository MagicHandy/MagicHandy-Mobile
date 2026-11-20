package com.magichandy.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.chat.ChatScreen
import com.magichandy.mobile.ui.components.AppFrame
import com.magichandy.mobile.ui.components.BottomTabs
import com.magichandy.mobile.ui.components.MainTab
import com.magichandy.mobile.ui.components.isTyping
import com.magichandy.mobile.ui.devices.AddDeviceScreen
import com.magichandy.mobile.ui.devices.DeviceDetailScreen
import com.magichandy.mobile.ui.devices.DevicesScreen
import com.magichandy.mobile.ui.live.LiveScreen
import com.magichandy.mobile.ui.models.AddServerScreen
import com.magichandy.mobile.ui.models.ChatGptScreen
import com.magichandy.mobile.ui.models.ModelExits
import com.magichandy.mobile.ui.models.ModelsScreen
import com.magichandy.mobile.ui.models.OpenRouterScreen
import com.magichandy.mobile.ui.navigation.AppNavigator
import com.magichandy.mobile.ui.navigation.Route
import com.magichandy.mobile.ui.navigation.SheetPage
import com.magichandy.mobile.ui.navigation.rememberAppNavigator
import com.magichandy.mobile.ui.onboarding.BeforeYouStartScreen
import com.magichandy.mobile.ui.settings.AppSettingsScreen
import com.magichandy.mobile.ui.settings.AppSheets
import com.magichandy.mobile.ui.settings.SheetExits
import com.magichandy.mobile.ui.stop.ReceiptScrim
import com.magichandy.mobile.ui.stop.RunInterruptedScreen
import com.magichandy.mobile.ui.stop.StopReceiptPanel
import com.magichandy.mobile.ui.stop.dismissalKey
import com.magichandy.mobile.ui.stop.isDismissed
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * The whole UI. It observes [core] and sends it intents; Stop goes straight to the core.
 *
 * @param openLive true after the ongoing notification was tapped. The root opens Live on the normal
 *   back stack and then calls [onLiveOpened].
 */
@Composable
fun MagicHandyRoot(
    core: MagicHandyCore,
    openLive: Boolean = false,
    onLiveOpened: () -> Unit = {},
) {
    val state by core.state.collectAsStateWithLifecycle()
    val onIntent: (UserIntent) -> Unit = remember(core) { { core.dispatch(it) } }
    val onStop: (StopSource) -> Unit = remember(core) { { core.emergencyStop(it) } }
    val calls = remember(core) { CoreCalls.of(core) }
    MagicHandyTheme(state.app.palette) {
        AppContent(state, onIntent, onStop, calls, openLive, onLiveOpened)
    }
}

/**
 * The frame and everything in it, for a given [state]. Stateless apart from navigation and which
 * Stop receipt was last dismissed.
 *
 * @param calls the core's calls that aren't intents; previews leave them doing nothing.
 * @param openLive see [MagicHandyRoot].
 */
@Composable
fun AppContent(
    state: AppState,
    onIntent: (UserIntent) -> Unit,
    onStop: (StopSource) -> Unit,
    calls: CoreCalls = CoreCalls.None,
    openLive: Boolean = false,
    onLiveOpened: () -> Unit = {},
) {
    val nav = rememberAppNavigator()
    val receipt = state.stopReceipt
    LaunchedEffect(receipt?.pressCount) { if (receipt != null) nav.closeSheet() }
    val interrupted = state.interruptedRun
    val isGated = !state.firstRun.hasAcknowledgedAdultsOnly || interrupted != null
    // A tap on the ongoing notification opens Live (board V-System), unless a gate is showing.
    LaunchedEffect(openLive) {
        if (openLive) {
            if (!isGated) nav.openLive()
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
    // A device-loss banner already says who confirmed Stop (board V-ConnectionLost), and Run
    // interrupted lists every device itself, so neither needs the receipt panel too.
    val hasLossBanner = state.banners.any { it is Banner.DeviceLost }
    val shownReceipt = receipt?.takeIf {
        !hasLossBanner && interrupted == null && !isDismissed(it, dismissedReceipt)
    }
    val stopLabel =
        stringResource(
            if (shownReceipt != null && !shownReceipt.isNoDevice) R.string.stop_again
            else R.string.stop
        )
    val showTabs = !isGated && nav.isAtDestination
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
        overlay = {
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
                            addDevice = { nav.push(Route.AddDevice(it)) },
                        ),
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
                    StopReceiptPanel(
                        shownReceipt,
                        onDone = dismissReceipt,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        },
    ) {
        when {
            !state.firstRun.hasAcknowledgedAdultsOnly ->
                BeforeYouStartScreen(onAcknowledge = { onIntent(UserIntent.AcknowledgeAdultsOnly) })
            interrupted != null -> RunInterruptedScreen(interrupted, onIntent)
            else -> RouteContent(state, nav, onIntent, calls)
        }
    }
}

@Composable
private fun RouteContent(
    state: AppState,
    nav: AppNavigator,
    onIntent: (UserIntent) -> Unit,
    calls: CoreCalls,
) {
    val system = systemAccess(state.app)
    when (val route = nav.current) {
        Route.Chat ->
            ChatScreen(
                state = state,
                isTyping = isTyping(),
                onIntent = onIntent,
                onOpenChats = { nav.openSheet(SheetPage.Chats) },
                onOpenLive = { nav.push(Route.Live) },
                onOpenDevices = { nav.selectDestination(Route.Devices) },
                onOpenSettings = { nav.openSheet(SheetPage.Settings) },
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
                    onSetCoyoteLimits = calls.setCoyoteLimits,
                    onBack = { nav.pop() },
                    onOpenBatterySettings = links.openBatterySettings,
                )
            }
        }
        is Route.AddDevice ->
            AddDeviceScreen(
                family = route.family,
                state = state,
                onIntent = onIntent,
                findVibrators = calls.findVibrators,
                onBack = { nav.pop() },
                onDone = { nav.pop() },
            )
        Route.AppSettings ->
            AppSettingsScreen(
                state = state,
                system = system,
                links = rememberSystemLinks(),
                onIntent = onIntent,
                onBack = { nav.pop() },
                onOpenModels = { nav.push(Route.Models) },
            )
        Route.Models ->
            ModelsScreen(
                state = state,
                onIntent = onIntent,
                exits =
                    ModelExits(
                        back = { nav.pop() },
                        addServer = { nav.push(Route.AddServer) },
                        addOpenRouter = { nav.push(Route.OpenRouterSetup) },
                        addChatGpt = { nav.push(Route.ChatGptSetup) },
                    ),
            )
        Route.AddServer -> AddServerScreen(state, onIntent, onBack = { nav.pop() })
        Route.OpenRouterSetup -> OpenRouterScreen(onIntent, onBack = { nav.pop() })
        Route.ChatGptSetup -> ChatGptScreen(state, onIntent, onBack = { nav.pop() })
    }
}
