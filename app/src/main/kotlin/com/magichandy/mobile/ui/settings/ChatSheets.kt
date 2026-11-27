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
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ReasoningChoice
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.SettingRow
import com.magichandy.mobile.ui.components.ToggleRow
import com.magichandy.mobile.ui.format.PersonaWords
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.models.reasoningName
import com.magichandy.mobile.ui.navigation.AppNavigator
import com.magichandy.mobile.ui.navigation.SheetPage
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Who replies (board V-Settings): choosing one returns to Settings, and Manage personas opens the
 * page where they're edited.
 */
@Composable
internal fun PersonaPage(
    state: AppState,
    nav: AppNavigator,
    onIntent: (UserIntent) -> Unit,
    exits: SheetExits,
) {
    val settings = state.settings
    ChoiceList(
        options = settings.personas,
        selected = settings.personas.firstOrNull { it.id == settings.persona.id },
        label = { it.name },
        description = { persona ->
            if (persona.isBuiltIn) {
                stringResource(
                    R.string.persona_line_built_in,
                    PersonaWords.level(persona.personality.explicitness),
                    PersonaWords.style(persona.personality.style),
                )
            } else {
                PersonaWords.line(persona.personality)
            }
        },
        onSelect = {
            onIntent(UserIntent.SetPersona(it.id))
            nav.sheetBack()
        },
    )
    SettingRow(
        label = stringResource(R.string.personas_manage),
        value = null,
        onClick = exits.openPersonas,
        isFirst = false,
        labelColor = MagicHandyTheme.colors.accent,
    )
    Hint(stringResource(R.string.persona_hint), Modifier.padding(top = MagicHandyTheme.dimens.xs))
}

@Composable
internal fun ChatModelPage(
    state: AppState,
    nav: AppNavigator,
    onIntent: (UserIntent) -> Unit,
    exits: SheetExits,
) {
    val models = state.models
    val connections = models.connections
    val chat = chatConnection(state)
    ChoiceList(
        options = connections,
        selected = chat,
        label = { it.name },
        description = { connectionLine(it) },
        onSelect = { onIntent(UserIntent.SetChatConnection(it.id)) },
    )
    if (chat != null && chat.provider == ProviderKind.OpenRouter) {
        SettingRow(
            label = stringResource(R.string.router_reasoning),
            value = reasoningName(ReasoningChoice(chat.isReasoningOn, chat.reasoningEffort)),
            onClick = { nav.pushSheet(SheetPage.Reasoning) },
            isFirst = false,
        )
    }
    // Only a model that runs online can refuse in a way a local server might not.
    if (chat != null && !chat.provider.isLocal) {
        SettingRow(
            label = stringResource(R.string.refusal_row, chat.name),
            value =
                connections.firstOrNull { it.id == models.refusalFallback }?.name
                    ?: stringResource(R.string.off),
            onClick = { nav.pushSheet(SheetPage.RefusalFallback) },
            isFirst = false,
        )
    }
    SettingRow(
        label = stringResource(R.string.chat_model_manage),
        value = null,
        onClick = exits.openModels,
        isFirst = connections.isEmpty(),
        labelColor = MagicHandyTheme.colors.accent,
    )
}

/**
 * Which server on the local network retries a message the online chat model refused (board
 * V-RefusalFallback). Off is the default, and the core accepts only local connections.
 */
@Composable
internal fun RefusalFallbackPage(state: AppState, onSet: (ConnectionId?) -> Unit) {
    val models = state.models
    val local = models.connections.filter { it.provider.isLocal }
    ChoiceList(
        options = listOf(null) + local,
        selected = local.firstOrNull { it.id == models.refusalFallback },
        label = { it?.name ?: stringResource(R.string.off) },
        description = { connection ->
            when {
                connection == null -> stringResource(R.string.refusal_off_desc)
                connection.isEncrypted -> connectionLine(connection)
                else ->
                    connectionLine(connection) +
                        stringResource(R.string.separator) +
                        stringResource(R.string.connection_not_encrypted)
            }
        },
        onSelect = { onSet(it?.id) },
    )
    Hint(
        stringResource(if (local.isEmpty()) R.string.refusal_none else R.string.refusal_note),
        Modifier.padding(top = MagicHandyTheme.dimens.xs),
    )
}

/** A connection's model and capability level, as the chat model choices show them. */
@Composable
private fun connectionLine(connection: ModelConnection): String =
    stringResource(
        R.string.settings_chat_model_value,
        connection.shortName,
        Words.capability(connection.capability),
    )

/** True for servers added by address on the local network; ChatGPT and OpenRouter run online. */
private val ProviderKind.isLocal: Boolean
    get() =
        this == ProviderKind.Ollama ||
            this == ProviderKind.LlamaCpp ||
            this == ProviderKind.OpenAiCompatible

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
    // The saved details themselves, newest first, so the person can see what will be recalled.
    settings.memories.forEachIndexed { index, memory ->
        CardRow(isFirst = index == 0) {
            Text(
                memory,
                style = MagicHandyTheme.type.body,
                modifier = Modifier.weight(1f).padding(vertical = MagicHandyTheme.dimens.s),
            )
        }
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
                    chat.title.ifBlank { stringResource(R.string.chats_new) },
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
