package com.magichandy.mobile.ui.models

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.RouterCatalog
import com.magichandy.mobile.core.api.RouterModel
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.Caption
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.CardRow
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.IconAction
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.TextInput
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import java.util.Locale

/**
 * Choosing an OpenRouter model (board V-OpenRouterModels): a search, two orders and one filter, the
 * models suggested for motion, and every model that works with MagicHandy through a provider that
 * keeps no data. A model that isn't listed can be entered by its ID; the core checks it when the
 * OpenRouter page saves.
 */
@Composable
internal fun RouterModelPicker(
    catalog: RouterCatalog,
    selected: String,
    onChoose: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var isByPrice by rememberSaveable { mutableStateOf(false) }
    var isOptionalOnly by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf("") }
    val dimens = MagicHandyTheme.dimens
    val models = (catalog as? RouterCatalog.Loaded)?.models.orEmpty()
    val isBrowsing = query.isBlank() && !isOptionalOnly && !isByPrice
    val suggested =
        if (isBrowsing) {
            SUGGESTED.mapNotNull { (id, note) -> models.firstOrNull { it.id == id }?.to(note) }
        } else emptyList()
    val shown =
        remember(models, query, isByPrice, isOptionalOnly, suggested) {
            val matches =
                models.matching(query, isOptionalOnly).filter { model ->
                    suggested.none { it.first == model }
                }
            if (isByPrice) matches.sortedBy { it.hundredRepliesMicros ?: Long.MAX_VALUE }
            else matches
        }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = dimens.m, end = dimens.m, bottom = dimens.l),
    ) {
        item {
            PageHeader(
                title = stringResource(R.string.router_pick_title),
                backDescription = stringResource(R.string.back_to_openrouter),
                onBack = onBack,
                breadcrumb = stringResource(R.string.provider_openrouter),
            )
        }
        item {
            Row(
                Modifier.padding(top = dimens.formGap),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextInput(
                    query,
                    { query = it },
                    stringResource(R.string.router_search),
                    Modifier.weight(1f),
                    placeholder = stringResource(R.string.router_search_hint),
                    isVerbatim = true,
                )
                if (query.isNotEmpty()) {
                    IconAction(
                        AppIcons.Close,
                        stringResource(R.string.router_search_clear),
                        { query = "" },
                    )
                }
            }
        }
        item {
            Row(
                Modifier.padding(top = dimens.s).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(dimens.xs),
            ) {
                PickerChip(R.string.router_sort_popular, !isByPrice, Role.RadioButton) {
                    isByPrice = false
                }
                PickerChip(R.string.router_sort_price, isByPrice, Role.RadioButton) {
                    isByPrice = true
                }
                PickerChip(R.string.router_filter_optional, isOptionalOnly, Role.Checkbox) {
                    isOptionalOnly = !isOptionalOnly
                }
            }
        }
        item { Hint(stringResource(R.string.router_price_note), Modifier.padding(top = dimens.xs)) }
        when (catalog) {
            RouterCatalog.Idle,
            RouterCatalog.Loading ->
                item {
                    Hint(stringResource(R.string.router_loading), Modifier.padding(top = dimens.m))
                }
            RouterCatalog.Failed ->
                item {
                    Column(
                        Modifier.padding(top = dimens.m),
                        verticalArrangement = Arrangement.spacedBy(dimens.s),
                    ) {
                        Hint(stringResource(R.string.router_unreachable))
                        AppButton(
                            stringResource(R.string.router_try_again),
                            onRetry,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            is RouterCatalog.Loaded -> {
                if (suggested.isNotEmpty()) {
                    item { Section(R.string.router_suggested_title) }
                    item {
                        Card(contentPadding = PaddingValues(horizontal = dimens.m)) {
                            suggested.forEachIndexed { index, (model, note) ->
                                ModelRow(model, note, model.id == selected, index == 0) {
                                    onChoose(model.id)
                                }
                            }
                        }
                    }
                }
                item { Section(R.string.router_all_title) }
                if (shown.isEmpty()) {
                    item { Hint(stringResource(R.string.router_no_match)) }
                }
                items(shown, key = { it.id }) { model ->
                    ModelRow(model, null, model.id == selected, model == shown.first()) {
                        onChoose(model.id)
                    }
                }
            }
        }
        item {
            Column(
                Modifier.padding(top = dimens.l),
                verticalArrangement = Arrangement.spacedBy(dimens.s),
            ) {
                TextInput(
                    manual,
                    { manual = it },
                    stringResource(R.string.router_manual),
                    placeholder = "author/model",
                    isVerbatim = true,
                )
                val id = manual.trim()
                AppButton(
                    stringResource(R.string.router_manual_use),
                    { onChoose(id) },
                    isEnabled = id.isNotEmpty() && id.none { it.isWhitespace() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(stringResource(R.string.router_list_note))
            }
        }
    }
}

/** One model: its name and ID, a note or reasoning tag, and its price for 100 replies. */
@Composable
private fun ModelRow(
    model: RouterModel,
    @StringRes note: Int?,
    isSelected: Boolean,
    isFirst: Boolean,
    onClick: () -> Unit,
) {
    val colors = MagicHandyTheme.colors
    val dimens = MagicHandyTheme.dimens
    val price = hundredReplies(model)
    val reasoning = model.reasoning
    val tag =
        when {
            reasoning == null -> null
            reasoning.isRequired -> stringResource(R.string.router_tag_required)
            else -> stringResource(R.string.router_tag_optional)
        }
    val spokenPrice = price?.let {
        if (model.isFree) it else stringResource(R.string.router_price, it)
    }
    val spoken = listOfNotNull(model.name, spokenPrice, tag).joinToString(", ")
    CardRow(
        isFirst = isFirst,
        minHeight = dimens.listRow,
        modifier =
            Modifier.selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dimens.xxs)) {
            Text(model.name, style = MagicHandyTheme.type.bodyStrong)
            Text(model.id, style = MagicHandyTheme.type.mono, color = colors.muted)
            if (note != null) {
                Text(stringResource(note), style = MagicHandyTheme.type.small, color = colors.muted)
            }
            if (tag != null) {
                if (reasoning?.isRequired == true) ModelTag(tag, colors.warn, colors.warnLine)
                else ModelTag(tag, colors.accent, colors.accent)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (price != null) Text(price, style = MagicHandyTheme.type.monoValue)
            if (isSelected) {
                Icon(
                    AppIcons.Check,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(dimens.icon),
                )
            }
        }
    }
}

/** A small outlined word under a model's name, such as Reasoning optional. */
@Composable
private fun ModelTag(text: String, color: Color, line: Color) {
    val dimens = MagicHandyTheme.dimens
    Text(
        text,
        style = MagicHandyTheme.type.overline.copy(letterSpacing = TextUnit.Unspecified),
        color = color,
        modifier =
            Modifier.padding(top = dimens.xxs)
                .border(dimens.hairline, line, MagicHandyTheme.shapes.small)
                .padding(horizontal = dimens.snug, vertical = dimens.hairline),
    )
}

/** A sort or filter choice, 48 dp tall. */
@Composable
private fun PickerChip(@StringRes text: Int, isSelected: Boolean, role: Role, onClick: () -> Unit) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.control
    Row(
        Modifier.heightIn(min = MagicHandyTheme.dimens.control)
            .clip(shape)
            .then(if (isSelected) Modifier.background(colors.accentContainer) else Modifier)
            .border(
                MagicHandyTheme.dimens.hairline,
                if (isSelected) colors.accent else colors.strongLine,
                shape,
            )
            .selectable(selected = isSelected, role = role, onClick = onClick)
            .padding(horizontal = MagicHandyTheme.dimens.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(text),
            style = MagicHandyTheme.type.label,
            color = if (isSelected) colors.text else colors.textSecondary,
        )
    }
}

@Composable
private fun Section(@StringRes text: Int) {
    Column {
        Spacer(Modifier.height(MagicHandyTheme.dimens.m))
        Caption(stringResource(text), Modifier.padding(bottom = MagicHandyTheme.dimens.xs))
    }
}

/** The models whose name or ID has every word of [query], leaving out ones that must reason. */
private fun List<RouterModel>.matching(query: String, isOptionalOnly: Boolean): List<RouterModel> {
    val words = query.lowercase(Locale.ROOT).split(' ').filter { it.isNotEmpty() }
    return filter { model ->
        (!isOptionalOnly || model.reasoning?.isRequired != true) &&
            words.all { model.name.lowercase(Locale.ROOT).contains(it) || model.id.contains(it) }
    }
}

/**
 * The models suggested for motion, with why. The desktop's tests found DeepSeek V4.1 Flash 1017
 * quick and cheap through OpenRouter, and GPT-6.1 Terra the most reliable, through ChatGPT.
 */
private val SUGGESTED =
    listOf(
        "deepseek/deepseek-v4.1-flash-1017" to R.string.router_tested_deepseek,
        "openai/gpt-6.1-terra" to R.string.router_tested_terra,
    )
