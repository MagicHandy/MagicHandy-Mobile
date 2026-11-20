package com.magichandy.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.ChangeFrequency
import com.magichandy.mobile.core.api.CheckIns
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.MotionStyle
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.Segment
import com.magichandy.mobile.ui.components.Segmented
import com.magichandy.mobile.ui.components.SettingRow
import com.magichandy.mobile.ui.components.StepperRow
import com.magichandy.mobile.ui.components.topHairline
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.navigation.AppNavigator
import com.magichandy.mobile.ui.navigation.SheetPage
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@Composable
internal fun MotionModePage(state: AppState, onIntent: (UserIntent) -> Unit) {
    val settings = state.settings
    ChoiceList(
        options = settings.availableModes,
        selected = settings.motionMode,
        label = { Words.motionMode(it) },
        description = { Words.motionModeDescription(it) },
        onSelect = { onIntent(UserIntent.SetMotionMode(it)) },
    )
    Hint(
        stringResource(R.string.motion_mode_note),
        Modifier.padding(top = MagicHandyTheme.dimens.xs),
    )
}

@Composable
internal fun StylePage(state: AppState, onIntent: (UserIntent) -> Unit) {
    ChoiceList(
        options = MotionStyle.entries,
        selected = state.settings.style,
        label = { Words.style(it) },
        description = { null },
        onSelect = { onIntent(UserIntent.SetStyle(it)) },
    )
    Hint(stringResource(R.string.style_hint), Modifier.padding(top = MagicHandyTheme.dimens.xs))
}

@Composable
internal fun AutopilotPage(state: AppState, nav: AppNavigator, onIntent: (UserIntent) -> Unit) {
    val autopilot = state.autopilot
    val level = autopilot.changeFrequency.level
    Block(isFirst = true) {
        StepperRow(
            label = stringResource(R.string.autopilot_frequency),
            value = stringResource(R.string.autopilot_frequency_value, level, ChangeFrequency.MAX),
            downDescription = stringResource(R.string.autopilot_less_often),
            upDescription = stringResource(R.string.autopilot_more_often),
            onDown = {
                if (level > ChangeFrequency.MIN) {
                    onIntent(UserIntent.SetChangeFrequency(ChangeFrequency(level - 1)))
                }
            },
            onUp = {
                if (level < ChangeFrequency.MAX) {
                    onIntent(UserIntent.SetChangeFrequency(ChangeFrequency(level + 1)))
                }
            },
            isFirst = true,
        )
        Hint(stringResource(R.string.autopilot_frequency_hint))
    }
    Block(isFirst = false) {
        Text(stringResource(R.string.autopilot_check_ins), style = MagicHandyTheme.type.body)
        Segmented(
            segments = CheckIns.entries.map { Segment(it, Words.checkIns(it)) },
            selected = autopilot.checkIns,
            onSelect = { onIntent(UserIntent.SetCheckIns(it)) },
        )
        Hint(stringResource(R.string.autopilot_check_ins_hint, state.settings.persona.name))
    }
    Block(isFirst = false) { FinishTarget(autopilot.finishTarget?.total, onIntent) }
    val planner = state.models.connections.firstOrNull { it.id == state.models.plannerConnection }
    val chat = chatConnection(state)
    SettingRow(
        label = stringResource(R.string.autopilot_planner),
        value =
            planner?.shortName
                ?: stringResource(
                    R.string.planner_same_as_chat_value,
                    chat?.shortName ?: stringResource(R.string.settings_none),
                ),
        onClick = { nav.pushSheet(SheetPage.PlannerModel) },
        isFirst = false,
    )
}

@Composable
private fun FinishTarget(total: Duration?, onIntent: (UserIntent) -> Unit) {
    val presets = listOf(10.minutes, 20.minutes, 30.minutes)
    val isMore = total != null && total !in presets
    Text(stringResource(R.string.autopilot_finish), style = MagicHandyTheme.type.body)
    val more = stringResource(R.string.finish_more)
    Segmented(
        segments =
            listOf(Segment<String>(OFF, stringResource(R.string.off))) +
                presets.map { Segment(it.inWholeMinutes.toString(), Words.minutes(it)) } +
                Segment(MORE, more),
        selected =
            when {
                total == null -> OFF
                isMore -> MORE
                else -> total.inWholeMinutes.toString()
            },
        onSelect = { choice ->
            onIntent(
                UserIntent.SetFinishTarget(
                    when (choice) {
                        OFF -> null
                        MORE -> MORE_DEFAULT
                        else -> choice.toInt().minutes
                    }
                )
            )
        },
    )
    if (total != null && isMore) {
        StepperRow(
            label = stringResource(R.string.autopilot_finish),
            value = Words.minutes(total),
            downDescription = stringResource(R.string.finish_shorter),
            upDescription = stringResource(R.string.finish_longer),
            onDown = { onIntent(UserIntent.SetFinishTarget(maxOf(MORE_MIN, total - MORE_STEP))) },
            onUp = { onIntent(UserIntent.SetFinishTarget(minOf(MORE_MAX, total + MORE_STEP))) },
            isFirst = true,
        )
    }
    Hint(stringResource(R.string.autopilot_finish_hint))
}

@Composable
private fun Block(isFirst: Boolean, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (isFirst) Modifier else Modifier.topHairline(MagicHandyTheme.colors.line))
            .padding(vertical = MagicHandyTheme.dimens.s),
        verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.s),
    ) {
        content()
    }
}

@Composable
internal fun PlannerModelPage(state: AppState, onIntent: (UserIntent) -> Unit) {
    val options = listOf<ConnectionId?>(null) + state.models.connections.map { it.id }
    ChoiceList(
        options = options,
        selected = state.models.plannerConnection,
        label = { id ->
            if (id == null) {
                stringResource(R.string.planner_same_as_chat)
            } else {
                state.models.connections.first { it.id == id }.name
            }
        },
        description = { id ->
            state.models.connections
                .firstOrNull { it.id == id }
                ?.let {
                    stringResource(
                        R.string.settings_chat_model_value,
                        it.shortName,
                        Words.capability(it.capability),
                    )
                }
        },
        onSelect = { onIntent(UserIntent.SetPlannerConnection(it)) },
    )
    Hint(stringResource(R.string.planner_hint), Modifier.padding(top = MagicHandyTheme.dimens.xs))
}

private const val OFF = "off"
private const val MORE = "more"
private val MORE_DEFAULT = 45.minutes
private val MORE_STEP = 5.minutes
private val MORE_MIN = 35.minutes
private val MORE_MAX = 120.minutes
