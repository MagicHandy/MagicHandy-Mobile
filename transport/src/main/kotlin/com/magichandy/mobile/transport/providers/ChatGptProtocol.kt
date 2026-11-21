package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.SignInNetworkIssue
import com.magichandy.mobile.transport.http.asOneShot
import com.magichandy.mobile.transport.http.boundedText
import com.magichandy.mobile.transport.http.exchange
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** OpenAI's direct sign-in uses the accounts routes. OIDC discovery supplies the signing keys. */
internal class ChatGptProtocol(private val http: Call.Factory) {
    val authorize = "$issuer/api/accounts/authorize".toHttpUrl()

    suspend fun grant(form: FormBody): JsonObject =
        json(
            Request.Builder().url("$issuer/api/accounts/oauth/token").post(form.asOneShot()).build()
        )

    suspend fun signingKeysUri(): HttpUrl {
        val doc = json(Request.Builder().url("$issuer/.well-known/openid-configuration").build())
        if (doc.string("issuer") != issuer) throw AccountRejected()
        fun endpoint(name: String): HttpUrl {
            val url = doc.string(name)?.toHttpUrlOrNull() ?: throw AccountRejected()
            if (
                !url.isHttps ||
                    url.host != authorize.host ||
                    url.port != 443 ||
                    url.username != "" ||
                    url.password != "" ||
                    url.fragment != null
            )
                throw AccountRejected()
            return url
        }
        endpoint("authorization_endpoint")
        endpoint("token_endpoint")
        return endpoint("jwks_uri")
    }

    suspend fun signingKeys(uri: HttpUrl): JsonObject = json(Request.Builder().url(uri).build())

    private suspend fun json(request: Request): JsonObject =
        http.exchange(request) { response ->
            if (!response.isSuccessful) {
                if (response.code in 400..499 && response.code !in setOf(408, 425, 429))
                    throw AccountRejected(response.code)
                throw AccountUnavailable(response.code)
            }
            parseAccountObject(response.boundedText(1L shl 20))
        }
}

internal fun parseAccountObject(value: String): JsonObject =
    try {
        Json.parseToJsonElement(value) as? JsonObject ?: throw AccountRejected()
    } catch (_: SerializationException) {
        throw AccountRejected()
    }

internal class AccountRejected(val status: Int? = null) :
    IOException("Account response was rejected")

internal class AccountUnavailable(val status: Int) : IOException("Account service unavailable")

/**
 * Exception messages can contain addresses. Only the failure type crosses the transport boundary.
 */
internal fun IOException.signInNetworkIssue(): SignInNetworkIssue? =
    generateSequence<Throwable>(this) { it.cause }
        .firstNotNullOfOrNull {
            when (it) {
                is UnknownHostException -> SignInNetworkIssue.NameResolution
                is SSLException -> SignInNetworkIssue.SecureConnection
                is InterruptedIOException -> SignInNetworkIssue.Timeout
                is SocketException,
                is EOFException -> SignInNetworkIssue.ConnectionLost
                else -> null
            }
        }
