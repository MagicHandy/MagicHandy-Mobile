package com.magichandy.mobile.ui.setup

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.magichandy.mobile.R
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.AppIcons
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.PushDown
import com.magichandy.mobile.ui.models.Page
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * Where Choose your AI can go next.
 *
 * @property skip setup's Skip for now; null outside setup.
 */
data class ChooseAiExits(
    val back: () -> Unit,
    val chatGpt: () -> Unit,
    val openRouter: () -> Unit,
    val server: () -> Unit,
    val skip: (() -> Unit)? = null,
)

/**
 * Choose your AI (board V-SetupModel): where Chat sends messages. In setup it's step 2, with Skip
 * for now; from Chat with no model it's a page of its own, and Back returns to Chat.
 */
@Composable
fun ChooseAiScreen(exits: ChooseAiExits, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.choose_ai_title)
    val lead = stringResource(R.string.choose_ai_lead)
    val skip = exits.skip
    if (skip != null) {
        SetupPage(
            step = 2,
            title = title,
            lead = lead,
            onBack = exits.back,
            backTo = stringResource(R.string.onboarding_title),
            modifier = modifier,
        ) {
            Providers(exits)
            PushDown()
            AppButton(
                stringResource(R.string.setup_skip),
                skip,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(
                stringResource(R.string.choose_ai_skip_note),
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Page(modifier) {
            PageHeader(
                title = title,
                backDescription =
                    stringResource(R.string.back_to_page, stringResource(R.string.tab_chat)),
                onBack = exits.back,
                isLarge = true,
            )
            Text(lead, style = MagicHandyTheme.type.small, color = MagicHandyTheme.colors.muted)
            Providers(exits)
        }
    }
}

@Composable
private fun ColumnScope.Providers(exits: ChooseAiExits) {
    ProviderCard(
        icon = AppIcons.Bubble,
        name = stringResource(R.string.provider_chatgpt),
        description = stringResource(R.string.choose_ai_chatgpt_desc),
        meta = stringResource(R.string.choose_ai_chatgpt_meta),
        onClick = exits.chatGpt,
    )
    ProviderCard(
        icon = AppIcons.Key,
        name = stringResource(R.string.provider_openrouter),
        description = stringResource(R.string.choose_ai_openrouter_desc),
        meta = stringResource(R.string.choose_ai_openrouter_meta),
        onClick = exits.openRouter,
    )
    ProviderCard(
        icon = AppIcons.Server,
        name = stringResource(R.string.provider_lan),
        description = stringResource(R.string.choose_ai_server_desc),
        meta = stringResource(R.string.choose_ai_server_meta),
        onClick = exits.server,
    )
}
