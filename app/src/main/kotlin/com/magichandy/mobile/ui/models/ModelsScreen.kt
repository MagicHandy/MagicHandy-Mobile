package com.magichandy.mobile.ui.models

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.FormIntent
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ReasoningChoice
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.FormRefusal
import com.magichandy.mobile.ui.components.AnchoredColumn
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.Caption
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.IconAction
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.SettingRow
import com.magichandy.mobile.ui.components.StatusLabel
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.rememberFormSubmission
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** Where the Models routes can go next. */
data class ModelExits(
    val back: () -> Unit,
    val addServer: () -> Unit,
    val addOpenRouter: () -> Unit,
    val addChatGpt: () -> Unit,
    /** Opens a saved OpenRouter connection's page (board V-OpenRouterSettings). */
    val openRouterConnection: (ConnectionId) -> Unit = {},
)

/** Models (plan §14.2 route 7): the two roles, saved connections, and adding a provider. */
@Composable
fun ModelsScreen(
    state: AppState,
    submit: suspend (form: FormIntent) -> FormResult,
    exits: ModelExits,
    modifier: Modifier = Modifier,
) {
    val models = state.models
    Page(modifier) {
        PageHeader(
            title = stringResource(R.string.models_title),
            backDescription = stringResource(R.string.back_to_app_settings),
            onBack = exits.back,
            isLarge = true,
        )
        Caption(stringResource(R.string.models_roles))
        Card(contentPadding = PaddingValues(horizontal = MagicHandyTheme.dimens.m)) {
            val chat = models.connections.firstOrNull { it.id == models.chatConnection }
            val planner = models.connections.firstOrNull { it.id == models.plannerConnection }
            RoleRow(
                stringResource(R.string.role_chat),
                chat?.roleName ?: stringResource(R.string.settings_none),
                true,
            )
            RoleRow(
                stringResource(R.string.role_autopilot),
                planner?.roleName ?: stringResource(R.string.planner_same_as_chat),
                false,
            )
        }
        Caption(stringResource(R.string.models_saved))
        if (models.connections.isEmpty()) {
            Hint(stringResource(R.string.models_empty))
        } else {
            // Removing waits for the core, which refuses while a run uses the connection.
            val removal = rememberFormSubmission(submit)
            Card(contentPadding = PaddingValues(horizontal = MagicHandyTheme.dimens.m)) {
                models.connections.forEachIndexed { index, connection ->
                    if (connection.provider == ProviderKind.OpenRouter) {
                        RouterRow(connection, index == 0) {
                            exits.openRouterConnection(connection.id)
                        }
                    } else {
                        ConnectionRow(connection, index == 0) {
                            removal.send(UserIntent.RemoveConnection(connection.id)) {}
                        }
                    }
                }
            }
            FormRefusal(removal)
        }
        Caption(stringResource(R.string.models_add))
        Card(contentPadding = PaddingValues(horizontal = MagicHandyTheme.dimens.m)) {
            SettingRow(
                label = stringResource(R.string.provider_chatgpt),
                description = stringResource(R.string.provider_chatgpt_desc),
                value = null,
                onClick = exits.addChatGpt,
                isFirst = true,
            )
            SettingRow(
                label = stringResource(R.string.provider_openrouter),
                description = stringResource(R.string.provider_openrouter_desc),
                value = null,
                onClick = exits.addOpenRouter,
                isFirst = false,
            )
            SettingRow(
                label = stringResource(R.string.provider_lan),
                description = stringResource(R.string.provider_lan_desc),
                value = null,
                onClick = exits.addServer,
                isFirst = false,
            )
        }
    }
}

@Composable
private fun RoleRow(role: String, value: String, isFirst: Boolean) {
    CardRow(isFirst = isFirst) {
        Text(role, style = MagicHandyTheme.type.body, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MagicHandyTheme.type.small,
            color = MagicHandyTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun ConnectionRow(connection: ModelConnection, isFirst: Boolean, onRemove: () -> Unit) {
    CardRow(isFirst = isFirst, minHeight = MagicHandyTheme.dimens.listRow) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.xxs),
        ) {
            Text(connection.name, style = MagicHandyTheme.type.bodyStrong)
            Text(
                stringResource(
                    R.string.settings_chat_model_value,
                    connection.model,
                    Words.capability(connection.capability),
                ),
                style = MagicHandyTheme.type.small,
                color = MagicHandyTheme.colors.muted,
            )
            ConnectionStatusLabel(connection)
        }
        IconAction(
            AppIcons.Close,
            stringResource(R.string.connection_remove, connection.name),
            onRemove,
            tint = MagicHandyTheme.colors.muted,
        )
    }
}

/**
 * A saved OpenRouter connection, named after its model, with its reasoning. It opens the
 * connection's page, where it can be changed or removed.
 */
@Composable
private fun RouterRow(connection: ModelConnection, isFirst: Boolean, onOpen: () -> Unit) {
    CardRow(
        isFirst = isFirst,
        minHeight = MagicHandyTheme.dimens.listRow,
        modifier = Modifier.clickable(role = Role.Button, onClick = onOpen),
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.xxs),
        ) {
            Text(connection.shortName, style = MagicHandyTheme.type.bodyStrong)
            Text(
                stringResource(
                    R.string.router_connection_line,
                    Words.capability(connection.capability),
                    reasoningName(
                        ReasoningChoice(connection.isReasoningOn, connection.reasoningEffort)
                    ),
                ),
                style = MagicHandyTheme.type.small,
                color = MagicHandyTheme.colors.muted,
            )
            ConnectionStatusLabel(connection)
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = MagicHandyTheme.colors.faint,
            modifier = Modifier.size(MagicHandyTheme.dimens.smallIcon),
        )
    }
}

/** Ready, or why not. A hosted connection that can't be reached is simply offline. */
@Composable
private fun ConnectionStatusLabel(connection: ModelConnection) {
    val isHosted =
        connection.provider == ProviderKind.ChatGpt ||
            connection.provider == ProviderKind.OpenRouter
    val (tone, word) =
        when (connection.status) {
            ConnectionStatus.Ready -> DotTone.Ok to R.string.connection_ready
            ConnectionStatus.Checking -> DotTone.Warn to R.string.connection_checking
            ConnectionStatus.Unreachable ->
                DotTone.Danger to
                    if (isHosted) R.string.connection_offline else R.string.connection_unreachable
            ConnectionStatus.NeedsSignIn -> DotTone.Warn to R.string.connection_needs_sign_in
        }
    val status =
        stringResource(word) +
            if (connection.isEncrypted) ""
            else
                stringResource(R.string.separator) +
                    stringResource(R.string.connection_not_encrypted)
    StatusLabel(tone, status)
}

/** How a role names its connection: an OpenRouter one by its model, the rest by their name. */
private val ModelConnection.roleName: String
    get() = if (provider == ProviderKind.OpenRouter) shortName else name

@Composable
internal fun Page(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val dimens = MagicHandyTheme.dimens
    AnchoredColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dimens.m, end = dimens.m, bottom = dimens.s),
        verticalArrangement = Arrangement.spacedBy(dimens.formGap),
        content = content,
    )
}
