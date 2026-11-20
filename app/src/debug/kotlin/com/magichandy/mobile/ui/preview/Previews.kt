package com.magichandy.mobile.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.IntifaceChoice
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ServerCheck
import com.magichandy.mobile.core.api.ServerCheckFailure
import com.magichandy.mobile.ui.AppContent
import com.magichandy.mobile.ui.components.AppFrame
import com.magichandy.mobile.ui.components.SheetHeader
import com.magichandy.mobile.ui.components.SheetOverlay
import com.magichandy.mobile.ui.devices.AddDeviceScreen
import com.magichandy.mobile.ui.devices.DeviceDetailScreen
import com.magichandy.mobile.ui.devices.DevicesScreen
import com.magichandy.mobile.ui.devices.VibratorSearch
import com.magichandy.mobile.ui.devices.VibratorSetup
import com.magichandy.mobile.ui.live.LiveScreen
import com.magichandy.mobile.ui.models.AddServerScreen
import com.magichandy.mobile.ui.models.ChatGptScreen
import com.magichandy.mobile.ui.models.ModelExits
import com.magichandy.mobile.ui.models.ModelsScreen
import com.magichandy.mobile.ui.models.OpenRouterScreen
import com.magichandy.mobile.ui.settings.AppSettingsScreen
import com.magichandy.mobile.ui.settings.ChatsPage
import com.magichandy.mobile.ui.settings.MemoryPage
import com.magichandy.mobile.ui.settings.MotionModePage
import com.magichandy.mobile.ui.settings.PersonaPage
import com.magichandy.mobile.ui.settings.RefusalFallbackPage
import com.magichandy.mobile.ui.settings.SystemAccess
import com.magichandy.mobile.ui.settings.SystemLinks
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import kotlin.time.Duration.Companion.milliseconds

/** Previews draw each screen in Steel Azure and Lowlight (plan §5.5). */
class PaletteProvider : PreviewParameterProvider<Palette> {
    override val values: Sequence<Palette> = sequenceOf(Palette.SteelAzure, Palette.Lowlight)
}

@Composable
private fun WholeApp(state: AppState, palette: Palette) {
    MagicHandyTheme(palette) { AppContent(state, onIntent = {}, onStop = {}) }
}

