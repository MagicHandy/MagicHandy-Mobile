package com.magichandy.mobile.core.api

/** A safe explanation for a hosted reply that did not complete. Contains no provider text. */
public data class ProviderReplyFailure(
    public val reason: Reason,
    /** Status from an unsuccessful HTTP response, when available. */
    public val httpStatus: Int? = null,
) {
    init {
        require(httpStatus == null || httpStatus in 100..599)
    }

    /** The recovery action depends on which part of the provider request failed. */
    public enum class Reason {
        SignInRequired,
        PermissionDenied,
        UsageLimit,
        ModelUnavailable,
        RequestRejected,
        ServiceUnavailable,
        UnexpectedResponse,
    }
}
