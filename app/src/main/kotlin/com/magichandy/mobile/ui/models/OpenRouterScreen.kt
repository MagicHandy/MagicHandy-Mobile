package com.magichandy.mobile.ui.models

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.FormIntent
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.ModelsState
import com.magichandy.mobile.core.api.ReasoningChoice
import com.magichandy.mobile.core.api.RouterCatalog
import com.magichandy.mobile.core.api.RouterModel
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.FormRefusal
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.InlineDialog
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.Segment
import com.magichandy.mobile.ui.components.Segmented
import com.magichandy.mobile.ui.components.TextInput
import com.magichandy.mobile.ui.format.Words
import com.magichandy.mobile.ui.rememberFormSubmission
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * OpenRouter (boards V-OpenRouterSetup and V-OpenRouterSettings): a key, a model from OpenRouter's
 * catalog and the reasoning it offers. Adding asks for the key and starts at Advanced; a saved
 * connection keeps its key unless a new one is typed, and adds its capability and Remove. The core
 * checks the key and the model before saving, and the page keeps what was typed when it can't.
 *
 * @param editing the saved connection being changed; null adds a new one. Once it's removed, the
 *   root leaves the page.
 */
@Composable
fun OpenRouterScreen(
    models: ModelsState,
    submit: suspend (form: FormIntent) -> FormResult,
    onIntent: (UserIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    origin: ProviderOrigin = ProviderOrigin.Models,
    editing: ModelConnection? = null,
    onSaved: () -> Unit = onBack,
) {
    LaunchedEffect(Unit) { onIntent(UserIntent.LoadOpenRouterModels) }
    // The key is held only while this page is open, never in saved instance state.
    var key by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(editing?.model.orEmpty()) }
    var isReasoningOn by rememberSaveable { mutableStateOf(editing?.isReasoningOn ?: false) }
    var level by rememberSaveable { mutableStateOf(editing?.reasoningEffort) }
    var capability by rememberSaveable {
        mutableStateOf(editing?.capability ?: CapabilityLevel.Advanced)
    }
    var isChoosing by rememberSaveable { mutableStateOf(false) }
    var isRemoving by rememberSaveable { mutableStateOf(false) }
    val submission = rememberFormSubmission(submit)
    val catalog = models.routerCatalog
    val listed = (catalog as? RouterCatalog.Loaded)?.models?.firstOrNull { it.id == model }
    val options = listed?.reasoning
    // Without the catalog, the saved choice stands; the core settles it when it saves.
    val reasoning = options?.settle(isReasoningOn, level) ?: ReasoningChoice(isReasoningOn, level)
    if (isChoosing) {
        BackHandler { isChoosing = false }
        RouterModelPicker(
            catalog = catalog,
            selected = model,
            onChoose = { chosen ->
                model = chosen
                isReasoningOn = false
                level = null
                isChoosing = false
                submission.edited()
            },
            onRetry = { onIntent(UserIntent.LoadOpenRouterModels) },
            onBack = { isChoosing = false },
            modifier = modifier,
        )
        return
    }
    Box(modifier.fillMaxSize()) {
        Page(Modifier) {
            if (editing == null) {
                ProviderHeader(stringResource(R.string.provider_openrouter), origin, onBack)
                Text(
                    stringResource(R.string.router_lead),
                    style = MagicHandyTheme.type.small,
                    color = MagicHandyTheme.colors.muted,
                )
            } else {
                PageHeader(
                    title = editing.shortName,
                    backDescription = stringResource(R.string.back_to_models),
                    onBack = onBack,
                    breadcrumb = stringResource(R.string.models_title),
                )
            }
            Column {
                TextInput(
                    key,
                    {
                        key = it
                        submission.edited()
                    },
                    stringResource(R.string.field_api_key),
                    placeholder = editing?.let { stringResource(R.string.router_key_saved) },
                    isSecret = true,
                )
                Hint(
                    stringResource(
                        if (editing == null) R.string.router_key_hint else R.string.router_key_keep
                    ),
                    Modifier.padding(top = MagicHandyTheme.dimens.xs),
                )
            }
            ModelField(listed, model) { isChoosing = true }
            CatalogStatus(catalog, isNeeded = model.isNotEmpty() && listed == null) {
                onIntent(UserIntent.LoadOpenRouterModels)
            }
            if (options != null && options.choices(reasoning).size > 1) {
                FieldLabel(R.string.router_reasoning)
                ReasoningChooser(options, reasoning) {
                    isReasoningOn = it.isOn
                    level = it.level
                    submission.edited()
                }
            }
            if (options != null) Hint(reasoningHint(options))
            if (editing != null) {
                FieldLabel(R.string.add_server_capability)
                Segmented(
                    segments = CapabilityLevel.entries.map { Segment(it, Words.capability(it)) },
                    selected = capability,
                    onSelect = {
                        capability = it
                        submission.edited()
                    },
                )
            }
            FormRefusal(submission, words = ::routerRefusal)
            AppButton(
                stringResource(
                    if (submission.isSending) R.string.form_saving else R.string.action_save
                ),
                {
                    val intent =
                        if (editing == null) {
                            openRouterSaveIntent(key, model, capability, reasoning, origin)
                        } else {
                            UserIntent.EditOpenRouter(
                                editing.id,
                                key.trim().takeIf { it.isNotEmpty() }?.let(::Secret),
                                model,
                                capability,
                                reasoning.isOn,
                                reasoning.level,
                            )
                        }
                    submission.send(intent, onSaved)
                },
                kind = ButtonKind.Primary,
                isEnabled =
                    (editing != null || key.isNotBlank()) &&
                        model.isNotBlank() &&
                        !submission.isSending,
                modifier = Modifier.fillMaxWidth(),
            )
            if (editing != null) {
                AppButton(
                    stringResource(R.string.router_remove),
                    { isRemoving = true },
                    kind = ButtonKind.Destructive,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Hint(stringResource(R.string.router_privacy))
        }
        if (isRemoving && editing != null) {
            val removal = rememberFormSubmission(submit)
            InlineDialog(
                title = stringResource(R.string.router_remove_title, editing.shortName),
                body = stringResource(R.string.router_remove_body),
                dismiss = stringResource(R.string.action_cancel),
                onDismiss = { isRemoving = false },
                confirm = stringResource(R.string.router_remove_confirm),
                // Once the connection is gone, the root leaves this page.
                onConfirm = { removal.send(UserIntent.RemoveConnection(editing.id)) {} },
                isDestructive = true,
                isConfirmEnabled = !removal.isSending,
                extra = { FormRefusal(removal) },
            )
        }
    }
}

/** The chosen model, or a prompt to choose one, opening the catalog. */
@Composable
private fun ModelField(listed: RouterModel?, id: String, onClick: () -> Unit) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val shape = MagicHandyTheme.shapes.control
    val name = listed?.name ?: id.substringAfterLast('/')
    val price = listed?.let { model ->
        if (model.isFree) stringResource(R.string.router_price_free)
        else hundredReplies(model)?.let { stringResource(R.string.router_price, it) }
    }
    val description =
        if (id.isEmpty()) stringResource(R.string.router_model_choose)
        else stringResource(R.string.router_model_change, name)
    Column {
        FieldLabel(R.string.field_model, Modifier.padding(bottom = dimens.snug))
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = dimens.control)
                .clip(shape)
                .background(colors.well)
                .border(dimens.hairline, colors.fieldLine, shape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(horizontal = dimens.m, vertical = dimens.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.cozy),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dimens.xxs)) {
                if (id.isEmpty()) {
                    Text(
                        stringResource(R.string.router_model_choose),
                        style = MagicHandyTheme.type.body,
                        color = colors.accent,
                    )
                } else {
                    Text(name, style = MagicHandyTheme.type.bodyStrong)
                    Text(id, style = MagicHandyTheme.type.mono, color = colors.muted)
                    if (price != null)
                        Text(price, style = MagicHandyTheme.type.small, color = colors.muted)
                }
            }
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = colors.faint,
                modifier = Modifier.size(dimens.smallIcon),
            )
        }
    }
}

