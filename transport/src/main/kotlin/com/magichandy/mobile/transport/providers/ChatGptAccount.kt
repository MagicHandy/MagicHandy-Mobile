// Ported from internal/openaiauth/{oauth,refresh,store}.go at 9a4c36cf.
package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.SignInFailure
import com.magichandy.mobile.core.ports.BrowserLauncher
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.ModelSignIn
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.core.ports.SecretsVault
import com.magichandy.mobile.transport.http.boundedText
import com.magichandy.mobile.transport.http.exchange
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One registered ChatGPT account. Keep one process-wide instance per vault handle. [hostId] is the
 * install's stable random agent host ID. The platform owns the redirect receiver.
 */
public class ChatGptAccount
internal constructor(
    private val http: Call.Factory,
    private val browser: BrowserLauncher,
    private val vault: SecretsVault,
    private val handle: SecretHandle,
    private val clock: Clock,
    private val hostId: String,
) : ModelSignIn, AccessTokenSource {
    /** Use one non-redirecting client for discovery, signed identity verification and rotation. */
    public constructor(
        client: OkHttpClient,
        browser: BrowserLauncher,
        vault: SecretsVault,
        handle: SecretHandle,
        clock: Clock,
        hostId: String,
    ) : this(
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build() as Call.Factory,
        browser,
        vault,
        handle,
        clock,
        hostId,
    )

    private val serial = Mutex()
    private val entropy = SecureRandom()

    override suspend fun signIn(): SignInFailure? =
        try {
            val completed =
                withTimeoutOrNull(5.minutes) {
                    serial.withLock {
                        try {
                            authorize()
                        } finally {
                            browser.close()
                        }
                    }
                } ?: false
            if (completed) null else SignInFailure.Cancelled
        } catch (_: Rejected) {
            DiagnosticReporter.record(DiagnosticKind.AccountRejected)
            SignInFailure.Rejected
        } catch (_: IOException) {
            DiagnosticReporter.record(DiagnosticKind.AccountUnavailable)
            SignInFailure.NetworkError
        }

    private suspend fun authorize(): Boolean {
        val previous = vault.get(handle)
        val document = discovery()
        val verifier = randomValue()
        val nonce = randomValue()
        val state = randomValue()
        val redirect = browser.redirectUri
        val url = authorizationUrl(document.auth, redirect, verifier, nonce, state)
        val callback = browser.openAndAwait(Secret(url.toString())) ?: return false
        val (clientId, code) = authorizationCode(callback, redirect, state)
        val tokens = exchangeCode(document.token, clientId, code, verifier, redirect)
        val subject = identity(document, tokens, clientId, nonce.reveal())
        storeTokens(tokens, clientId, subject, null, previous)
        return true
    }

    private fun authorizationUrl(
        endpoint: HttpUrl,
        redirect: String,
        verifier: Secret,
        nonce: Secret,
        state: Secret,
    ): HttpUrl {
        val digest =
            MessageDigest.getInstance("SHA-256").digest(verifier.reveal().encodeToByteArray())
        return endpoint
            .newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", "dynamic_agent_client")
            .addQueryParameter("redirect_uri", redirect)
            .addQueryParameter(
                "scope",
                "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
            )
            .addQueryParameter("resource", resource)
            .addQueryParameter("ext_agent_host_id", hostId)
            .addQueryParameter("agent_name_hint", "MagicHandy")
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
    ): Pair<String, Secret> {
        val query = callbackQuery(callback, redirect)
        if (
            !MessageDigest.isEqual(
                query["state"].orEmpty().encodeToByteArray(),
                state.reveal().encodeToByteArray(),
            ) || "error" in query
        )
            throw Rejected()
        val clientId =
            query["client_id"]?.takeIf { it.length in 1..256 && it != "dynamic_agent_client" }
                ?: throw Rejected()
        val code = query["code"]?.takeIf { it.length in 1..8192 } ?: throw Rejected()
        return clientId to Secret(code)
    }

    private suspend fun exchangeCode(
        endpoint: HttpUrl,
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
        return json(Request.Builder().url(endpoint).post(form).build())
    }

    override suspend fun token(): Secret? = serial.withLock {
        val stored = vault.get(handle) ?: return@withLock null
        try {
            val account = parse(stored.reveal())
            val now = clock.now().epochSeconds
            if ("chatgpt.tokens.use.direct" !in account.string("scope").orEmpty().split(' '))
                return@withLock null
            if ((account.number("expiry") ?: 0) > now + 90)
                return@withLock account.string("access_token")?.let(::Secret)
            if (now < (account.number("earliest_refresh_at") ?: 0)) return@withLock null
            val clientId = account.string("client_id") ?: return@withLock null
            val subject = account.string("sub") ?: return@withLock null
            val refresh = account.string("refresh_token") ?: return@withLock null
            val document = discovery()
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
                val rotated = json(Request.Builder().url(document.token).post(form).build())
                if (rotated.string("refresh_token") == refresh) throw Rejected()
                if (
                    rotated.string("id_token") != null &&
                        identity(document, rotated, clientId, null) != subject
                )
                    throw Rejected()
                storeTokens(rotated, clientId, subject, account.string("scope"), stored)
            }
        } catch (_: Rejected) {
            DiagnosticReporter.record(DiagnosticKind.AccountRejected)
            null
        }
    }

    private suspend fun storeTokens(
        tokens: JsonObject,
        clientId: String,
        subject: String,
        priorScope: String?,
        previous: Secret?,
    ): Secret {
        val access = tokens.string("access_token")?.takeIf { it.isNotBlank() } ?: throw Rejected()
        val refresh = tokens.string("refresh_token")?.takeIf { it.isNotBlank() } ?: throw Rejected()
        val expiry = tokens.number("expires_in")?.takeIf { it in 1..86400 } ?: throw Rejected()
        val scopes = tokens.string("scope") ?: priorScope.orEmpty()
        if (
            !tokens.string("token_type").equals("Bearer", ignoreCase = true) ||
                "chatgpt.tokens.use.direct" !in scopes.split(' ')
        )
            throw Rejected()
        val packed = buildJsonObject {
            put("access_token", access)
            put("refresh_token", refresh)
            put("client_id", clientId)
            put("sub", subject)
            put("scope", scopes)
            put("expiry", clock.now().epochSeconds + expiry)
            put("earliest_refresh_at", tokens.number("earliest_refresh_at") ?: 0)
        }
        currentCoroutineContext().ensureActive()
        if (!vault.replace(handle, previous, Secret(packed.toString()))) throw Rejected()
        return Secret(access)
    }

    private suspend fun identity(
        document: Discovery,
        tokens: JsonObject,
        clientId: String,
        nonce: String?,
    ): String {
        val jwt = tokens.string("id_token") ?: throw Rejected()
        val keys = json(Request.Builder().url(document.keys).build())
        return verifiedSubject(Secret(jwt), clientId, nonce, clock.now().epochSeconds, keys)
            ?: throw Rejected()
    }

    private suspend fun discovery(): Discovery {
        val doc = json(Request.Builder().url(issuer + "/.well-known/openid-configuration").build())
        if (doc.string("issuer") != issuer) throw Rejected()
        fun endpoint(name: String): HttpUrl {
            val url = doc.string(name)?.toHttpUrlOrNull() ?: throw Rejected()
            if (
                !url.isHttps ||
                    url.host != "auth.openai.com" ||
                    url.port != 443 ||
                    url.username != "" ||
                    url.password != "" ||
                    url.fragment != null
            )
                throw Rejected()
            return url
        }
        return Discovery(
            endpoint("authorization_endpoint"),
            endpoint("token_endpoint"),
            endpoint("jwks_uri"),
        )
    }

    private suspend fun json(request: Request): JsonObject =
        http.exchange(request) { response ->
            if (!response.isSuccessful) {
                if (response.code in 400..499 && response.code != 429) throw Rejected()
                throw IOException("Account service unavailable")
            }
            parse(response.boundedText(1L shl 20))
        }

    private fun randomValue(): Secret =
        Secret(
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32).also(entropy::nextBytes))
        )

    private fun parse(value: String): JsonObject =
        try {
            Json.parseToJsonElement(value) as? JsonObject ?: throw Rejected()
        } catch (_: SerializationException) {
            throw Rejected()
        }

    private class Rejected : IOException("Account response was rejected")

    private data class Discovery(val auth: HttpUrl, val token: HttpUrl, val keys: HttpUrl)

    private fun callbackQuery(callback: Secret, redirect: String): Map<String, String> {
        val raw = callback.reveal()
        if (raw.length > 16384 || raw.substringBefore('?') != redirect) throw Rejected()
        return try {
            val uri = URI(raw)
            if (uri.rawFragment != null) throw Rejected()
            // URI.rawQuery is null for opaque app schemes. The exact redirect match above also
            // covers them.
            val pairs =
                raw.substringAfter('?', "").split('&').map {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                        URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                }
            if (pairs.distinctBy { it.first }.size != pairs.size) throw Rejected()
            pairs.toMap()
        } catch (_: URISyntaxException) {
            throw Rejected()
        } catch (_: IllegalArgumentException) {
            throw Rejected()
        }
    }
}
