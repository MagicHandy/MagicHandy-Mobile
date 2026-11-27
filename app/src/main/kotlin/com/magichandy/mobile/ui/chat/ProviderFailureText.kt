package com.magichandy.mobile.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.api.ProviderReplyFailure.Reason

@Composable
internal fun providerFailureText(failure: ProviderReplyFailure): String {
    val message =
        stringResource(
            when (failure.reason) {
                Reason.ContextLimit -> R.string.provider_context_limit
                Reason.CredentialsRejected -> R.string.reply_provider_credentials
                Reason.BillingRequired -> R.string.reply_provider_billing
                Reason.RateLimited -> R.string.reply_provider_rate
                Reason.SignInRequired -> R.string.reply_provider_sign_in
                Reason.PermissionDenied -> R.string.reply_provider_permission
                Reason.UsageLimit -> R.string.reply_provider_limit
                Reason.ModelUnavailable -> R.string.reply_provider_model
                Reason.RequestRejected -> R.string.reply_provider_request
                Reason.ServiceUnavailable -> R.string.reply_provider_service
                Reason.UnexpectedResponse -> R.string.reply_provider_format
            }
        )
    val status = failure.httpStatus ?: return message
    return stringResource(
        R.string.error_with_detail,
        message,
        stringResource(R.string.http_status, status.toString()),
    )
}
