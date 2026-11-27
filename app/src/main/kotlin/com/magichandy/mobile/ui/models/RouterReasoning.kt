package com.magichandy.mobile.ui.models

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.ReasoningChoice
import com.magichandy.mobile.core.api.ReasoningEffort
import com.magichandy.mobile.core.api.ReasoningOptions
import com.magichandy.mobile.core.api.RouterModel
import com.magichandy.mobile.ui.components.Card
import com.magichandy.mobile.ui.components.Segment
import com.magichandy.mobile.ui.components.Segmented
import com.magichandy.mobile.ui.settings.ChoiceList
import com.magichandy.mobile.ui.theme.MagicHandyTheme
import java.text.NumberFormat
import java.util.Currency

/** The choices a model offers (docs/openrouter-design.md §4.3), Off first when allowed. */
internal fun ReasoningOptions.choices(selected: ReasoningChoice? = null): List<ReasoningChoice> =
    buildList {
        if (!isRequired) add(ReasoningChoice(false, null))
        val automatic = ReasoningChoice(true, null)
        // Older connections may delegate effort to the provider even when it now lists levels.
        if (offered.isEmpty() || selected == automatic) add(automatic)
        offered.forEach { add(ReasoningChoice(true, it)) }
    }

/**
 * The reasoning choices a model offers: a segmented control for up to four, a list for more. The
 * hint under them says which replies soonest. A model with nothing to choose shows nothing.
 */
@Composable
internal fun ReasoningChooser(
    options: ReasoningOptions,
    selected: ReasoningChoice,
    onSelect: (ReasoningChoice) -> Unit,
) {
    val choices = options.choices(selected)
    if (choices.size < 2) return
    if (choices.size <= SEGMENTS) {
        Segmented(
            segments = choices.map { Segment(it, reasoningName(it, options.offered.isNotEmpty())) },
            selected = selected,
            onSelect = onSelect,
        )
    } else {
        Card(contentPadding = PaddingValues(horizontal = MagicHandyTheme.dimens.m)) {
            ChoiceList(
                options = choices,
                selected = selected,
                label = { reasoningName(it, options.offered.isNotEmpty()) },
                description = { null },
                onSelect = onSelect,
            )
        }
    }
}

/** The line under a model's reasoning choices. */
@Composable
internal fun reasoningHint(options: ReasoningOptions): String {
    val default = options.defaultLevel
    return when {
        options.isRequired && options.choices().size < 2 ->
            stringResource(R.string.router_reasoning_always)
        options.isRequired -> stringResource(R.string.router_reasoning_required)
        default != null -> stringResource(R.string.router_reasoning_default, levelName(default))
        else -> stringResource(R.string.router_reasoning_hint)
    }
}

/** Off, On, or a level's name, such as "Low". */
@Composable
internal fun reasoningName(choice: ReasoningChoice, hasLevels: Boolean = false): String {
    val level = choice.level
    return when {
        !choice.isOn -> stringResource(R.string.router_reasoning_off)
        level == null && hasLevels -> stringResource(R.string.router_model_default)
        level == null -> stringResource(R.string.router_reasoning_on)
        else -> levelName(level)
    }
}

@Composable
internal fun levelName(level: ReasoningEffort): String =
    stringResource(
        when (level) {
            ReasoningEffort.Minimal -> R.string.router_level_minimal
            ReasoningEffort.Low -> R.string.router_level_low
            ReasoningEffort.Medium -> R.string.router_level_medium
            ReasoningEffort.High -> R.string.router_level_high
            ReasoningEffort.ExtraHigh -> R.string.router_level_extra_high
            ReasoningEffort.Max -> R.string.router_level_max
        }
    )

/**
 * About what 100 replies cost, in US dollars as the phone's language writes them, such as "$0.04",
 * or Free. Null when OpenRouter doesn't say.
 */
@Composable
internal fun hundredReplies(model: RouterModel): String? {
    if (model.isFree) return stringResource(R.string.router_price_free)
    val micros = model.hundredRepliesMicros ?: return null
    val dollars =
        NumberFormat.getCurrencyInstance(LocalConfiguration.current.locales[0]).apply {
            currency = Currency.getInstance("USD")
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
    return if (micros < CENT_MICROS) "< " + dollars.format(0.01)
    else dollars.format(micros / 1_000_000.0)
}

/** More choices than this read better as a list than as a segmented control. */
private const val SEGMENTS = 4

private const val CENT_MICROS = 10_000L
