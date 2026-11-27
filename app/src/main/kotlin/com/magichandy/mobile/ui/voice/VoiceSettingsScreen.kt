package com.magichandy.mobile.ui.voice

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.Detector
import com.magichandy.mobile.core.api.DownloadPhase
import com.magichandy.mobile.core.api.MicSource
import com.magichandy.mobile.core.api.VoiceConfig
import com.magichandy.mobile.core.api.VoiceDownload
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.Caption
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.Segment
import com.magichandy.mobile.ui.components.Segmented
import com.magichandy.mobile.ui.components.SettingRow
import com.magichandy.mobile.ui.components.ToggleRow
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings › Voice (boards V-VoiceSettings, V-VoiceModelD and, in test builds, V-VoiceAdvanced):
 * the speech model, switching it, removing it, microphone access, and two lines on when listening
 * ends and which languages Stop works in.
 */
@Composable
fun VoiceSettingsScreen(voice: VoiceUi, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = MagicHandyTheme.dimens
    val state = voice.state
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = dimens.m, end = dimens.m, bottom = dimens.s),
        verticalArrangement = Arrangement.spacedBy(dimens.snug),
    ) {
        PageHeader(
            title = stringResource(R.string.voice_title),
            backDescription = stringResource(R.string.voice_back_to_app_settings),
            onBack = onBack,
            breadcrumb = stringResource(R.string.app_settings_title),
        )
        val installed = state.installed
        val switching = state.download?.model?.takeIf { installed != null && it != installed }
        Section(stringResource(R.string.voice_model)) {
            SettingRow(
                label =
                    if (switching != null && installed != null) modelName(installed)
                    else stringResource(R.string.voice_model),
                value =
                    when {
                        installed == null -> stringResource(R.string.voice_not_set_up)
                        switching != null ->
                            stringResource(
                                R.string.voice_model_value,
                                stringResource(R.string.voice_in_use),
                                fileSize(voice.downloadBytes(installed)),
                            )
                        else ->
                            stringResource(
                                R.string.voice_model_value,
                                modelName(installed),
                                fileSize(voice.downloadBytes(installed)),
                            )
                    },
                valueTone = if (installed != null) DotTone.Ok else null,
                onClick = {
                    when {
                        state.canChooseModel && voice.isOffered -> voice.openModelSettings()
                        installed == null && voice.isOffered -> voice.openSetup()
                    }
                },
                isFirst = true,
            )
            state.download?.let { DownloadRow(voice, it) }
            if (installed != null || state.download != null) RemoveRow(voice)
        }
        Section(stringResource(R.string.voice_microphone)) {
            val context = LocalContext.current
            var isAllowed by rememberSaveable { mutableStateOf(isMicrophoneAllowed(context)) }
            // Access can change in Android's settings while the app is away.
            LifecycleResumeEffect(context) {
                isAllowed = isMicrophoneAllowed(context)
                onPauseOrDispose {}
            }
            SettingRow(
                label = stringResource(R.string.voice_microphone_access),
                value =
                    stringResource(
                        if (isAllowed) R.string.voice_allowed else R.string.voice_not_allowed
                    ),
                valueTone = if (isAllowed) DotTone.Ok else DotTone.Warn,
                onClick = voice.openMicrophoneSettings,
                isFirst = true,
            )
        }
        if (state.showsHiddenSettings) AdvancedSection(voice)
        Help(
            listOfNotNull(
                if (switching != null && installed != null) {
                    stringResource(R.string.voice_switch_after, modelName(installed))
                } else {
                    null
                },
                stringResource(R.string.voice_help_listening),
                stringResource(
                    if (voice.isLanguageSupported) R.string.voice_help_languages
                    else R.string.voice_help_language_missing
                ),
            )
        )
    }
}

@Composable
private fun Section(title: String, rows: @Composable () -> Unit) {
    Caption(
        title,
        Modifier.padding(
            start = MagicHandyTheme.dimens.xxs,
            top = MagicHandyTheme.dimens.s,
            bottom = MagicHandyTheme.dimens.xxs,
        ),
    )
    Card(contentPadding = PaddingValues(horizontal = MagicHandyTheme.dimens.m)) { rows() }
}

