package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.SignInNetworkIssue
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.FormBody
import okhttp3.OkHttpClient

internal class ChatGptProtocolTest {
    @Test
    fun `a temporary service error cannot replay a single use grant`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(503).setHeader("Retry-After", "0").build())
            server.enqueue(MockResponse.Builder().body("{}").build())
            val client =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        chain.proceed(
                            request.newBuilder().url(server.url(request.url.encodedPath)).build()
                        )
                    }
                    .build()
            val protocol = ChatGptProtocol(client)
            val error =
                assertFailsWith<AccountUnavailable> {
                    protocol.grant(FormBody.Builder().add("code", "synthetic").build())
                }
            assertEquals(503, error.status)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `network errors report only an allowlisted cause even through OkHttp wrapping`() {
        for ((failure, cause) in
            listOf(
                UnknownHostException("private") to SignInNetworkIssue.NameResolution,
                SSLHandshakeException("private") to SignInNetworkIssue.SecureConnection,
                SocketTimeoutException("private") to SignInNetworkIssue.Timeout,
                ConnectException("private") to SignInNetworkIssue.ConnectionLost,
            )) assertEquals(
            cause,
            IOException("HTTP exchange interrupted", failure).signInNetworkIssue(),
        )
        assertNull(IOException("private").signInNetworkIssue())
    }
}
