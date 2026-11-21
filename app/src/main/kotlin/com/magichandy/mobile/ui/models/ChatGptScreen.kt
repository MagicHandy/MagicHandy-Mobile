package com.magichandy.mobile.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.SignInFailure
import com.magichandy.mobile.core.api.SignInNetworkIssue
import com.magichandy.mobile.core.api.SignInStage
import com.magichandy.mobile.core.api.SignInState
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.ui.components.AppButton
import com.magichandy.mobile.ui.components.ButtonKind
import com.magichandy.mobile.ui.components.DotTone
import com.magichandy.mobile.ui.components.Hint
import com.magichandy.mobile.ui.components.StatusLabel
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/** Opens the system browser for ChatGPT and calls [onSaved] once the connection is saved. */
@Composable
fun ChatGptScreen(
    state: AppState,
    onIntent: (UserIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    origin: ProviderOrigin = ProviderOrigin.Models,
    onSaved: () -> Unit = onBack,
) {
    val signIn = state.models.signIn
    var requestedHere by rememberSaveable {
        mutableStateOf(signIn == SignInState.WaitingForBrowser)
    }
    val back = {
        if (signIn == SignInState.WaitingForBrowser) onIntent(UserIntent.CancelSignIn)
        onBack()
    }
    BackHandler(enabled = signIn == SignInState.WaitingForBrowser, onBack = back)
    // A returning account keeps its original connection ID. Completion follows the durable save.
    val completed = state.models.completedSignIns
    var before by rememberSaveable { mutableLongStateOf(completed) }
    LaunchedEffect(completed) {
        if (completed > before) onSaved()
        before = completed
    }
    Page(modifier) {
        ProviderHeader(stringResource(R.string.provider_chatgpt), origin, back)
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
                if (signIn is SignInState.Failed && requestedHere) {
                    Hint(signInError(signIn))
                }
                AppButton(
                    stringResource(R.string.chatgpt_sign_in),
                    {
                        requestedHere = true
                        onIntent(chatGptSignInIntent(origin))
                    },
                    kind = ButtonKind.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun signInError(failure: SignInState.Failed): String {
    val message =
        when (failure.cause) {
            SignInFailure.Cancelled -> R.string.sign_in_cancelled
            SignInFailure.StorageUnavailable ->
                if (failure.stage == SignInStage.SavingConnection) R.string.sign_in_save_connection
                else R.string.sign_in_storage
            SignInFailure.Rejected ->
                when (failure.stage) {
                    SignInStage.IdentityVerification ->
                        if (failure.httpStatus != null) R.string.sign_in_keys_unavailable
                        else R.string.sign_in_invalid_identity
                    SignInStage.CodeExchange -> R.string.sign_in_exchange_rejected
                    else -> R.string.sign_in_rejected
                }
            SignInFailure.NetworkError ->
                when (failure.stage) {
                    SignInStage.CodeExchange -> R.string.sign_in_exchange_unavailable
                    SignInStage.IdentityVerification -> R.string.sign_in_keys_unavailable
                    SignInStage.BrowserCallback -> R.string.sign_in_callback_unavailable
                    else -> R.string.sign_in_network
                }
        }
    val text = stringResource(message)
    val detail =
        failure.httpStatus?.let { stringResource(R.string.http_status, it.toString()) }
            ?: when (failure.networkIssue) {
                SignInNetworkIssue.NameResolution -> "DNS"
                SignInNetworkIssue.SecureConnection -> "TLS"
                SignInNetworkIssue.Timeout -> stringResource(R.string.sign_in_timeout)
                SignInNetworkIssue.ConnectionLost ->
                    stringResource(R.string.sign_in_connection_lost)
                null -> return text
            }
    return stringResource(R.string.error_with_detail, text, detail)
}

internal fun chatGptSignInIntent(origin: ProviderOrigin): UserIntent =
    if (origin.selectsForChat) UserIntent.StartChatGptSignInForChat
    else UserIntent.StartChatGptSignIn