@Composable
private fun InFrame(palette: Palette, content: @Composable () -> Unit) {
    MagicHandyTheme(palette) { AppFrame(stopLabel = "Stop", onStop = {}) { content() } }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ChatPlaying(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    WholeApp(SampleStates.playing, palette)

@Preview(widthDp = 390, heightDp = 844, fontScale = 2f)
@Composable
private fun ChatPlayingLargeText() = WholeApp(SampleStates.playing, Palette.SteelAzure)

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ChatConnectionLost(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    WholeApp(SampleStates.connectionLost, palette)

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ChatStopReceipt(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    WholeApp(SampleStates.stopped, palette)

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun RunInterrupted(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    WholeApp(SampleStates.interrupted, palette)

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun BeforeYouStart(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    WholeApp(SampleStates.firstRun, palette)

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun Live(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        LiveScreen(
            SampleStates.playing,
            onIntent = {},
            onStepCoyote = { _, _, _ -> },
            onBack = {},
            onOpenAutopilotSettings = {},
        )
    }

/** Board V-CoyoteLive: a playing Coyote's channel strengths and their limits. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun LiveWithCoyote(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        LiveScreen(
            SampleStates.coyotePlaying,
            onIntent = {},
            onStepCoyote = { _, _, _ -> },
            onBack = {},
            onOpenAutopilotSettings = {},
        )
    }

/** Board V-CoyotePage: a disconnected Coyote, whose limits can change. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun CoyotePage(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        DeviceDetailScreen(
            device = SampleDevices.coyoteDisconnected,
            isBatteryUnrestricted = true,
            hasAcknowledgedCoyoteNotes = true,
            onIntent = {},
            onSetCoyoteLimits = { _, _, _ -> },
            onBack = {},
            onOpenBatterySettings = {},
        )
    }

/** Board V-AddVibrator: two vibrators found at the bridge, the first one picked. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun AddVibratorFound(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        val choices =
            listOf(IntifaceChoice(0, "Bullet vibrator"), IntifaceChoice(1, "Wand massager"))
        Column(
            Modifier.padding(horizontal = MagicHandyTheme.dimens.m),
            verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.cozy),
        ) {
            VibratorSetup(
                address = "127.0.0.1:12345",
                search = VibratorSearch.Found("127.0.0.1:12345", choices),
                selected = choices.first(),
                onAddressChange = {},
                onFind = {},
                onSelect = {},
                onConnect = { _, _ -> },
            )
        }
    }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun Devices(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        DevicesScreen(
            SampleStates.playing,
            system = SystemAccess(areNotificationsAllowed = true, isBatteryUnrestricted = true),
            onIntent = {},
            onOpenDevice = {},
            onAddDevice = {},
            onOpenNotificationSettings = {},
        )
    }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun VacuGlidePage(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        DeviceDetailScreen(
            device = SampleDevices.vacuGlide,
            isBatteryUnrestricted = true,
            hasAcknowledgedCoyoteNotes = true,
            onIntent = {},
            onSetCoyoteLimits = { _, _, _ -> },
            onBack = {},
            onOpenBatterySettings = {},
        )
    }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun AppSettings(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        AppSettingsScreen(
            state = SampleStates.playing,
            system = SystemAccess(areNotificationsAllowed = false, isBatteryUnrestricted = true),
            links = SystemLinks(openNotificationSettings = {}, openBatterySettings = {}),
            onIntent = {},
            onBack = {},
            onOpenModels = {},
        )
    }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun AddServer(@PreviewParameter(PaletteProvider::class) palette: Palette) {
    val found =
        ServerCheck.Found(
            address = "192.168.0.20:11434",
            provider = ProviderKind.Ollama,
            isEncrypted = false,
            models = listOf("gemma4:12b", "gemma4:e4b"),
            selectedModel = "gemma4:12b",
            suggested = CapabilityLevel.Intermediate,
            testedAt = CapabilityLevel.Intermediate,
            contextNeeded = 32_768,
            contextAllowed = 32_768,
            repliedIn = 1_900.milliseconds,
        )
    val state = SampleStates.playing.let { it.copy(models = it.models.copy(serverCheck = found)) }
    InFrame(palette) { AddServerScreen(state, onIntent = {}, onBack = {}) }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ChatIdle(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    WholeApp(SampleStates.idle, palette)

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ChatNoModel(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    WholeApp(SampleStates.noChatModel, palette)

@Preview(widthDp = 390, heightDp = 844, fontScale = 2f)
@Composable
private fun StopReceiptLargeText() = WholeApp(SampleStates.stopped, Palette.SteelAzure)

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun DevicesWithLoss(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        DevicesScreen(
            SampleStates.connectionLost,
            system = SystemAccess(areNotificationsAllowed = false, isBatteryUnrestricted = true),
            onIntent = {},
            onOpenDevice = {},
            onAddDevice = {},
            onOpenNotificationSettings = {},
        )
    }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun HandyPage(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        DeviceDetailScreen(
            device = SampleDevices.handy,
            isBatteryUnrestricted = false,
            hasAcknowledgedCoyoteNotes = true,
            onIntent = {},
            onSetCoyoteLimits = { _, _, _ -> },
            onBack = {},
            onOpenBatterySettings = {},
        )
    }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun AddHandy(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        AddDeviceScreen(
            DeviceFamily.Handy,
            SampleStates.playing,
            onIntent = {},
            findVibrators = { emptyList() },
            onBack = {},
            onDone = {},
        )
    }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun AddCoyote(@PreviewParameter(PaletteProvider::class) palette: Palette) {
    val state =
        SampleStates.playing.let {
            it.copy(devices = it.devices.copy(hasAcknowledgedCoyoteNotes = false))
        }
    InFrame(palette) {
        AddDeviceScreen(
            DeviceFamily.Coyote,
            state,
            onIntent = {},
            findVibrators = { emptyList() },
            onBack = {},
            onDone = {},
        )
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun Models(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) {
        ModelsScreen(
            SampleStates.playing,
            onIntent = {},
            exits = ModelExits(back = {}, addServer = {}, addOpenRouter = {}, addChatGpt = {}),
        )
    }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun AddServerFailed(@PreviewParameter(PaletteProvider::class) palette: Palette) {
    val failed = ServerCheck.Failed("192.168.0.20:11434", ServerCheckFailure.Unreachable)
    val state = SampleStates.playing.let { it.copy(models = it.models.copy(serverCheck = failed)) }
    InFrame(palette) { AddServerScreen(state, onIntent = {}, onBack = {}) }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun OpenRouter(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) { OpenRouterScreen(onIntent = {}, onBack = {}) }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ChatGpt(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) { ChatGptScreen(SampleStates.playing, onIntent = {}, onBack = {}) }

/** A sheet page over the chat it opens from, as the compact settings show it. */
@Composable
private fun SheetPreview(
    palette: Palette,
    title: String,
    page: @Composable () -> Unit,
) =
    InFrame(palette) {
        Box(Modifier.fillMaxSize()) {
            SheetOverlay(title = title, onDismiss = {}) {
                SheetHeader(title = title, onClose = {})
                page()
            }
        }
    }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun MotionModeSheet(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    SheetPreview(palette, "Motion mode") { MotionModePage(SampleStates.playing, onIntent = {}) }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun PersonaSheet(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    SheetPreview(palette, "Persona") { PersonaPage(SampleStates.playing, onIntent = {}) }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun MemorySheet(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    SheetPreview(palette, "Memory") { MemoryPage(SampleStates.withMemories, onIntent = {}) }

/** Board V-RefusalFallback: Off, or one of the servers on the local network. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun RefusalFallbackSheet(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    SheetPreview(palette, "If Terra refuses") {
        RefusalFallbackPage(SampleStates.playing, onSet = {})
    }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun ChatsSheet(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    SheetPreview(palette, "Chats") { ChatsPage(SampleStates.playing, onIntent = {}, onClose = {}) }
