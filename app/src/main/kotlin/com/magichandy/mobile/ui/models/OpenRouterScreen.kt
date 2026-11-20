package com.magichandy.mobile.ui.models

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.FormIntent
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.FormRefusal
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.Segment
import com.magichandy.mobile.ui.components.Segmented
import com.magichandy.mobile.ui.components.TextInput
import com.magichandy.mobile.ui.components.ToggleRow
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.rememberFormSubmission
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * OpenRouter setup: a key, a model, a capability level and reasoning. It closes once the core has
 * saved the connection, and keeps what was typed when the core can't.
 */
@Composable
fun OpenRouterScreen(
    submit: suspend (form: FormIntent) -> FormResult,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The key is held only while this screen is open, never in saved instance state.
    var key by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var capability by rememberSaveable { mutableStateOf(CapabilityLevel.Advanced) }
    var isReasoningOn by rememberSaveable { mutableStateOf(false) }
    val submission = rememberFormSubmission(submit)
    Page(modifier) {
        PageHeader(
            title = stringResource(R.string.provider_openrouter),
            backDescription = stringResource(R.string.back_to_models),
            onBack = onBack,
            breadcrumb = stringResource(R.string.models_title),
        )
        Text(
            stringResource(R.string.openrouter_lead),
            style = MagicHandyTheme.type.small,
            color = MagicHandyTheme.colors.muted,
        )
        TextInput(
            key,
            {
                key = it
                submission.edited()
            },
            stringResource(R.string.field_api_key),
            isSecret = true,
        )
        TextInput(
            model,
            {
                model = it
                submission.edited()
            },
            stringResource(R.string.field_model),
            placeholder = stringResource(R.string.field_model_hint),
        )
        Text(stringResource(R.string.add_server_capability), style = MagicHandyTheme.type.small)
        Segmented(
            segments = CapabilityLevel.entries.map { Segment(it, Words.capability(it)) },
            selected = capability,
            onSelect = { capability = it },
        )
        Card(contentPadding = PaddingValues(horizontal = MagicHandyTheme.dimens.m)) {
            ToggleRow(
                label = stringResource(R.string.openrouter_reasoning),
                description = stringResource(R.string.openrouter_reasoning_desc),
                isOn = isReasoningOn,
                onChange = { isReasoningOn = it },
                isFirst = true,
            )
        }
        FormRefusal(submission)
        AppButton(
            stringResource(
                if (submission.isSending) R.string.form_saving else R.string.action_save
            ),
            {
                submission.send(
                    UserIntent.SaveOpenRouter(
                        Secret(key.trim()),
                        model.trim(),
                        capability,
                        isReasoningOn,
                    ),
                    onBack,
                )
            },
            kind = ButtonKind.Primary,
            isEnabled = key.isNotBlank() && model.isNotBlank() && !submission.isSending,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
