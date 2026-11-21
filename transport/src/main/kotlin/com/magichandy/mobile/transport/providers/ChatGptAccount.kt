// Ported from internal/openaiauth/{oauth,refresh,store}.go at 9a4c36cf.
package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.SignInFailure
import com.magichandy.mobile.core.api.SignInStage
import com.magichandy.mobile.core.api.SignInState
import com.magichandy.mobile.core.ports.BrowserLauncher
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.ModelSignIn
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.core.ports.SecretsVault
import com.magichandy.mobile.core.ports.UnusableCredential
import com.magichandy.mobile.core.ports.signInWindow
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * One registered ChatGPT account. Keep one process-wide instance per vault handle. [hostId] is the
 * install's persisted host identifier in OpenAI's wire format (a UUIDv4 URN on Android). The
 * platform owns the redirect receiver.
 */
public class ChatGptAccount
internal constructor(
    http: Call.Factory,
    private val browser: BrowserLauncher,
    private val vault: SecretsVault,
    private val handle: SecretHandle,
    private val clock: Clock,
    private val hostId: String,
    private val signInRequired: suspend () -> Unit = {},
) : ModelSignIn, AccessTokenSource {
    /** Use one non-redirecting client for discovery, signed identity verification and rotation. */
    public constructor(
        client: OkHttpClient,
        browser: BrowserLauncher,
        vault: SecretsVault,
        handle: SecretHandle,
        clock: Clock,
        hostId: String,
        signInRequired: suspend () -> Unit = {},
    ) : this(
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build() as Call.Factory,
        browser,
        vault,
        handle,
        clock,
        hostId,
        signInRequired,
    )

    private class Registration(val id: String, val subject: String, val hint: Secret?)

    private val protocol = ChatGptProtocol(http)
    private val serial = Mutex()
    private val entropy = SecureRandom()
    // A failed first exchange can leave an issued client but no verified identity. Offer it once
    // on an immediate retry. An existing verified account always supplies its own registration.
    private var issuedForRetry: String? = null

    override suspend fun signIn(): SignInState.Failed? {
        var stage: SignInStage? = null
        return try {
            val completed =
                withTimeoutOrNull(signInWindow) {
                    serial.withLock {
                        try {
                            authorize { stage = it }
                        } finally {
                            browser.close()
                        }
                    }
                } ?: false
            if (completed) null else SignInState.Failed(SignInFailure.Cancelled)
        } catch (_: CredentialStoreUnavailable) {
            DiagnosticReporter.record(DiagnosticKind.StoreUnavailable)
            SignInState.Failed(SignInFailure.StorageUnavailable)
        } catch (failure: AccountRejected) {
            DiagnosticReporter.record(DiagnosticKind.AccountRejected)
            SignInState.Failed(SignInFailure.Rejected, stage, failure.status)
        } catch (failure: IOException) {
            DiagnosticReporter.record(DiagnosticKind.AccountUnavailable)
            SignInState.Failed(
                SignInFailure.NetworkError,
                stage,
                (failure as? AccountUnavailable)?.status,
                failure.signInNetworkIssue(),
            )
        }
    }

    private suspend fun authorize(at: (SignInStage) -> Unit): Boolean {
        val edit =
            try {
                vault.prepareSignIn(handle)
            } catch (_: IOException) {
                throw CredentialStoreUnavailable()
            }
        val saved = edit.original?.let { registration(it) }
        val client = saved?.id ?: issuedForRetry?.takeIf { edit.original == null }
        issuedForRetry = null
        at(SignInStage.Discovery)
        val keys = protocol.signingKeysUri()
        at(SignInStage.BrowserCallback)
        val verifier = randomValue()
        val nonce = randomValue()
        val state = randomValue()
        val redirect = browser.redirectUri
        val url = authorizationUrl(redirect, verifier, nonce, state, client, saved?.hint)
        val callback = browser.openAndAwait(Secret(url.toString())) ?: return false
        val (clientId, code) = authorizationCode(callback, redirect, state, client)
        at(SignInStage.CodeExchange)
        val tokens =
            try {
                exchangeCode(clientId, code, verifier, redirect)
            } catch (failure: IOException) {
                if (
                    client == null &&
                        edit.original == null &&
                        (failure !is AccountRejected || failure.status == 400)
                )
                    issuedForRetry = clientId
                throw failure
            }
        at(SignInStage.IdentityVerification)
        val subject = identity(protocol.signingKeys(keys), tokens, clientId, nonce.reveal())
        if (saved != null && subject != saved.subject) throw AccountRejected()
        at(SignInStage.CodeExchange)
        storeTokens(tokens, clientId, subject, null) {
            try {
                edit.commit(it)
            } catch (_: IOException) {
                throw CredentialStoreUnavailable()
            }
        }
        return true
    }

    private suspend fun registration(stored: Secret): Registration? =
        try {
            val record = parseAccountObject(stored.reveal())
            Registration(
                issuedClient(record.string("client_id")),
                record.string("sub")?.takeIf(String::isNotBlank) ?: throw AccountRejected(),
                record.string("id_token")?.let(::Secret),
            )
        } catch (_: AccountRejected) {
            // An explicit sign-in can repair an unreadable registration after identity
            // verification.
            DiagnosticReporter.record(DiagnosticKind.AccountRejected)
            null
        }

    private fun authorizationUrl(
        redirect: String,
        verifier: Secret,
        nonce: Secret,
        state: Secret,
        client: String?,
        hint: Secret?,
    ): HttpUrl {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(verifier.reveal().encodeToByteArray())
        return protocol.authorize
            .newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", client ?: "dynamic_agent_client")
            .addQueryParameter("redirect_uri", redirect)
            .addQueryParameter(
                "scope",
                "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
            )
            .addQueryParameter("resource", resource)
            .addQueryParameter("ext_agent_host_id", hostId)
            .apply {
                if (client == null) addQueryParameter("agent_name_hint", "MagicHandy")
                else hint?.let { addQueryParameter("id_token_hint", it.reveal()) }
            }
            .addQueryParameter("state", state.reveal())
            .addQueryParameter("nonce", nonce.reveal())
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter(
                "code_challenge",
                Base64.getUrlEncoder().withoutPadding().encodeToString(digest),
            )
            .build()
    }

    private fun authorizationCode(
        callback: Secret,
        redirect: String,
        state: Secret,
        savedClient: String?,
    ): Pair<String, Secret> {
        val query = callbackQuery(callback, redirect)
        if (
            !MessageDigest.isEqual(
                query["state"].orEmpty().encodeToByteArray(),
                state.reveal().encodeToByteArray(),
            ) || "error" in query
        )
            throw AccountRejected()
        val clientId = issuedClient(query["client_id"] ?: savedClient)
        if (savedClient != null && clientId != savedClient) throw AccountRejected()
        val code = query["code"]?.takeIf { it.length in 1..8192 } ?: throw AccountRejected()
        return clientId to Secret(code)
    }

    private fun issuedClient(id: String?): String =
        id?.takeIf {
            it.length in 1..256 &&
                it.all { ch -> ch.code in 33..126 } &&
                it != "dynamic_agent_client"
        } ?: throw AccountRejected()

    private suspend fun exchangeCode(
        clientId: String,
        code: Secret,
        verifier: Secret,
        redirect: String,
    ): JsonObject {
        val form =
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", clientId)
                .add("code", code.reveal())
                .add("code_verifier", verifier.reveal())
                .add("redirect_uri", redirect)
                .add("resource", resource)
                .build()
        return protocol.grant(form)
    }

    override suspend fun token(): Secret? = serial.withLock {
        try {
            val stored = vault.get(handle) ?: return@withLock requireSignIn()
            val account = parseAccountObject(stored.reveal())
            val now = clock.now().epochSeconds
            if ("chatgpt.tokens.use.direct" !in account.string("scope").orEmpty().split(' '))
                return@withLock requireSignIn()
            val expiry = account.number("expiry") ?: return@withLock requireSignIn()
            if (expiry > now + 90 || now < (account.number("earliest_refresh_at") ?: 0)) {
                val bearer =
                    account.string("access_token")?.takeIf { it.isNotBlank() && expiry > now }
                return@withLock bearer?.let(::Secret) ?: requireSignIn()
            }
            val clientId = account.string("client_id") ?: return@withLock requireSignIn()
            val subject = account.string("sub") ?: return@withLock requireSignIn()
            val refresh = account.string("refresh_token") ?: return@withLock requireSignIn()
            // A temporary JWKS outage must not strand a newly rotated, single-use grant.
            val keys = protocol.signingKeys(protocol.signingKeysUri())
            val form =
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", clientId)
                    .add("refresh_token", refresh)
                    .add("resource", resource)
                    .build()
            currentCoroutineContext().ensureActive()
            // Once the endpoint can rotate the old token, finish verification and the vault CAS.
            withContext(NonCancellable) {
                val rotated = protocol.grant(form)
                if (rotated.string("refresh_token") == refresh) throw AccountRejected()
                if (
                    rotated.string("id_token") != null &&
                        identity(keys, rotated, clientId, null) != subject
                )
                    throw AccountRejected()
                storeTokens(rotated, clientId, subject, account) {
                    vault.replace(handle, stored, it)
                }
            }
        } catch (_: AccountRejected) {
            DiagnosticReporter.record(DiagnosticKind.AccountRejected)
            requireSignIn()
        } catch (_: UnusableCredential) {
            requireSignIn()
        }
    }

    private suspend fun requireSignIn(): Secret? {
        signInRequired()
        return null
    }

    override suspend fun rejected(token: Secret) {
        serial.withLock {
            val saved = vault.get(handle) ?: return@withLock
            if (parseAccountObject(saved.reveal()).string("access_token") == token.reveal())
                signInRequired()
        }
    }

    private suspend fun storeTokens(
        tokens: JsonObject,
        clientId: String,
        subject: String,
        prior: JsonObject?,
        persist: suspend (Secret) -> Boolean,
    ): Secret {
        val access =
            tokens.string("access_token")?.takeIf { it.isNotBlank() } ?: throw AccountRejected()
        val refresh =
            tokens.string("refresh_token")?.takeIf { it.isNotBlank() } ?: throw AccountRejected()
        val expiry =
            tokens.number("expires_in")?.takeIf { it in 1..86400 } ?: throw AccountRejected()
        val scopes = tokens.string("scope") ?: prior?.string("scope").orEmpty()
        if (
            !tokens.string("token_type").equals("Bearer", ignoreCase = true) ||
                "chatgpt.tokens.use.direct" !in scopes.split(' ')
        )
            throw AccountRejected()
        val packed = buildJsonObject {
            put("access_token", access)
            put("refresh_token", refresh)
            put("client_id", clientId)
            put("sub", subject)
            (tokens.string("id_token") ?: prior?.string("id_token"))?.let { put("id_token", it) }
            put("scope", scopes)
            put("expiry", clock.now().epochSeconds + expiry)
            put("earliest_refresh_at", tokens.number("earliest_refresh_at") ?: 0)
        }
        currentCoroutineContext().ensureActive()
        if (!persist(Secret(packed.toString()))) throw AccountRejected()
        return Secret(access)
    }

    private fun identity(
        keys: JsonObject,
        tokens: JsonObject,
        clientId: String,
        nonce: String?,
    ): String {
        val jwt = tokens.string("id_token") ?: throw AccountRejected()
        return verifiedSubject(Secret(jwt), clientId, nonce, clock.now().epochSeconds, keys)
            ?: throw AccountRejected()
    }

    private fun randomValue(): Secret =
        Secret(
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32).also(entropy::nextBytes))
        )

    private class CredentialStoreUnavailable : IOException("Credential storage unavailable")

    private fun callbackQuery(callback: Secret, redirect: String): Map<String, String> {
        val raw = callback.reveal()
        if (raw.length > 16384 || raw.substringBefore('?') != redirect) throw AccountRejected()
        return try {
            val uri = URI(raw)
            if (uri.rawFragment != null) throw AccountRejected()
            // URI.rawQuery is null for opaque app schemes. The exact redirect match above also
            // covers them.
            val pairs =
                raw.substringAfter('?', "").split('&').map {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                        URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                }
            if (pairs.distinctBy { it.first }.size != pairs.size) throw AccountRejected()
            pairs.toMap()
        } catch (_: URISyntaxException) {
            throw AccountRejected()
        } catch (_: IllegalArgumentException) {
            throw AccountRejected()
        }
    }
}