/** The download's progress, or why it stopped, with Cancel and Retry or Resume. */
@Composable
private fun DownloadRow(voice: VoiceUi, download: VoiceDownload) {
    val installed = voice.state.installed
    val percent = (download.fraction * PERCENT).roundToInt()
    val total = fileSize(download.total)
    val problem = download.problem
    val text =
        when {
            download.phase == DownloadPhase.Failed && problem != null -> problemText(problem)
            download.phase == DownloadPhase.Paused ->
                stringResource(R.string.voice_switch_paused, percent, total)
            installed != null ->
                stringResource(
                    R.string.voice_switch_downloading,
                    percent,
                    total,
                    modelName(installed),
                )
            else -> stringResource(R.string.voice_first_downloading, percent, total)
        }
    CardRow(isFirst = false) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.xxs),
        ) {
            Text(modelName(download.model), style = MagicHandyTheme.type.body)
            Text(text, style = MagicHandyTheme.type.small, color = MagicHandyTheme.colors.muted)
            if (download.phase != DownloadPhase.Failed) {
                LinearProgressIndicator(
                    progress = { download.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = MagicHandyTheme.dimens.xs),
                    color = MagicHandyTheme.colors.accent,
                    trackColor = MagicHandyTheme.colors.strongLine,
                    drawStopIndicator = {},
                )
            }
        }
        if (download.phase != DownloadPhase.Running) {
            AppButton(
                stringResource(
                    if (download.phase == DownloadPhase.Failed) R.string.voice_retry
                    else R.string.voice_resume
                ),
                voice.controls::retryDownload,
                height = MagicHandyTheme.dimens.compactControl,
            )
        }
        AppButton(
            stringResource(R.string.action_cancel),
            voice.controls::cancelDownload,
            height = MagicHandyTheme.dimens.compactControl,
        )
    }
}

/** Removing needs a second tap, like Delete all chats. */
@Composable
private fun RemoveRow(voice: VoiceUi) {
    var isArmed by rememberSaveable { mutableStateOf(false) }
    SettingRow(
        label = stringResource(R.string.voice_remove),
        description =
            if (isArmed) {
                stringResource(
                    if (voice.state.listening != null) R.string.voice_remove_confirm_listening
                    else R.string.voice_remove_confirm
                )
            } else {
                null
            },
        value = null,
        labelColor = MagicHandyTheme.colors.dangerText,
        onClick = {
            if (isArmed) {
                voice.controls.remove()
                isArmed = false
            } else {
                isArmed = true
            }
        },
        isFirst = false,
    )
}

@Composable
private fun Help(lines: List<String>) {
    Column(
        Modifier.padding(
            start = MagicHandyTheme.dimens.xxs,
            end = MagicHandyTheme.dimens.xxs,
            top = MagicHandyTheme.dimens.s,
        ),
        verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.s),
    ) {
        lines.forEach {
            Text(it, style = MagicHandyTheme.type.small, color = MagicHandyTheme.colors.muted)
        }
    }
}

/**
 * The hidden settings (docs/voice-design.md §5.2), in test builds only, in English. Every one is
 * wired, with the tested defaults, so one can come back to releases by translating its row and
 * moving it into the saved settings.
 */
