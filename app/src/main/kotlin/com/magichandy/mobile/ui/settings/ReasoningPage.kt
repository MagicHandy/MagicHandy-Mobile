package com.magichandy.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.FormIntent
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.RouterCatalog
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.FormRefusal
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.models.choices
import com.magichandy.mobile.ui.models.reasoningHint
import com.magichandy.mobile.ui.models.reasoningName
import com.magichandy.mobile.ui.models.routerRefusal
import com.magichandy.mobile.ui.rememberFormSubmission
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * The chat model's OpenRouter reasoning, from Chat model (board V-OpenRouterReasoning). It lists
 * the levels the model offers, and a change applies from the next message.
 */
@Composable
internal fun ReasoningPage(
    state: AppState,
    onIntent: (UserIntent) -> Unit,
    submit: suspend (form: FormIntent) -> FormResult,
) {
    val connection =
        chatConnection(state)?.takeIf { it.provider == ProviderKind.OpenRouter } ?: return
    LaunchedEffect(Unit) { onIntent(UserIntent.LoadOpenRouterModels) }
    val change = rememberFormSubmission(submit)
    val catalog = state.models.routerCatalog
    val listed =
        (catalog as? RouterCatalog.Loaded)?.models?.firstOrNull { it.id == connection.model }
    val options = listed?.reasoning
    val gap = Modifier.padding(top = MagicHandyTheme.dimens.xs)
    when {
        options != null -> {
            val selected = options.settle(connection.isReasoningOn, connection.reasoningEffort)
            Hint(reasoningHint(options))
            ChoiceList(
                options = options.choices(selected),
                selected = selected,
                label = { reasoningName(it, options.offered.isNotEmpty()) },
                description = { null },
                onSelect = {
                    change.send(
                        UserIntent.EditOpenRouter(
                            connection.id,
                            null,
                            connection.model,
                            connection.capability,
                            it.isOn,
                            it.level,
                        )
                    ) {}
                },
            )
            FormRefusal(change, gap, words = ::routerRefusal)
            Hint(stringResource(R.string.router_reasoning_applies), gap)
        }
        listed != null -> Hint(stringResource(R.string.router_reasoning_none))
        catalog == RouterCatalog.Failed ->
            Column {
                Hint(stringResource(R.string.router_unreachable))
                AppButton(
                    stringResource(R.string.router_try_again),
                    { onIntent(UserIntent.LoadOpenRouterModels) },
                    modifier = gap.fillMaxWidth(),
                )
            }
        catalog is RouterCatalog.Loaded -> Hint(stringResource(R.string.router_refused_model))
        else -> Hint(stringResource(R.string.router_loading))
    }
}
