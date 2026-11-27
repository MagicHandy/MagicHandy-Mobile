package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.RouterModel
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.KeyCheck
import com.magichandy.mobile.core.ports.OpenRouterAccess
import com.magichandy.mobile.core.ports.isValidOpenRouterKey
import com.magichandy.mobile.transport.http.boundedText
import com.magichandy.mobile.transport.http.exchange
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OpenRouter's catalog and key check (docs/openrouter-design.md §4.1, §4.2).
 *
 * The catalog request carries no key, so it works before one is entered. It asks OpenRouter only
 * for models that support structured outputs and have a host that keeps no data, the same rules
 * every chat request sets, most popular first. The key check sends the key alone. Neither spends
 * credits.
 */
public class OpenRouterAccount
internal constructor(
    private val http: Call.Factory,
    private val base: HttpUrl,
    private val nanoTime: () -> Long = System::nanoTime,
) : OpenRouterAccess {
    private val lock = Mutex()
    private var cached: List<RouterModel>? = null
    private var sampled = 0L

    override suspend fun models(): List<RouterModel> = lock.withLock {
        cached
            ?.takeIf { nanoTime() - sampled < TimeUnit.MINUTES.toNanos(CACHE_MINUTES) }
            ?.let {
                return@withLock it
            }
        val url =
            base
                .newBuilder()
                .addPathSegment("models")
                .addQueryParameter("supported_parameters", "structured_outputs")
                .addQueryParameter("zdr", "true")
                .addQueryParameter("sort", "most-popular")
                // Without a limit, OpenRouter may stop at 500 models.
                .addQueryParameter("limit", "1000")
                .build()
        val models =
            http.exchange(Request.Builder().url(url).get().build()) { response ->
                if (!response.isSuccessful) throw IOException("Catalog unavailable")
                routerModels(response.boundedText(CATALOG_BYTES))
            }
        cached = models
        sampled = nanoTime()
        models
    }

    override suspend fun check(key: Secret): KeyCheck {
        if (!key.isValidOpenRouterKey()) return KeyCheck.Rejected
        val request =
            Request.Builder()
                .url(requireNotNull(base.resolve("key")))
                .header("Authorization", "Bearer " + key.reveal())
                .get()
                .build()
        return try {
            http.exchange(request) { response ->
                when (response.code) {
                    in 200..299 -> keyResult(response.boundedText(KEY_BYTES))
                    401,
                    403 -> KeyCheck.Rejected
                    else -> KeyCheck.Unreachable
                }
            }
        } catch (_: IOException) {
            KeyCheck.Unreachable
        }
    }

    public companion object {
        private const val CACHE_MINUTES = 15L
        private const val CATALOG_BYTES = 8L shl 20
        private const val KEY_BYTES = 64L shl 10

        /** A management key manages other keys and can't chat, so it doesn't count as accepted. */
        private fun keyResult(text: String): KeyCheck {
            val data =
                try {
                    (Json.parseToJsonElement(text) as? JsonObject)?.get("data") as? JsonObject
                } catch (_: SerializationException) {
                    null
                } ?: return KeyCheck.Unreachable
            val flags = listOfNotNull(data["is_management_key"], data["is_provisioning_key"])
            if (JsonPrimitive(true) in flags) return KeyCheck.Rejected
            if (flags.any { it != JsonPrimitive(false) }) return KeyCheck.Unreachable
            val label = data["label"] as? JsonPrimitive
            return if (flags.isNotEmpty() || label?.isString == true) KeyCheck.Accepted
            else KeyCheck.Unreachable
        }

        /** A client for openrouter.ai that never follows a redirect or retries on its own. */
        public fun create(client: OkHttpClient): OpenRouterAccount =
            OpenRouterAccount(
                client
                    .newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .callTimeout(30, TimeUnit.SECONDS)
                    .build(),
                "https://openrouter.ai/api/v1/".toHttpUrl(),
            )
    }
}
