package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.api.ProviderReplyFailure.Reason
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

internal class ProviderFailuresTest {
    @Test
    fun providersKeepActionableHttpFailures() = runTest {
        for (failCatalog in listOf(false, true)) for ((status, reason) in
            mapOf(
                401 to Reason.CredentialsRejected,
                402 to Reason.BillingRequired,
                429 to Reason.RateLimited,
                503 to Reason.ServiceUnavailable,
            )) {
            val client =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val catalog = chain.request().url.encodedPath.endsWith("/models")
                        val json =
                            if (catalog)
                                """{"data":[{"id":"synthetic","supported_parameters":["max_tokens"]}]}"""
                            else """{"error":{"message":"synthetic failure"}}"""
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(if (catalog && !failCatalog) 200 else status)
                            .message("fixture")
                            .body(json.toResponseBody())
                            .build()
                    }
                    .build()
            val endpoint = "http://synthetic.invalid/".toHttpUrl()
            val providers = buildList {
                add(
                    CompatibleProvider.openRouter(
                        client,
                        AccessTokenSource { Secret("synthetic-fixture") },
                        "synthetic",
                        false,
                    )
                )
                if (!failCatalog) {
                    add(CompatibleProvider(client, endpoint, "synthetic", null, false, 8192))
                    add(OllamaProvider(client, endpoint, "synthetic", 8192))
                }
            }
            for (provider in providers) {
                val result =
                    provider.stream(listOf(TextMessage(TextRole.User, "Synthetic turn."))) {}
                assertEquals(TextResult.Failed(ProviderReplyFailure(reason, status)), result)
            }
        }
    }
}
