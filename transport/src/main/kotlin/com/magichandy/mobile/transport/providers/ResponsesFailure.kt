package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.api.ProviderReplyFailure.Reason
import com.magichandy.mobile.core.ports.TextResult
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Response

/** Only a known recovery category and the HTTP status may leave the Responses client. */
internal fun Response.responsesFailure(): TextResult.Failed {
    val providerCode =
        try {
            val doc = Json.parseToJsonElement(peekBody(8192).string()) as? JsonObject
            (doc?.get("error") as? JsonObject)?.string("code")
        } catch (_: SerializationException) {
            null
        } catch (_: IOException) {
            null
        }
    return responsesFailure(code, providerCode)
}

internal fun responsesFailure(status: Int?, code: String?): TextResult.Failed {
    val reason =
        when {
            status == 401 -> Reason.SignInRequired
            code == "subscription_sharing_usage_limit_exceeded" || status in setOf(402, 429) ->
                Reason.UsageLimit
            code in
                setOf(
                    "subscription_sharing_usage_unavailable",
                    "subscription_sharing_user_unavailable",
                ) -> Reason.ServiceUnavailable
            code == "model_not_found" -> Reason.ModelUnavailable
            status == 408 || status == 425 -> Reason.ServiceUnavailable
            status == 403 ||
                code in
                    setOf(
                        "subscription_sharing_user_not_eligible",
                        "subscription_sharing_invalid_user",
                        "chatpass_v2_scope_not_authorized",
                        "chatpass_v2_invalid_authorization_context",
                    ) -> Reason.PermissionDenied
            status != null && status in 400..499 ||
                code in
                    setOf(
                        "subscription_sharing_unsupported_capability",
                        "subscription_sharing_route_not_supported",
                    ) -> Reason.RequestRejected
            else -> Reason.ServiceUnavailable
        }
    return TextResult.Failed(ProviderReplyFailure(reason, status))
}
