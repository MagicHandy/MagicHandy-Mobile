package com.magichandy.mobile.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ServerCheck
import com.magichandy.mobile.core.api.ServerCheckFailure
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.Segment
import com.magichandy.mobile.ui.components.Segmented
import com.magichandy.mobile.ui.components.StatusLabel
import com.magichandy.mobile.ui.components.TextInput
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** Add a server on your network (board V-AddServer): an address, a check, then a test. */
@Composable
fun AddServerScreen(
    state: AppState,
    onIntent: (UserIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val check = state.models.serverCheck
    var address by rememberSaveable {
        mutableStateOf((check as? ServerCheck.Found)?.address.orEmpty())
    }
    Page(modifier) {
        PageHeader(
            title = stringResource(R.string.add_server_title),
            backDescription = stringResource(R.string.back_to_models),
            onBack = onBack,
            breadcrumb = stringResource(R.string.models_title),
        )
        Text(
            stringResource(R.string.add_server_lead),
            style = MagicHandyTheme.type.small,
            color = MagicHandyTheme.colors.muted,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            TextInput(
                address,
                { address = it },
                stringResource(R.string.field_address),
                Modifier.weight(1f),
                placeholder = stringResource(R.string.field_address_hint),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                onImeAction = { onIntent(UserIntent.CheckServer(address.trim())) },
            )
            AppButton(
                stringResource(
                    if (check is ServerCheck.Checking) R.string.add_server_checking
                    else R.string.add_server_check
                ),
                { onIntent(UserIntent.CheckServer(address.trim())) },
                isEnabled = address.isNotBlank() && check !is ServerCheck.Checking,
                modifier = Modifier.padding(start = MagicHandyTheme.dimens.s),
            )
        }
        when (check) {
            is ServerCheck.Found -> FoundServer(check, onIntent, onBack)
            is ServerCheck.Failed -> Hint(failureText(check.cause))
            ServerCheck.Idle,
            is ServerCheck.Checking -> Unit
        }
    }
}

@Composable
private fun FoundServer(
    found: ServerCheck.Found,
    onIntent: (UserIntent) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MagicHandyTheme.colors
    var capability by rememberSaveable(found.selectedModel) { mutableStateOf(found.suggested) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusLabel(
            DotTone.Ok,
            stringResource(R.string.add_server_found, Words.provider(found.provider)),
        )
        if (!found.isEncrypted) {
            Text(
                stringResource(R.string.separator) +
                    stringResource(R.string.add_server_not_encrypted),
                style = MagicHandyTheme.type.caption,
                color = colors.warn,
            )
        }
    }
    Text(stringResource(R.string.add_server_model), style = MagicHandyTheme.type.small)
    ModelPicker(found.models, found.selectedModel) { onIntent(UserIntent.SelectServerModel(it)) }
    Text(stringResource(R.string.add_server_capability), style = MagicHandyTheme.type.small)
    val suggested = stringResource(R.string.add_server_suggested)
    Segmented(
        segments =
            CapabilityLevel.entries.map {
                Segment(
                    it,
                    Words.capability(it),
                    note = if (it == found.suggested) suggested else null,
                )
            },
        selected = capability,
        onSelect = { capability = it },
        minHeight = MagicHandyTheme.dimens.noteSegment,
    )
    val needed = stringResource(R.string.context_tokens, found.contextNeeded / TOKENS_PER_K)
    val testedAt = found.testedAt
    val allowed = found.contextAllowed
    Hint(
        if (testedAt != null && allowed != null) {
            stringResource(
                R.string.add_server_context_tested,
                found.selectedModel,
                Words.capability(testedAt),
                needed,
                stringResource(R.string.context_tokens, allowed / TOKENS_PER_K),
            )
        } else {
            stringResource(
                R.string.add_server_context,
                found.selectedModel,
                needed,
                Words.capability(capability),
            )
        }
    )
    found.repliedIn?.let {
        StatusLabel(DotTone.Ok, stringResource(R.string.add_server_ready, Words.seconds(it)))
    }
    val name =
        stringResource(R.string.add_server_name, Words.provider(found.provider), found.address)
    Row(
        horizontalArrangement = Arrangement.spacedBy(MagicHandyTheme.dimens.s),
        modifier = Modifier.padding(top = MagicHandyTheme.dimens.s),
    ) {
        AppButton(
            stringResource(R.string.add_server_test_again),
            { onIntent(UserIntent.CheckServer(found.address)) },
            modifier = Modifier.weight(1f),
        )
        AppButton(
            stringResource(R.string.add_server_save),
            {
                onIntent(
                    UserIntent.SaveServer(name, found.address, found.selectedModel, capability)
                )
                onBack()
            },
            kind = ButtonKind.Primary,
            modifier = Modifier.weight(1.6f),
        )
    }
}

@Composable
private fun ModelPicker(models: List<String>, selected: String, onSelect: (String) -> Unit) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.control
    var isOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(colors.well)
            .border(MagicHandyTheme.dimens.hairline, colors.fieldLine, shape)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.DropdownList) { isOpen = !isOpen }
                .padding(
                    horizontal = MagicHandyTheme.dimens.m,
                    vertical = MagicHandyTheme.dimens.roomy,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected, style = MagicHandyTheme.type.body, modifier = Modifier.weight(1f))
            Icon(
                if (isOpen) AppIcons.ChevronUp else AppIcons.ChevronDown,
                null,
                tint = colors.muted,
                modifier = Modifier.size(MagicHandyTheme.dimens.smallIcon),
            )
        }
        if (isOpen) {
            for (model in models) {
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = MagicHandyTheme.dimens.touch)
                        .selectable(selected = model == selected, role = Role.RadioButton) {
                            onSelect(model)
                            isOpen = false
                        }
                        .padding(
                            horizontal = MagicHandyTheme.dimens.m,
                            vertical = MagicHandyTheme.dimens.s,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        model,
                        style = MagicHandyTheme.type.body,
                        color = if (model == selected) colors.accent else colors.text,
                    )
                }
            }
        }
    }
}

@Composable
private fun failureText(cause: ServerCheckFailure): String =
    stringResource(
        when (cause) {
            ServerCheckFailure.InvalidAddress -> R.string.server_failure_invalid
            ServerCheckFailure.Unreachable -> R.string.server_failure_unreachable
            ServerCheckFailure.NotAModelServer -> R.string.server_failure_not_model_server
            ServerCheckFailure.NoModels -> R.string.server_failure_no_models
            ServerCheckFailure.NotPrivateAddress -> R.string.server_failure_not_private
        }
    )

private const val TOKENS_PER_K = 1024
