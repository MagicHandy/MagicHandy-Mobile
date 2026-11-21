package com.magichandy.mobile.core.api

/** A device connection failure that can be shown without exposing a key or a vendor response. */
public data class DeviceConnectionFailure(
    public val reason: Reason,
    /** HTTP status from a failed request, when the service returned one. */
    public val httpStatus: Int? = null,
    /** A recognized device error number from a successful HTTP exchange. */
    public val deviceCode: Int? = null,
) {
    public enum class Reason {
        CredentialUnavailable,
        Offline,
        AccessDenied,
        TimedOut,
        DeviceBusy,
        RateLimited,
        ServiceStartFailed,
        ServiceUnavailable,
        InvalidReply,
        IdleUnconfirmed,
    }
}
