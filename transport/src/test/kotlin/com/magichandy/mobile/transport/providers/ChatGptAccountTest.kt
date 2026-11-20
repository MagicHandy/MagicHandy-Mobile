package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.SignInFailure
import com.magichandy.mobile.core.ports.BrowserLauncher
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.core.ports.SecretsVault
import com.magichandy.mobile.core.testing.InMemoryVault
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class ChatGptAccountTest {
    private class IdentityFixture : BrowserLauncher, Clock {
        override val redirectUri = "fixture:/oauth-return"
        var seconds = 1000L

        override fun now() = Instant.fromEpochSeconds(seconds)

        override fun elapsed() = seconds.seconds

        val vault = InMemoryVault()
        val handle = SecretHandle("synthetic-account")
        val calls = mutableListOf<String>()
        var authorization: HttpUrl? = null
        var wrongState = false
        var invalidNonce = false
        var waitForever = false
        var foreignDiscovery = false
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val base64 = Base64.getUrlEncoder().withoutPadding()

        override suspend fun openAndAwait(authorization: Secret): Secret {
            val url = authorization.reveal().toHttpUrl()
            this.authorization = url
            if (waitForever) awaitCancellation()
            val state = if (wrongState) "wrong" else requireNotNull(url.queryParameter("state"))
            return Secret(
                redirectUri + "?state=" + state + "&code=synthetic-code&client_id=synthetic-client"
            )
        }

        private fun jwt(): String {
            val nonce = if (invalidNonce) "wrong" else authorization?.queryParameter("nonce")
            val claims = buildJsonObject {
                put("iss", issuer)
                put("iat", seconds)
                put("exp", seconds + 900)
                put("sub", "synthetic-user")
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

        private fun tokenBody(form: FormBody): String {
            val args = (0 until form.size).associate { form.name(it) to form.value(it) }
            val refreshing = args["grant_type"] == "refresh_token"
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
                put("scope", "chatgpt.tokens.use.direct")
                put("id_token", jwt())
            }
                .toString()
        }

        val http = MemoryHttp { req ->
            val path = req.url.encodedPath
            calls += path
            val body =
                when (path) {
                    "/.well-known/openid-configuration" -> buildJsonObject {
                            put("issuer", issuer)
                            put("authorization_endpoint", issuer + "/authorize")
                            put(
                                "token_endpoint",
                                (if (foreignDiscovery) "https://other.invalid" else issuer) +
                                    "/token",
                            )
                            put("jwks_uri", issuer + "/keys")
                        }
                            .toString()
                    "/token" -> tokenBody(req.body as FormBody)
                    "/keys" ->
                        (rsa.public as RSAPublicKey).let {
                            """{"keys":[{"kid":"k","kty":"RSA","n":"${component(it.modulus)}","e":"${component(it.publicExponent)}"}]}"""
                        }
                    else -> error("Unexpected synthetic request")
                }
            req.reply(body)
        }

        fun account(store: SecretsVault = vault) =
            ChatGptAccount(http, this, store, handle, this, "synthetic-host")
    }

    @Test
    fun `PKCE sign-in verifies identity and refresh rotates the whole vault credential`() =
        runTest {
            val f = IdentityFixture()
            assertNull(f.account().signIn())
            val restarted = f.account()
            assertEquals(Secret("access-one"), restarted.token())
            f.seconds += 100
            assertEquals(Secret("access-two"), restarted.token())
            assertEquals(2, f.calls.count { it == "/token" })
            assertEquals("Secret(redacted)", assertNotNull(f.vault.get(f.handle)).toString())
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
            assertEquals(2, f.calls.count { it == "/token" })
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
            assertEquals(SignInFailure.Rejected, f.account().signIn())
            assertTrue("/token" !in f.calls)
            assertNull(f.vault.get(f.handle))
        }
    }

    @Test
    fun `even a correctly signed identity with the wrong nonce cannot install credentials`() =
        runTest {
            val f = IdentityFixture()
            f.invalidNonce = true
            assertEquals(SignInFailure.Rejected, f.account().signIn())
            assertNull(f.vault.get(f.handle))
        }

    @Test
    fun `an expired browser attempt is cancelled without installing credentials`() = runTest {
        val f = IdentityFixture()
        f.waitForever = true
        assertEquals(SignInFailure.Cancelled, f.account().signIn())
        assertNull(f.vault.get(f.handle))
        assertTrue("/token" !in f.calls)
    }
}
