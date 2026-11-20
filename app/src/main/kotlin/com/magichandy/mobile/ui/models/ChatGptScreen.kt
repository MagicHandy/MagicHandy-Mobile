package com.magichandy.mobile.ui.models

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.SignInFailure
import com.magichandy.mobile.core.api.SignInState
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.PageHeader
import com.magichandy.mobile.ui.components.StatusLabel
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** ChatGPT sign-in through the system browser. */
@Composable
fun ChatGptScreen(
    state: AppState,
    onIntent: (UserIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signIn = state.models.signIn
    Page(modifier) {
        PageHeader(
            title = stringResource(R.string.provider_chatgpt),
            backDescription = stringResource(R.string.back_to_models),
            onBack = onBack,
            breadcrumb = stringResource(R.string.models_title),
        )
        Text(
            stringResource(R.string.chatgpt_lead),
            style = MagicHandyTheme.type.small,
            color = MagicHandyTheme.colors.muted,
        )
        when (signIn) {
            SignInState.WaitingForBrowser -> {
                StatusLabel(DotTone.Warn, stringResource(R.string.chatgpt_waiting))
                AppButton(
                    stringResource(R.string.chatgpt_cancel),
                    { onIntent(UserIntent.CancelSignIn) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SignInState.Idle,
            is SignInState.Failed -> {
                if (signIn is SignInState.Failed) {
                    Hint(
                        stringResource(
                            when (signIn.cause) {
                                SignInFailure.Cancelled -> R.string.sign_in_cancelled
                                SignInFailure.Rejected -> R.string.sign_in_rejected
                                SignInFailure.NetworkError -> R.string.sign_in_network
                            }
                        )
                    )
                }
                AppButton(
                    stringResource(R.string.chatgpt_sign_in),
                    { onIntent(UserIntent.StartChatGptSignIn) },
                    kind = ButtonKind.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