/** Loading, or why the catalog didn't load, while the page needs it to show the model. */
@Composable
private fun CatalogStatus(catalog: RouterCatalog, isNeeded: Boolean, onRetry: () -> Unit) {
    if (!isNeeded) return
    when (catalog) {
        RouterCatalog.Idle,
        RouterCatalog.Loading -> Hint(stringResource(R.string.router_loading))
        RouterCatalog.Failed -> {
            Hint(stringResource(R.string.router_unreachable))
            AppButton(
                stringResource(R.string.router_try_again),
                onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is RouterCatalog.Loaded -> Unit
    }
}

@Composable
private fun FieldLabel(@StringRes text: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(text),
        style = MagicHandyTheme.type.small,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}

/** OpenRouter's own words for the refusals its pages can get. */
internal fun routerRefusal(reason: FormReason): Int? =
    when (reason) {
        FormReason.InvalidKey -> R.string.router_refused_key
        FormReason.InvalidModel -> R.string.router_refused_model
        FormReason.Unreachable -> R.string.router_refused_unreachable
        FormReason.Duplicate -> R.string.router_refused_duplicate
        else -> null
    }

internal fun openRouterSaveIntent(
    key: String,
    model: String,
    capability: CapabilityLevel,
    reasoning: ReasoningChoice,
    origin: ProviderOrigin,
): UserIntent.SaveOpenRouter =
    UserIntent.SaveOpenRouter(
        Secret(key.trim()),
        model.trim(),
        capability,
        reasoning.isOn,
        selectForChat = origin.selectsForChat,
        reasoningEffort = reasoning.level,
    )