@Composable
private fun AdvancedSection(voice: VoiceUi) {
    val config = voice.state.config
    val tune = voice.controls::tune
    Row(
        Modifier.padding(
            start = MagicHandyTheme.dimens.xxs,
            top = MagicHandyTheme.dimens.s,
            bottom = MagicHandyTheme.dimens.xxs,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.snug),
    ) {
        Caption(stringResource(R.string.voice_advanced))
        Tag(
            stringResource(R.string.voice_advanced_tag).uppercase(),
            MagicHandyTheme.colors.warn,
            MagicHandyTheme.colors.warnLine,
        )
    }
    Text(
        stringResource(R.string.voice_advanced_warning),
        style = MagicHandyTheme.type.small,
        color = MagicHandyTheme.colors.warn,
        modifier = Modifier.padding(horizontal = MagicHandyTheme.dimens.xxs),
    )
    Card(contentPadding = PaddingValues(horizontal = MagicHandyTheme.dimens.m)) {
        LabelledChoice(stringResource(R.string.voice_audio), isFirst = true) {
            Segmented(
                segments =
                    listOf(
                        Segment(MicSource.Standard, stringResource(R.string.voice_audio_standard)),
                        Segment(MicSource.Speech, stringResource(R.string.voice_audio_speech)),
                        Segment(
                            MicSource.EchoCancel,
                            stringResource(R.string.voice_audio_echo_cancel),
                        ),
                        Segment(MicSource.Raw, stringResource(R.string.voice_audio_raw)),
                    ),
                selected = config.source,
                onSelect = { tune(config.copy(source = it)) },
            )
            Text(
                stringResource(R.string.voice_audio_talkback),
                style = MagicHandyTheme.type.small,
                color = MagicHandyTheme.colors.muted,
            )
        }
        ToggleRow(
            label = stringResource(R.string.voice_raise),
            isOn = config.raise,
            onChange = { tune(config.copy(raise = it)) },
            isFirst = false,
        )
        CardRow(isFirst = false) {
            Text(
                stringResource(R.string.voice_detector),
                style = MagicHandyTheme.type.body,
                modifier = Modifier.weight(1f),
            )
            Segmented(
                modifier = Modifier.width(DETECTOR_WIDTH),
                segments =
                    listOf(
                        Segment(Detector.Silero, stringResource(R.string.voice_detector_silero)),
                        Segment(Detector.Ten, stringResource(R.string.voice_detector_ten)),
                    ),
                selected = config.detector,
                onSelect = { tune(config.copy(detector = it)) },
            )
        }
        SettingRow(
            label = stringResource(R.string.voice_threshold),
            value = decimal(config.threshold),
            onClick = {
                tune(config.copy(threshold = next(VoiceConfig.thresholds, config.threshold)))
            },
            isFirst = false,
        )
        SettingRow(
            label = stringResource(R.string.voice_silence),
            value = stringResource(R.string.voice_seconds, decimal(config.silenceSeconds)),
            onClick = {
                tune(
                    config.copy(silenceSeconds = next(VoiceConfig.silences, config.silenceSeconds))
                )
            },
            isFirst = false,
        )
        ToggleRow(
            label = stringResource(R.string.voice_show_levels),
            isOn = config.showLevels,
            onChange = { tune(config.copy(showLevels = it)) },
            isFirst = false,
        )
        val context = LocalContext.current
        val isListening = voice.state.listening != null
        SettingRow(
            label = stringResource(R.string.voice_check_microphone),
            description =
                if (isListening) stringResource(R.string.voice_check_microphone_busy) else null,
            value = null,
            onClick = { if (!isListening) openVoiceTest(context) },
            isFirst = false,
        )
    }
}

@Composable
private fun LabelledChoice(label: String, isFirst: Boolean, choice: @Composable () -> Unit) {
    CardRow(isFirst = isFirst) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.xs),
        ) {
            Text(label, style = MagicHandyTheme.type.body)
            choice()
        }
    }
}

/** The next choice after [value], wrapping round, as a row steps through its values. */
private fun next(choices: List<Float>, value: Float): Float =
    choices[(choices.indexOf(value) + 1).mod(choices.size)]

private fun decimal(value: Float): String = "%.1f".format(Locale.ROOT, value)

/** The voice test page, in the test builds that have it. */
private fun openVoiceTest(context: Context) {
    try {
        context.startActivity(
            Intent().setClassName(context, "com.magichandy.mobile.voicelab.VoiceLabActivity")
        )
    } catch (_: ActivityNotFoundException) {
        // Release builds have no voice test; they never show this row.
    }
}

private const val PERCENT = 100

/** Wide enough for Silero and TEN side by side, as on board V-VoiceAdvanced. */
private val DETECTOR_WIDTH = 150.dp
