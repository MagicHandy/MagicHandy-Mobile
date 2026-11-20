package com.magichandy.mobile.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.CoyoteChannel
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceOutput
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.DeviceSummary
import com.magichandy.mobile.core.api.LimitKind
import com.magichandy.mobile.core.api.MotionSource
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StepDirection
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.LabelValue
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.StatusLabel
import com.magichandy.mobile.ui.components.StepperRow
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Live controls (board V-Live): the commanded figure, each device's output, the limits every source
 * obeys, the play actions and Autopilot.
 */
@Composable
fun LiveScreen(
    state: AppState,
    onIntent: (UserIntent) -> Unit,
    onStepCoyote: (device: DeviceId, channel: CoyoteChannel, increase: Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenAutopilotSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = MagicHandyTheme.dimens
    Column(
        modifier
            .fillMaxSize()
            .background(MagicHandyTheme.colors.chrome)
            .verticalScroll(rememberScrollState())
            .padding(start = dimens.m, end = dimens.m, bottom = dimens.s),
        verticalArrangement = Arrangement.spacedBy(dimens.s),
    ) {
        PageHeader(
            title = stringResource(R.string.live_title),
            backDescription = stringResource(R.string.back_to_chat),
            onBack = onBack,
        )
        Figure(state, onIntent)
        val inRun = state.devices.devices.filter { it.membership is RunMembership.InRun }
        if (inRun.isNotEmpty()) DeviceOutputs(inRun)
        if (state.run.phase != RunPhase.Idle) {
            for (coyote in inRun.filter { it.settings is DeviceSettings.Coyote }) {
                CoyoteStrength(coyote, state.run.phase, onStepCoyote)
            }
        }
        Limits(state, onIntent)
        PlayActions(state.run.phase, onIntent)
        AutopilotCard(state.autopilot, state.run.recentChanges, onIntent, onOpenAutopilotSettings)
    }
}

@Composable
private fun Figure(state: AppState, onIntent: (UserIntent) -> Unit) {
    val run = state.run
    if (run.phase == RunPhase.Idle) {
        Column(verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.s)) {
            Text(stringResource(R.string.live_idle), style = MagicHandyTheme.type.title)
            AppButton(
                stringResource(R.string.live_start_motion),
                { onIntent(UserIntent.StartMotion) },
                kind = ButtonKind.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = MagicHandyTheme.dimens.xxs),
    ) {
        Text(
            run.commandedPace?.let { Words.percent(it) } ?: "–",
            style = MagicHandyTheme.type.figure,
        )
        Spacer(Modifier.width(MagicHandyTheme.dimens.roomy))
        Column(verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.xs)) {
            Text(
                stringResource(R.string.live_pace_commanded),
                style = MagicHandyTheme.type.small,
                color = MagicHandyTheme.colors.muted,
            )
            val phaseWord =
                stringResource(
                    when (run.phase) {
                        RunPhase.Paused -> R.string.status_paused
                        RunPhase.Edging -> R.string.strip_edging
                        else -> R.string.status_playing
                    }
                )
            val line =
                when (run.source) {
                    MotionSource.Autopilot ->
                        stringResource(R.string.live_choosing_autopilot, phaseWord)
                    MotionSource.Chat -> stringResource(R.string.live_choosing_chat, phaseWord)
                    MotionSource.You -> stringResource(R.string.live_choosing_you, phaseWord)
                    null -> phaseWord
                }
            StatusLabel(
                tone = if (run.phase == RunPhase.Playing) DotTone.Ok else DotTone.Idle,
                text = line,
                color = MagicHandyTheme.colors.text,
            )
        }
    }
}

