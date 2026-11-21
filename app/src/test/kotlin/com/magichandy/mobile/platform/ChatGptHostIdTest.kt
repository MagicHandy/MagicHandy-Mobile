package com.magichandy.mobile.platform

import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.SignInFailure
import com.magichandy.mobile.core.ports.BrowserLauncher
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.core.testing.InMemoryVault
import com.magichandy.mobile.platform.storage.AtomicBytes
import com.magichandy.mobile.platform.storage.chatGptHostId
import com.magichandy.mobile.transport.providers.ChatGptAccount
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class ChatGptHostIdTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a new install persists a UUIDv4 URN and reuses it after restart`() {
        val file = File(folder.root, "agent-host")
        val storage = AtomicBytes(file) {}
        val id = chatGptHostId(storage)
        assertTrue(id.startsWith("urn:uuid:"))
        assertEquals(4, UUID.fromString(id.removePrefix("urn:uuid:")).version())
        assertEquals(id, file.readText())
        assertEquals(id, chatGptHostId(storage))
    }

    @Test
    fun `upgrading the installed APK preserves the original UUID and repairs its wire format`() {
        val file = File(folder.root, "agent-host")
        val storage = AtomicBytes(file) {}
        val original = "c7906153-8654-4d60-a06c-853de0d214dd"
        file.writeText(original)
        val expected = "urn:uuid:" + original
        assertEquals(expected, chatGptHostId(storage))
        assertEquals(expected, file.readText())
        assertEquals(expected, chatGptHostId(storage))
    }

    @Test
    fun `a malformed local host id is replaced once`() {
        val file = File(folder.root, "agent-host")
        val storage = AtomicBytes(file) {}
        file.writeText("damaged")
        val replaced = chatGptHostId(storage)
        assertEquals(4, UUID.fromString(replaced.removePrefix("urn:uuid:")).version())
        assertEquals("urn:uuid:", replaced.take(9))
        assertEquals(replaced, chatGptHostId(storage))
    }

    @Test
    fun `the upgraded host reaches the real authorization builder with the required query`() =
        runBlocking {
            val file = File(folder.root, "agent-host")
            val legacy = "d7246d86-59bc-4df7-9d86-257b17ca58e1"
            file.writeText(legacy)
            val id = chatGptHostId(AtomicBytes(file) {})
            val issuer = "https://auth.openai.com"
            val discovery =
                """{"issuer":"$issuer","authorization_endpoint":"$issuer/api/accounts/authorize","token_endpoint":"$issuer/token","jwks_uri":"$issuer/keys"}"""
            val http =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        assertEquals(
                            "/.well-known/openid-configuration",
                            chain.request().url.encodedPath,
                        )
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(discovery.toResponseBody())
                            .build()
                    }
                    .build()
            var opened = false
            val browser =
                object : BrowserLauncher {
                    override val redirectUri = "http://127.0.0.1:55555/auth/callback"

                    override suspend fun openAndAwait(authorization: Secret): Secret? {
                        val url = authorization.reveal().toHttpUrl()
                        assertEquals("auth.openai.com", url.host)
                        assertEquals("/api/accounts/authorize", url.encodedPath)
                        assertEquals(
                            listOf("urn:uuid:$legacy"),
                            url.queryParameterValues("ext_agent_host_id"),
                        )
                        assertEquals(redirectUri, url.queryParameter("redirect_uri"))
                        assertEquals("S256", url.queryParameter("code_challenge_method"))
                        opened = true
                        return null
                    }
                }
            val clock =
                object : Clock {
                    override fun elapsed() = Duration.ZERO

                    override fun now() = Instant.fromEpochSeconds(1000)
                }
            val account =
                ChatGptAccount(http, browser, InMemoryVault(), SecretHandle("fixture"), clock, id)
            assertEquals(SignInFailure.Cancelled, account.signIn()?.cause)
            assertTrue(opened)
        }
}
