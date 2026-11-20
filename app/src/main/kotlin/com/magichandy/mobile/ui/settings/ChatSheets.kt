package com.magichandy.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.SettingRow
import com.magichandy.mobile.ui.components.ToggleRow
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme

@Composable
internal fun PersonaPage(state: AppState, onIntent: (UserIntent) -> Unit) {
    val settings = state.settings
    ChoiceList(
        options = settings.personas,
        selected = settings.persona,
        label = { it.name },
        description = { it.summary },
        onSelect = { onIntent(UserIntent.SetPersona(it.id)) },
    )
    Hint(stringResource(R.string.persona_hint), Modifier.padding(top = MagicHandyTheme.dimens.xs))
}

@Composable
internal fun ChatModelPage(state: AppState, onIntent: (UserIntent) -> Unit, exits: SheetExits) {
    val connections = state.models.connections
    ChoiceList(
        options = connections,
        selected = connections.firstOrNull { it.id == state.models.chatConnection },
        label = { it.name },
        description = {
            stringResource(
                R.string.settings_chat_model_value,
                it.shortName,
                Words.capability(it.capability),
            )
        },
        onSelect = { onIntent(UserIntent.SetChatConnection(it.id)) },
    )
    SettingRow(
        label = stringResource(R.string.chat_model_manage),
        value = null,
        onClick = exits.openModels,
        isFirst = connections.isEmpty(),
        labelColor = MagicHandyTheme.colors.accent,
    )
}

@Composable
internal fun MemoryPage(state: AppState, onIntent: (UserIntent) -> Unit) {
    val settings = state.settings
    ToggleRow(
        label = stringResource(R.string.memory_toggle),
        description = stringResource(R.string.memory_desc, settings.persona.name),
        isOn = settings.isMemoryOn,
        onChange = { onIntent(UserIntent.SetMemory(it)) },
        isFirst = true,
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = MagicHandyTheme.dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(
                R.plurals.memory_entries,
                settings.memoryEntries,
                settings.memoryEntries,
            ),
            style = MagicHandyTheme.type.small,
            color = MagicHandyTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        AppButton(
            stringResource(R.string.memory_clear),
            { onIntent(UserIntent.ClearMemory) },
            kind = ButtonKind.Destructive,
            isEnabled = settings.memoryEntries > 0,
            height = MagicHandyTheme.dimens.compactControl,
        )
    }
    Hint(stringResource(R.string.memory_clear_note))
}

@Composable
internal fun ChatsPage(state: AppState, onIntent: (UserIntent) -> Unit, onClose: () -> Unit) {
    AppButton(
        stringResource(R.string.chats_new),
        {
            onIntent(UserIntent.NewChat)
            onClose()
        },
        kind = ButtonKind.Primary,
        modifier = Modifier.fillMaxWidth().padding(bottom = MagicHandyTheme.dimens.s),
    )
    state.chat.chats.forEachIndexed { index, chat ->
        val isOpen = chat.id == state.chat.chatId
        CardRow(
            isFirst = index == 0,
            minHeight = MagicHandyTheme.dimens.sheetRow,
            modifier =
                Modifier.heightIn(min = MagicHandyTheme.dimens.sheetRow).clickable(
                    role = Role.Button
                ) {
                    onIntent(UserIntent.OpenChat(chat.id))
                    onClose()
                },
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    chat.title,
                    style = MagicHandyTheme.type.body,
                    fontWeight = if (isOpen) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    chat.personaName +
                        stringResource(R.string.separator) +
                        Words.clockTime(chat.lastActivity),
                    style = MagicHandyTheme.type.caption,
                    color = MagicHandyTheme.colors.muted,
                )
            }
            if (isOpen) {
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