@Composable
private fun DeviceOutputs(devices: List<DeviceSummary>) {
    Card(
        contentPadding =
            PaddingValues(
                horizontal = MagicHandyTheme.dimens.m,
                vertical = MagicHandyTheme.dimens.snug,
            )
    ) {
        for (device in devices) {
            LabelValue(
                label = { Text(device.name, style = MagicHandyTheme.type.small) },
                value = {
                    Text(
                        outputText(device.output),
                        style =
                            MagicHandyTheme.type.mono.copy(
                                fontSize = MagicHandyTheme.type.small.fontSize
                            ),
                        color = MagicHandyTheme.colors.muted,
                        textAlign = TextAlign.End,
                    )
                },
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = MagicHandyTheme.dimens.readoutRow)
                        .padding(vertical = MagicHandyTheme.dimens.xxs),
            )
        }
    }
}

@Composable
private fun outputText(output: DeviceOutput?): String =
    when (output) {
        null -> Words.status(DeviceStatus.Connected)
        is DeviceOutput.Stroke -> {
            val range = stringResource(R.string.range, output.low.value, output.high.value)
            val requested = output.requestedPace
            if (requested != null && requested != output.pace) {
                stringResource(
                    R.string.live_device_stroke_asked,
                    Words.percent(output.pace),
                    Words.percent(requested),
                    range,
                )
            } else {
                stringResource(R.string.live_device_stroke, Words.percent(output.pace), range)
            }
        }
        is DeviceOutput.Level ->
            stringResource(
                if (output.followsLead) R.string.live_device_level_follows
                else R.string.live_device_level,
                Words.percent(output.level),
                output.max.value,
            )
        is DeviceOutput.Tempo ->
            stringResource(R.string.live_device_tempo, Words.percent(output.tempo))
        is DeviceOutput.Channels ->
            stringResource(R.string.live_device_channels, output.strengthA, output.strengthB)
    }

@Composable
private fun Limits(state: AppState, onIntent: (UserIntent) -> Unit) {
    val limits = state.run.limits
    Card(caption = stringResource(R.string.live_limits)) {
        val rows =
            listOf(
                Triple(
                    LimitKind.TopSpeed,
                    limits.topSpeed,
                    R.string.limit_lower_top_speed to R.string.limit_raise_top_speed,
                ),
                Triple(
                    LimitKind.StrokeLow,
                    limits.strokeLow,
                    R.string.limit_lower_stroke_low to R.string.limit_raise_stroke_low,
                ),
                Triple(
                    LimitKind.StrokeHigh,
                    limits.strokeHigh,
                    R.string.limit_lower_stroke_high to R.string.limit_raise_stroke_high,
                ),
            )
        rows.forEachIndexed { index, (kind, value, descriptions) ->
            StepperRow(
                label = Words.limit(kind),
                value = value.value.toString(),
                downDescription = stringResource(descriptions.first),
                upDescription = stringResource(descriptions.second),
                onDown = { onIntent(UserIntent.StepLimit(kind, StepDirection.Down)) },
                onUp = { onIntent(UserIntent.StepLimit(kind, StepDirection.Up)) },
                isFirst = index == 0,
            )
        }
    }
}

@Composable
private fun PlayActions(phase: RunPhase, onIntent: (UserIntent) -> Unit) {
    if (phase == RunPhase.Idle) return
    Row(horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.s)) {
        val isPaused = phase == RunPhase.Paused
        AppButton(
            stringResource(
                if (isPaused) R.string.live_resume_motion else R.string.live_pause_motion
            ),
            { onIntent(if (isPaused) UserIntent.ResumeMotion else UserIntent.PauseMotion) },
            kind = if (isPaused) ButtonKind.Start else ButtonKind.Outline,
            modifier = Modifier.weight(1f),
        )
        val isEdging = phase == RunPhase.Edging
        AppButton(
            stringResource(if (isEdging) R.string.live_edge_end else R.string.live_edge),
            { onIntent(UserIntent.SetEdge(!isEdging)) },
            kind = if (isEdging) ButtonKind.Selected else ButtonKind.Outline,
            isEnabled = phase == RunPhase.Playing || isEdging,
            modifier = Modifier.weight(1f),
        )
    }
}
