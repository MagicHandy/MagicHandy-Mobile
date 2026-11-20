package com.magichandy.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.ChangeFrequency
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.CoreCalls
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.SettingRow
import com.magichandy.mobile.ui.components.SheetHeader
import com.magichandy.mobile.ui.components.SheetOverlay
import com.magichandy.mobile.ui.devices.DeviceTypeList
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.navigation.AppNavigator
import com.magichandy.mobile.ui.navigation.SheetPage
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** What the sheets can open outside themselves. */
data class SheetExits(
    val openAppSettings: () -> Unit,
    val openModels: () -> Unit,
    val addDevice: (DeviceFamily) -> Unit,
)

/**
 * The in-window sheets (plan §14.2): the chat list, compact settings and its pages, and the
 * device-type chooser. Pages after the first in a stack show Back.
 */
@Composable
fun BoxScope.AppSheets(
    state: AppState,
    nav: AppNavigator,
    onIntent: (UserIntent) -> Unit,
    calls: CoreCalls,
    exits: SheetExits,
) {
    val page = nav.sheet ?: return
    val isNested = nav.sheets.size > 1
    val title = sheetTitle(page, state)
    SheetOverlay(title = title, onDismiss = nav::closeSheet, onBack = nav::sheetBack) {
        SheetHeader(
            title = title,
            onClose = nav::closeSheet,
            onBack = if (isNested) nav::sheetBack else null,
            // Back names the page it returns to, such as Autopilot from the planner model.
            backDescription =
                nav.sheets.getOrNull(nav.sheets.size - 2)?.let {
                    stringResource(R.string.back_to_page, sheetTitle(it, state))
                },
            subtitle =
                when (page) {
                    SheetPage.MotionMode -> motionModeSubtitle(state)
                    SheetPage.RefusalFallback -> refusalSubtitle(state)
                    else -> null
                },
        )
        when (page) {
            SheetPage.Chats -> ChatsPage(state, onIntent, nav::closeSheet)
            SheetPage.Settings -> SettingsHome(state, nav, exits)
            SheetPage.MotionMode -> MotionModePage(state, onIntent)
            SheetPage.Style -> StylePage(state, onIntent)
            SheetPage.Autopilot -> AutopilotPage(state, nav, onIntent)
            SheetPage.PlannerModel -> PlannerModelPage(state, onIntent)
            SheetPage.Persona -> PersonaPage(state, onIntent)
            SheetPage.ChatModel -> ChatModelPage(state, nav, onIntent, exits)
            SheetPage.RefusalFallback -> RefusalFallbackPage(state, calls.setRefusalFallback)
            SheetPage.Memory -> MemoryPage(state, onIntent)
            SheetPage.DeviceType -> DeviceTypeList(exits.addDevice)
        }
    }
}

@Composable
private fun sheetTitle(page: SheetPage, state: AppState): String =
    when (page) {
        SheetPage.Chats -> stringResource(R.string.chats_title)
        SheetPage.Settings -> stringResource(R.string.settings_title)
        SheetPage.MotionMode -> stringResource(R.string.settings_motion_mode)
        SheetPage.Style -> stringResource(R.string.settings_style)
        SheetPage.Autopilot -> stringResource(R.string.settings_autopilot)
        SheetPage.PlannerModel -> stringResource(R.string.autopilot_planner)
        SheetPage.Persona -> stringResource(R.string.settings_persona)
        SheetPage.ChatModel -> stringResource(R.string.settings_chat_model)
        SheetPage.RefusalFallback ->
            chatConnection(state)?.let { stringResource(R.string.refusal_row, it.name) }
                ?: stringResource(R.string.refusal_title_generic)
        SheetPage.Memory -> stringResource(R.string.settings_memory)
        SheetPage.DeviceType -> stringResource(R.string.device_type_title)
    }

@Composable
private fun refusalSubtitle(state: AppState): String? =
    chatConnection(state)?.let { stringResource(R.string.refusal_desc, it.name) }

@Composable
private fun motionModeSubtitle(state: AppState): String {
    val connection = chatConnection(state)
    val model =
        if (connection != null) {
            stringResource(
                R.string.settings_chat_model_value,
                connection.shortName,
                Words.capability(connection.capability),
            )
        } else {
            stringResource(R.string.settings_none)
        }
    return stringResource(R.string.motion_mode_subtitle, model)
}

internal fun chatConnection(state: AppState) =
    state.models.connections.firstOrNull { it.id == state.models.chatConnection }

@Composable
private fun SettingsHome(state: AppState, nav: AppNavigator, exits: SheetExits) {
    val settings = state.settings
    val autopilot = state.autopilot
    val connection = chatConnection(state)
    val rows =
        listOf(
            Triple(
                R.string.settings_motion_mode,
                Words.motionMode(settings.motionMode),
                SheetPage.MotionMode,
            ),
            Triple(R.string.settings_style, Words.style(settings.style), SheetPage.Style),
            Triple(
                R.string.settings_autopilot,
                stringResource(
                    R.string.settings_autopilot_value,
                    autopilot.changeFrequency.level,
                    ChangeFrequency.MAX,
                    Words.checkIns(autopilot.checkIns),
                ),
                SheetPage.Autopilot,
            ),
            Triple(R.string.settings_persona, settings.persona.name, SheetPage.Persona),
            Triple(
                R.string.settings_chat_model,
                if (connection != null) {
                    stringResource(
                        R.string.settings_chat_model_value,
                        connection.shortName,
                        Words.capability(connection.capability),
                    )
                } else {
                    stringResource(R.string.settings_none)
                },
                SheetPage.ChatModel,
            ),
            Triple(
                R.string.settings_memory,
                stringResource(if (settings.isMemoryOn) R.string.on else R.string.off),
                SheetPage.Memory,
            ),
        )
    rows.forEachIndexed { index, (label, value, page) ->
        SettingRow(
            label = stringResource(label),
            value = value,
            onClick = { nav.pushSheet(page) },
            isFirst = index == 0,
        )
    }
    SettingRow(
        label = stringResource(R.string.settings_app),
        value = null,
        onClick = exits.openAppSettings,
        isFirst = false,
        labelColor = MagicHandyTheme.colors.accent,
    )
}

/** A single-choice list with a check on the selected row. */
@Composable
internal fun <T> ChoiceList(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    description: @Composable (T) -> String?,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        options.forEachIndexed { index, option ->
            val isOn = option == selected
            CardRow(
                isFirst = index == 0,
                minHeight = MagicHandyTheme.dimens.sheetRow,
                modifier =
                    Modifier.selectable(selected = isOn, role = Role.RadioButton) {
                        onSelect(option)
                    },
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.tight),
                ) {
                    Text(
                        label(option),
                        style = MagicHandyTheme.type.body,
                        fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    description(option)?.let {
                        Text(
                            it,
                            style = MagicHandyTheme.type.small,
                            color = MagicHandyTheme.colors.muted,
                        )
                    }
                }
                if (isOn) {
                    Icon(
                        AppIcons.Check,
                        null,
                        tint = MagicHandyTheme.colors.accent,
                        modifier = Modifier.size(MagicHandyTheme.dimens.icon),
                    )
                }
            }
        }
    }
}
