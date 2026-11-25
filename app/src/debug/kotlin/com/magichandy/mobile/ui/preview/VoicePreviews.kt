package com.magichandy.mobile.ui.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.VoiceModel
import com.magichandy.mobile.ui.AppContent
import com.magichandy.mobile.ui.components.AppFrame
import com.magichandy.mobile.ui.components.SheetHeader
import com.magichandy.mobile.ui.components.SheetOverlay
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import com.magichandy.mobile.ui.voice.ListeningChip
import com.magichandy.mobile.ui.voice.VoicePickerPage
import com.magichandy.mobile.ui.voice.VoiceSettingsScreen
import com.magichandy.mobile.ui.voice.VoiceSetupPage
import com.magichandy.mobile.ui.voice.VoiceUi
import com.magichandy.mobile.voice.DownloadPhase
import com.magichandy.mobile.voice.VoiceControls
import com.magichandy.mobile.voice.VoiceDownload
import com.magichandy.mobile.voice.VoiceEnd
import com.magichandy.mobile.voice.VoiceListening
import com.magichandy.mobile.voice.VoiceMeter
import com.magichandy.mobile.voice.VoiceNotice
import com.magichandy.mobile.voice.VoiceState

/** Voice input's screens (docs/voice-design.md; boards V-Voice*), each beside its board. */
private object VoiceSamples {
    /** A 12 GB phone with Compact on it, so the picker is offered. */
    val ready = VoiceState(isOffered = true, canChooseModel = true, installed = VoiceModel.Compact)

    val notSetUp = ready.copy(installed = null)

    val listening = ready.copy(listening = VoiceListening(VoiceModel.Compact, isReady = true))

    /** The meter while someone speaks, as on board V-VoiceListen. */
    val hearing =
        VoiceMeter(
            listOf(
                0.06f,
                0.08f,
                0.06f,
                0.08f,
                0.38f,
                0.66f,
                0.92f,
                0.75f,
                1f,
                0.83f,
                0.58f,
                0.79f,
                0.96f,
                0.71f,
                0.46f,
                0.33f,
                0.21f,
                0.12f,
            ),
            isHearing = true,
        )

    /** Switching to full precision, 41% in (board V-VoiceModelD). */
    val switching =
        ready.copy(
            chosen = VoiceModel.Full,
            download =
                VoiceDownload(
                    VoiceModel.Full,
                    done = 1_045_000_000,
                    total = 2_550_516_374,
                    phase = DownloadPhase.Running,
                ),
        )

    val firstDownload =
        notSetUp.copy(
            download =
                VoiceDownload(
                    VoiceModel.Compact,
                    done = 275_000_000,
                    total = 671_220_638,
                    phase = DownloadPhase.Running,
                    startsListening = true,
                )
        )

    val takenNotice = ready.copy(notice = VoiceNotice(VoiceEnd.MicTaken, VoiceModel.Compact))

    fun ui(state: VoiceState, meter: VoiceMeter = VoiceMeter()) =
        VoiceUi.of(VoiceControls.Fixed(state, meter))
}

@Composable
private fun WholeAppWithVoice(
    state: AppState,
    voice: VoiceState,
    palette: Palette,
    meter: VoiceMeter = VoiceMeter(),
) {
    MagicHandyTheme(palette) {
        AppContent(state, onIntent = {}, onStop = {}, voice = VoiceControls.Fixed(voice, meter))
    }
}

@Composable
private fun VoiceSheetPreview(
    palette: Palette,
    title: String,
    page: @Composable ColumnScope.() -> Unit,
) =
    InFrame(palette) {
        Box(Modifier.fillMaxSize()) {
            SheetOverlay(title = title, onDismiss = {}) {
                SheetHeader(title = title, onClose = {})
                page()
            }
        }
    }

/** Board V-VoiceIdle: nothing typed, so the microphone stands in for Send. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun VoiceIdle(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    WholeAppWithVoice(SampleStates.playing, VoiceSamples.ready, palette)

/** Boards V-VoiceSetup and V-VoiceModelB: the first tap, with Choose another model. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun VoiceSetupSheet(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    VoiceSheetPreview(palette, "Talk instead of typing") {
        VoiceSetupPage(VoiceSamples.ui(VoiceSamples.notSetUp), onClose = {})
    }

/** Board V-VoiceModelPick: the picker on first use. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun VoicePickerSheet(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    VoiceSheetPreview(palette, "Choose a speech model") {
        VoicePickerPage(
            VoiceSamples.ui(VoiceSamples.notSetUp),
            isFromSettings = false,
            onClose = {},
        )
    }

/** Board V-VoiceListen: listening and hearing you, the deck as it was. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun VoiceListening(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    WholeAppWithVoice(SampleStates.playing, VoiceSamples.listening, palette, VoiceSamples.hearing)

/** Board V-VoiceListen with large text: the meter gives way to the words. */
@Preview(widthDp = 390, heightDp = 844, fontScale = 2f)
@Composable
private fun VoiceListeningLargeText() =
    WholeAppWithVoice(
        SampleStates.playing,
        VoiceSamples.listening,
        Palette.SteelAzure,
        VoiceSamples.hearing,
    )

/** Board V-VoiceStop: a spoken Stop, and the receipt says listening ended. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun VoiceStopReceipt(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    WholeAppWithVoice(
        SampleStates.stopped,
        VoiceSamples.ready.copy(endedByStop = SampleStates.stopped.stopEpoch),
        palette,
    )

/** Board V-VoiceSettings: Settings › Voice while listening, with the chip above Stop. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun VoiceSettingsListening(@PreviewParameter(PaletteProvider::class) palette: Palette) {
    val voice = VoiceSamples.ui(VoiceSamples.listening)
    MagicHandyTheme(palette) {
        AppFrame(stopLabel = "Stop", onStop = {}, aboveTabs = { ListeningChip(voice) }) {
            VoiceSettingsScreen(voice, onBack = {})
        }
    }
}

/** Board V-VoiceModelSwitch: the picker from Settings, with full precision chosen. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun VoiceModelSheet(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    VoiceSheetPreview(palette, "Speech model") {
        VoicePickerPage(
            VoiceSamples.ui(VoiceSamples.ready),
            isFromSettings = true,
            onClose = {},
            initial = VoiceModel.Full,
        )
    }

/** Board V-VoiceModelD: switching to full precision while Compact keeps working. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun VoiceSwitching(@PreviewParameter(PaletteProvider::class) palette: Palette) =
    InFrame(palette) { VoiceSettingsScreen(VoiceSamples.ui(VoiceSamples.switching), onBack = {}) }

/** Board V-VoiceAdvanced: the hidden settings, as test builds show them. */
@Preview(widthDp = 390, heightDp = 1400)
@Composable
private fun VoiceAdvanced() =
    InFrame(Palette.SteelAzure) {
        VoiceSettingsScreen(
            VoiceSamples.ui(VoiceSamples.ready.copy(showsHiddenSettings = true)),
            onBack = {},
        )
    }

/** The first download, above the composer, while chat stays usable. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun VoiceDownloading() =
    WholeAppWithVoice(SampleStates.playing, VoiceSamples.firstDownload, Palette.SteelAzure)

/** Listening ended because another app took the microphone. */
@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun VoiceEnded() =
    WholeAppWithVoice(SampleStates.playing, VoiceSamples.takenNotice, Palette.SteelAzure)
