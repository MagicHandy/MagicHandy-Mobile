package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.SignInFailure
import com.magichandy.mobile.core.api.SignInStage
import com.magichandy.mobile.core.api.SignInState
import com.magichandy.mobile.core.ports.BrowserLauncher
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.core.ports.SecretsVault
import com.magichandy.mobile.core.testing.InMemoryVault
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

internal class ChatGptAccountTest {
    private class IdentityFixture : BrowserLauncher, Clock {
        override val redirectUri = "http://127.0.0.1:54321/auth/callback"
        var seconds = 1000L

        override fun now() = Instant.fromEpochSeconds(seconds)

        override fun elapsed() = seconds.seconds

        val vault = InMemoryVault()
        val handle = SecretHandle("synthetic-account")
        val calls = mutableListOf<String>()
        var authorization: HttpUrl? = null
        var returnClient: String? = "synthetic-client"
        var subject = "synthetic-user"
        var wrongState = false
        var refreshAfter = 0L
        var invalidNonce = false
        var waitForever = false
        var foreignDiscovery = false
        var failingPath: String? = null
        var status = 503
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val base64 = Base64.getUrlEncoder().withoutPadding()

        override suspend fun openAndAwait(authorization: Secret): Secret {
            val url = authorization.reveal().toHttpUrl()
            this.authorization = url
            if (waitForever) awaitCancellation()
            val state = if (wrongState) "wrong" else requireNotNull(url.queryParameter("state"))
            return Secret(
                redirectUri +
                    "?state=" +
                    state +
                    "&code=synthetic-code" +
                    returnClient?.let { "&client_id=" + it }.orEmpty()
            )
        }

        private fun jwt(): String {
            val nonce = if (invalidNonce) "wrong" else authorization?.queryParameter("nonce")
            val claims = buildJsonObject {
                put("iss", issuer)
                put("iat", seconds)
                put("exp", seconds + 900)
                put("sub", subject)
                put("aud", "synthetic-client")
                put("nonce", nonce)
            }
            val head = base64.encodeToString("""{"alg":"RS256","kid":"k"}""".encodeToByteArray())
            val signed = head + "." + base64.encodeToString(claims.toString().encodeToByteArray())
            val sig =
                Signature.getInstance("SHA256withRSA")
                    .apply {
                        initSign(rsa.private)
                        update(signed.encodeToByteArray())
                    }
                    .sign()
            return signed + "." + base64.encodeToString(sig)
        }

        private fun component(value: BigInteger) =
            base64.encodeToString(
                value.toByteArray().let {
                    if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
                }
            )

        private fun tokenBody(request: Request): String {
            val form = request.url.newBuilder().encodedQuery(request.payload()).build()
            val args = form.queryParameterNames.associateWith(form::queryParameter)
            assertTrue(requireNotNull(request.body).isOneShot())
            assertEquals(
                "application/x-www-form-urlencoded",
                request.body?.contentType().toString(),
            )
            val refreshing = args["grant_type"] == "refresh_token"
            assertEquals("synthetic-client", args["client_id"])
            if (refreshing) assertEquals("synthetic-refresh", args["refresh_token"])
            else {
                val verifier = requireNotNull(args["code_verifier"])
                assertEquals(
                    authorization?.queryParameter("code_challenge"),
                    base64.encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
                    ),
                )
                assertEquals("S256", authorization?.queryParameter("code_challenge_method"))
            }
            return buildJsonObject {
                put("token_type", "Bearer")
                put("access_token", if (refreshing) "access-two" else "access-one")
                put("refresh_token", if (refreshing) "rotated" else "synthetic-refresh")
                put("expires_in", 180)
                put("earliest_refresh_at", refreshAfter)
                put("scope", "chatgpt.tokens.use.direct")
                put("id_token", jwt())
            }
                .toString()
        }

