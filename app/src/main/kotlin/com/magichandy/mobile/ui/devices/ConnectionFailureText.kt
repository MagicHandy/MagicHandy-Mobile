package com.magichandy.mobile.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.magichandy.mobile.R
import com.magichandy.mobile.core.api.DeviceConnectionFailure
import com.magichandy.mobile.core.api.DeviceConnectionFailure.Reason

@Composable
internal fun connectionFailureText(failure: DeviceConnectionFailure): String {
    val text =
        stringResource(
            when (failure.reason) {
                Reason.CredentialUnavailable -> R.string.handy_cloud_credential
                Reason.Offline -> R.string.handy_cloud_offline
                Reason.AccessDenied -> R.string.handy_cloud_access
                Reason.TimedOut -> R.string.handy_cloud_timeout
                Reason.DeviceBusy -> R.string.handy_cloud_busy
                Reason.RateLimited -> R.string.handy_cloud_rate
                Reason.ServiceStartFailed -> R.string.device_connection_service_start
                Reason.ServiceUnavailable -> R.string.handy_cloud_service
                Reason.InvalidReply -> R.string.handy_cloud_reply
                Reason.IdleUnconfirmed -> R.string.handy_cloud_idle
            }
        )
    val detail =
        failure.httpStatus?.let { stringResource(R.string.http_status, it.toString()) }
            ?: failure.deviceCode?.let { stringResource(R.string.handy_cloud_code, it.toString()) }
            ?: return text
    return stringResource(R.string.error_with_detail, text, detail)
}
