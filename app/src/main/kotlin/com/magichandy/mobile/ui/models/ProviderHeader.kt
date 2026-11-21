package com.magichandy.mobile.ui.models

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.ui.components.PageHeader

/** Where a provider's setup page was opened from, which its Back names. */
enum class ProviderOrigin {
    /** Models, in App settings. */
    Models,

    /** Choose your AI, in setup or from Chat. */
    ChooseAi,
}

/**
 * Setup and Chat's Choose your AI bind a newly saved connection to Chat; Models leaves it alone.
 */
internal val ProviderOrigin.selectsForChat: Boolean
    get() = this == ProviderOrigin.ChooseAi

/** A provider page's heading, with Back to the page it was opened from. */
@Composable
internal fun ProviderHeader(title: String, origin: ProviderOrigin, onBack: () -> Unit) {
    val from =
        stringResource(
            when (origin) {
                ProviderOrigin.Models -> R.string.models_title
                ProviderOrigin.ChooseAi -> R.string.choose_ai_title
            }
        )
    PageHeader(
        title = title,
        backDescription =
            when (origin) {
                ProviderOrigin.Models -> stringResource(R.string.back_to_models)
                ProviderOrigin.ChooseAi -> stringResource(R.string.back_to_page, from)
            },
        onBack = onBack,
        breadcrumb = from,
    )
}