        val http = MemoryHttp { req ->
            val path = req.url.encodedPath
            calls += path
            if (path == failingPath)
                return@MemoryHttp req.reply("private fixture detail")
                    .newBuilder()
                    .code(status)
                    .build()
            val body =
                when (path) {
                    "/.well-known/openid-configuration" -> buildJsonObject {
                            put("issuer", issuer)
                            put("authorization_endpoint", issuer + "/oauth/authorize")
                            put(
                                "token_endpoint",
                                (if (foreignDiscovery) "https://other.invalid" else issuer) +
                                    "/oauth/token",
                            )
                            put("jwks_uri", issuer + "/keys")
                        }
                            .toString()
                    "/api/accounts/oauth/token" -> tokenBody(req)
                    "/keys" ->
                        (rsa.public as RSAPublicKey).let {
                            """{"keys":[{"kid":"k","kty":"RSA","n":"${component(it.modulus)}","e":"${component(it.publicExponent)}"}]}"""
                        }
                    else -> error("Unexpected synthetic request")
                }
            req.reply(body)
        }

        fun account(store: SecretsVault = vault, onSignInRequired: suspend () -> Unit = {}) =
            ChatGptAccount(
                http,
                this,
                store,
                handle,
                this,
                "urn:uuid:40c39552-4166-4f5f-b061-3ebfa5d07cf5",
                onSignInRequired,
            )
    }

    @Test
    fun `PKCE sign-in verifies identity and refresh rotates the whole vault credential`() =
        runTest {
            val f = IdentityFixture()
            assertNull(f.account().signIn())
            assertEquals("/api/accounts/authorize", f.authorization?.encodedPath)
            assertEquals(
                listOf("/.well-known/openid-configuration", "/api/accounts/oauth/token", "/keys"),
                f.calls,
            )
            val restarted = f.account()
            assertEquals(Secret("access-one"), restarted.token())
            f.seconds += 100
            assertEquals(Secret("access-two"), restarted.token())
            assertEquals(2, f.calls.count { it == "/api/accounts/oauth/token" })
            assertEquals("Secret(redacted)", assertNotNull(f.vault.get(f.handle)).toString())
        }

    @Test
    fun `unavailable signing keys do not consume the single use refresh token`() = runTest {
        val fixture = IdentityFixture()
        val account = fixture.account()
        assertNull(account.signIn())
        fixture.calls.clear()
        fixture.seconds += 100
        fixture.failingPath = "/keys"
        assertFailsWith<IOException> { account.token() }
        assertTrue("/api/accounts/oauth/token" !in fixture.calls)
        fixture.failingPath = null
        assertEquals(Secret("access-two"), account.token())
        assertEquals(1, fixture.calls.count { it == "/api/accounts/oauth/token" })
        assertEquals("/api/accounts/oauth/token", fixture.calls.last())
    }

    @Test
    fun `expired bearer before the permitted refresh window requests sign-in`() = runTest {
        val fixture = IdentityFixture()
        var required = 0
        val account = fixture.account(onSignInRequired = { required++ })
        fixture.refreshAfter = fixture.seconds + 300
        assertNull(account.signIn())
        fixture.seconds += 200
        assertNull(account.token())
        assertEquals(1, required)
        assertEquals(1, fixture.calls.count { it == "/api/accounts/oauth/token" })
    }

    @Test
    fun `cancelling the turn after the refresh response still stores the rotated credential`() =
        runTest {
            val f = IdentityFixture()
            val storing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store =
                object : SecretsVault by f.vault {
                    override suspend fun replace(
                        handle: SecretHandle,
                        expected: Secret?,
                        value: Secret,
                    ): Boolean {
                        if (expected != null) {
                            storing.complete(Unit)
                            release.await()
                        }
                        return f.vault.replace(handle, expected, value)
                    }
                }
            val account = f.account(store)
            assertNull(account.signIn())
            f.seconds += 100
            val turn = launch { account.token() }
            storing.await()
            turn.cancel()
            release.complete(Unit)
            turn.join()
            val stored =
                Json.parseToJsonElement(assertNotNull(f.vault.get(f.handle)).reveal()).jsonObject
            assertEquals(
                Secret("rotated"),
                Secret(stored.getValue("refresh_token").jsonPrimitive.content),
            )
            assertEquals(Secret("access-two"), f.account().token())
            assertEquals(2, f.calls.count { it == "/api/accounts/oauth/token" })
        }

    @Test
    fun `a refresh finishing after local removal cannot restore the credential`() = runTest {
        val f = IdentityFixture()
        val store =
            object : SecretsVault by f.vault {
                override suspend fun replace(
                    handle: SecretHandle,
                    expected: Secret?,
                    value: Secret,
                ): Boolean {
                    if (expected != null) f.vault.remove(handle)
                    return f.vault.replace(handle, expected, value)
                }
            }
        val account = f.account(store)
        assertNull(account.signIn())
        f.seconds += 100
        assertNull(account.token())
        assertNull(f.vault.get(f.handle))
    }

    @Test
    fun `wrong state or a foreign discovery endpoint never exchanges a code`() = runTest {
        for (foreign in listOf(false, true)) {
            val f = IdentityFixture()
            f.foreignDiscovery = foreign
            f.wrongState = !foreign
            assertEquals(SignInFailure.Rejected, f.account().signIn()?.cause)
            assertTrue("/api/accounts/oauth/token" !in f.calls)
            assertNull(f.vault.get(f.handle))
        }
    }

    @Test
    fun `even a correctly signed identity with the wrong nonce cannot install credentials`() =
        runTest {
            val f = IdentityFixture()
            f.invalidNonce = true
            assertEquals(SignInFailure.Rejected, f.account().signIn()?.cause)
            assertNull(f.vault.get(f.handle))
        }

    @Test
    fun `an exchange or key retrieval failure carries only its stage and HTTP status`() = runTest {
        for ((stage, path) in
            listOf(
                SignInStage.CodeExchange to "/api/accounts/oauth/token",
                SignInStage.IdentityVerification to "/keys",
            )) {
            for (status in listOf(403, 429, 503)) {
                val fixture = IdentityFixture()
                fixture.failingPath = path
                fixture.status = status
                val cause =
                    if (status == 403) SignInFailure.Rejected else SignInFailure.NetworkError
                assertEquals(SignInState.Failed(cause, stage, status), fixture.account().signIn())
                assertNull(fixture.vault.get(fixture.handle))
                assertEquals(1, fixture.calls.count { it == "/api/accounts/oauth/token" })
            }
        }
    }

    @Test
    fun `an expired browser attempt is cancelled without installing credentials`() = runTest {
        val f = IdentityFixture()
        f.waitForever = true
        assertEquals(SignInFailure.Cancelled, f.account().signIn()?.cause)
        assertNull(f.vault.get(f.handle))
        assertTrue("/api/accounts/oauth/token" !in f.calls)
    }

    @Test
    fun `retry after a failed first exchange reauthorizes the issued client with fresh PKCE`() =
        runTest {
            val f = IdentityFixture()
            val account = f.account()
            f.failingPath = "/api/accounts/oauth/token"
            f.status = 400
            assertEquals(SignInStage.CodeExchange, account.signIn()?.stage)
            val first = assertNotNull(f.authorization)
            assertNull(f.vault.get(f.handle))
            f.failingPath = null
            f.returnClient = null
            assertNull(account.signIn())
            val second = assertNotNull(f.authorization)
            assertEquals("synthetic-client", second.queryParameter("client_id"))
            assertNull(second.queryParameter("agent_name_hint"))
            assertNull(second.queryParameter("id_token_hint"))
            for (key in listOf("state", "nonce", "code_challenge")) assertTrue(
                first.queryParameter(key) != second.queryParameter(key)
            )
            assertEquals(2, f.calls.count { it == "/api/accounts/oauth/token" })
        }

    @Test
    fun `the same host reauthorizes the issued client when the callback omits its client id`() =
        runTest {
            val f = IdentityFixture()
            assertNull(f.account().signIn())
            val first = assertNotNull(f.authorization)
            assertEquals("dynamic_agent_client", first.queryParameter("client_id"))
            assertEquals("MagicHandy", first.queryParameter("agent_name_hint"))
            assertEquals(
                "urn:uuid:40c39552-4166-4f5f-b061-3ebfa5d07cf5",
                first.queryParameter("ext_agent_host_id"),
            )
            f.returnClient = null
            assertNull(f.account().signIn())
            val second = assertNotNull(f.authorization)
            assertEquals("synthetic-client", second.queryParameter("client_id"))
            assertEquals(
                first.queryParameter("ext_agent_host_id"),
                second.queryParameter("ext_agent_host_id"),
            )
            assertNull(second.queryParameter("agent_name_hint"))
            assertTrue(!second.queryParameter("id_token_hint").isNullOrBlank())
            for (field in listOf("state", "nonce", "code_challenge")) assertTrue(
                first.queryParameter(field) != second.queryParameter(field)
            )
        }

    @Test
    fun `returning sign-in keeps the saved account if the client or subject changes`() = runTest {
        val f = IdentityFixture()
        assertNull(f.account().signIn())
        val before = assertNotNull(f.vault.get(f.handle))
        f.returnClient = "unexpected-client"
        assertEquals(SignInFailure.Rejected, f.account().signIn()?.cause)
        assertEquals(1, f.calls.count { it == "/api/accounts/oauth/token" })
        f.returnClient = null
        f.subject = "another-user"
        assertEquals(SignInFailure.Rejected, f.account().signIn()?.cause)
        assertEquals(2, f.calls.count { it == "/api/accounts/oauth/token" })
        assertEquals(before, f.vault.get(f.handle))
    }

    @Test
    fun `a usable access token still works in the early refresh window`() = runTest {
        val fixture = IdentityFixture()
        fixture.refreshAfter = fixture.seconds + 160
        val account = fixture.account()
        assertNull(account.signIn())
        fixture.seconds += 120
        assertEquals(Secret("access-one"), account.token())
        assertEquals(1, fixture.calls.count { it == "/api/accounts/oauth/token" })
        fixture.seconds += 40
        assertEquals(Secret("access-two"), account.token())
    }

    @Test
    fun `a new registration requires its issued client and an older saved account can omit the hint`() =
        runTest {
            val fixture = IdentityFixture()
            fixture.returnClient = null
            assertEquals(SignInFailure.Rejected, fixture.account().signIn()?.cause)
            assertTrue("/api/accounts/oauth/token" !in fixture.calls)
            fixture.returnClient = "synthetic-client"
            assertNull(fixture.account().signIn())
            val original = assertNotNull(fixture.vault.get(fixture.handle))
            val legacy =
                JsonObject(Json.parseToJsonElement(original.reveal()).jsonObject - "id_token")
            fixture.vault.put(fixture.handle, Secret(legacy.toString()))
            fixture.returnClient = null
            assertNull(fixture.account().signIn())
            val authorization = assertNotNull(fixture.authorization)
            assertEquals("synthetic-client", authorization.queryParameter("client_id"))
            assertNull(authorization.queryParameter("id_token_hint"))
        }

    @Test
    fun `only a refusal for the current bearer marks the account as needing sign-in`() = runTest {
        val fixture = IdentityFixture()
        var required = 0
        val account = fixture.account(onSignInRequired = { required++ })
        assertNull(account.signIn())
        account.rejected(Secret("old"))
        assertEquals(0, required)
        account.rejected(Secret("access-one"))
        assertEquals(1, required)
        fixture.seconds += 100
        assertEquals(Secret("access-two"), account.token())
        account.rejected(Secret("access-one"))
        assertEquals(1, required)
    }
}
