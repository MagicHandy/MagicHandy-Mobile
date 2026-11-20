package com.magichandy.mobile.ui.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AutopilotState
import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.RunEvent
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.Caption
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.LabelValue
import com.magichandy.mobile.ui.components.StatusDot
import com.magichandy.mobile.ui.components.topHairline
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme

@Composable
internal fun AutopilotCard(
    autopilot: AutopilotState,
    recent: List<RunEvent>,
    onIntent: (UserIntent) -> Unit,
    onOpenAutopilotSettings: () -> Unit,
) {
    val colors = MagicHandyTheme.colors
    Card(
        contentPadding =
            PaddingValues(
                start = MagicHandyTheme.dimens.m,
                end = MagicHandyTheme.dimens.m,
                top = MagicHandyTheme.dimens.cozy,
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = MagicHandyTheme.dimens.headingRow),
        ) {
            val tone =
                when (autopilot.status) {
                    AutopilotStatus.Active -> DotTone.Accent
                    AutopilotStatus.Held -> DotTone.Warn
                    AutopilotStatus.Paused,
                    AutopilotStatus.Off -> DotTone.Idle
                }
            StatusDot(tone)
            Spacer(Modifier.width(MagicHandyTheme.dimens.s))
            val next = autopilot.nextChangeIn?.takeIf { autopilot.status == AutopilotStatus.Active }
            LabelValue(
                label = {
                    Caption(
                        stringResource(
                            when (autopilot.status) {
                                AutopilotStatus.Active -> R.string.live_autopilot_active
                                AutopilotStatus.Paused -> R.string.live_autopilot_paused
                                AutopilotStatus.Held -> R.string.live_autopilot_held
                                AutopilotStatus.Off -> R.string.live_autopilot_off
                            }
                        ),
                        Modifier.semantics { heading() },
                    )
                },
                value = {
                    if (next != null) {
                        Text(
                            stringResource(R.string.live_next_check, Words.countdown(next)),
                            style = MagicHandyTheme.type.mono,
                            color = colors.muted,
                            textAlign = TextAlign.End,
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
        autopilot.finishTarget?.let { target ->
            val remaining = target.remaining
            Text(
                if (remaining != null) {
                    stringResource(R.string.live_finish_target, Words.minutes(remaining))
                } else {
                    stringResource(R.string.live_finish_target_set, Words.minutes(target.total))
                },
                style = MagicHandyTheme.type.small,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = MagicHandyTheme.dimens.xs),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.s),
            modifier =
                Modifier.padding(
                    top = MagicHandyTheme.dimens.cozy,
                    bottom = MagicHandyTheme.dimens.cozy,
                ),
        ) {
            when (autopilot.status) {
                AutopilotStatus.Off ->
                    AppButton(
                        stringResource(R.string.live_start_autopilot),
                        { onIntent(UserIntent.StartAutopilot) },
                        modifier = Modifier.weight(1f),
                    )
                AutopilotStatus.Paused -> {
                    AppButton(
                        stringResource(R.string.live_resume_autopilot),
                        { onIntent(UserIntent.ResumeAutopilot) },
                        modifier = Modifier.weight(1f),
                    )
                    TurnOff(onIntent, Modifier.weight(1f))
                }
                AutopilotStatus.Active,
                AutopilotStatus.Held -> {
                    AppButton(
                        stringResource(R.string.live_pause_autopilot),
                        { onIntent(UserIntent.PauseAutopilot) },
                        modifier = Modifier.weight(1f),
                    )
                    TurnOff(onIntent, Modifier.weight(1f))
                }
            }
        }
        RecentChanges(recent)
        Row(
            Modifier.fillMaxWidth()
                .topHairline(colors.line)
                .heightIn(min = MagicHandyTheme.dimens.touch)
                .clickable(role = Role.Button, onClick = onOpenAutopilotSettings),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.live_autopilot_settings),
                style =
                    MagicHandyTheme.type.label.copy(fontSize = MagicHandyTheme.type.small.fontSize),
                color = colors.accent,
                modifier = Modifier.weight(1f),
            )
            Icon(
                AppIcons.ChevronRight,
                null,
                tint = colors.accent,
                modifier = Modifier.size(MagicHandyTheme.dimens.inlineIcon),
            )
        }
    }
}

@Composable
private fun TurnOff(onIntent: (UserIntent) -> Unit, modifier: Modifier) {
    AppButton(
        stringResource(R.string.live_turn_off),
        { onIntent(UserIntent.TurnOffAutopilot) },
        modifier = modifier,
    )
}

/** Collapsed, it shows the latest change; open, every change in this run (board V-Live). */
@Composable
private fun RecentChanges(recent: List<RunEvent>) {
    val colors = MagicHandyTheme.colors
    var isOpen by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.live_recent_changes)
    Column(
        Modifier.fillMaxWidth()
            .topHairline(colors.line)
            .heightIn(min = MagicHandyTheme.dimens.touch)
            .clickable(role = Role.Button, onClickLabel = label) { isOpen = !isOpen }
            .padding(vertical = MagicHandyTheme.dimens.s),
        verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.snug),
    ) {
        val shown = if (isOpen) recent else recent.take(1)
        if (shown.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = MagicHandyTheme.dimens.eventRow),
            ) {
                Text(label, style = MagicHandyTheme.type.caption, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.live_recent_none),
                    style = MagicHandyTheme.type.caption,
                    color = colors.muted,
                )
            }
        }
        shown.forEachIndexed { index, event ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = MagicHandyTheme.dimens.eventRow),
            ) {
                Text(
                    Words.clockTime(event.at),
                    style = MagicHandyTheme.type.mono,
                    color = colors.muted,
                    modifier = Modifier.padding(end = MagicHandyTheme.dimens.s),
                )
                Text(
                    eventText(event),
                    style = MagicHandyTheme.type.caption,
                    modifier = Modifier.weight(1f),
                )
                if (index == 0) {
                    Icon(
                        if (isOpen) AppIcons.ChevronUp else AppIcons.ChevronDown,
                        contentDescription = null,
                        tint = colors.muted,
                        modifier = Modifier.size(MagicHandyTheme.dimens.inlineIcon),
                    )
                }
            }
        }
    }
}

@Composable
private fun eventText(event: RunEvent): String {
    val requested = event.requested
    val limitedBy = event.limitedBy
    return if (requested != null && limitedBy != null) {
        stringResource(
            R.string.recent_limited,
            requested,
            event.commanded,
            Words.limitInline(limitedBy),
        )
    } else {
        stringResource(R.string.recent_commanded, event.commanded, Words.source(event.source))
    }
}
