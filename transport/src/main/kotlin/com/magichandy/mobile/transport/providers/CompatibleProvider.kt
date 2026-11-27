// Ported from internal/llm/{compatible,openrouter_reasoning}.go at 1b80d72e.
package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.api.ProviderReplyFailure.Reason
import com.magichandy.mobile.core.api.ReasoningChoice
import com.magichandy.mobile.core.api.ReasoningEffort
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.chat.PromptBudget
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.transport.http.asOneShot
import com.magichandy.mobile.transport.http.boundedText
import com.magichandy.mobile.transport.http.exchange
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

/** OpenRouter's catalog negotiates schema and reasoning support before sending chat content. */
public class CompatibleProvider
internal constructor(
    private val http: Call.Factory,
    private val base: HttpUrl,
    private val model: String,
    private val token: AccessTokenSource?,
    private val reasoning: Boolean,
    private val context: Int,
    private val effort: ReasoningEffort? = null,
) : StructuredProvider {
    /**
     * What the catalog says about the model. [reasoning] is null when it doesn't reason, and
     * [contextTokens] and [replyTokens] are zero when OpenRouter doesn't say.
     */
    private data class Capabilities(
        val parameters: Set<String>,
        val reasoning: RouterReasoning?,
        val contextTokens: Int = 0,
        val replyTokens: Int = 0,
    )

    private class CatalogFailure(val result: TextResult.Failed) : IOException()

    private val lock = Mutex()
    private var cached: Capabilities? = null
    private var sampled = 0L

    private suspend fun capabilities(auth: Secret?): Capabilities = lock.withLock {
        if (token == null)
            return@withLock Capabilities(setOf("structured_outputs", "max_tokens"), null)
        cached
            ?.takeIf { System.nanoTime() - sampled < TimeUnit.MINUTES.toNanos(15) }
            ?.let {
                return@withLock it
            }
        val row = modelRow(auth)
        val top = row["top_provider"] as? JsonObject
        val result =
            Capabilities(
                row.supportedParameters(),
                row.routerReasoning(),
                (row["context_length"] as? JsonPrimitive)?.intOrNull ?: 0,
                (top?.get("max_completion_tokens") as? JsonPrimitive)?.intOrNull ?: 0,
            )
        cached = result
        sampled = System.nanoTime()
        result
    }

    private suspend fun modelRow(auth: Secret?): JsonObject {
        val slug = model.split('/')
        if (slug.size == 2 && slug.all { it.isNotEmpty() && it != "." && it != ".." }) {
            val url =
                base
                    .newBuilder()
                    .addPathSegment("model")
                    .addPathSegment(slug[0])
                    .addPathSegment(slug[1])
                    .build()
            val exact =
                http.exchange(request(url, auth).get().build()) { response ->
                    if (response.code == 404) return@exchange null
                    if (!response.isSuccessful)
                        throw CatalogFailure(compatibleFailure(response.code))
                    Json.parseToJsonElement(response.boundedText(1L shl 20)).jsonObject["data"]
                        as? JsonObject
                }
            if (exact?.get("id") == JsonPrimitive(model)) return exact
        }
        // Older saved identifiers can be aliases. Keep negotiation for JSON-only legacy models.
        val url =
            base
                .newBuilder()
                .addPathSegment("models")
                .addQueryParameter("zdr", "true")
                .addQueryParameter("sort", "most-popular")
                .addQueryParameter("limit", "1000")
                .build()
        return http.exchange(request(url, auth).get().build()) { response ->
            if (!response.isSuccessful) throw CatalogFailure(compatibleFailure(response.code))
            val rows =
                Json.parseToJsonElement(response.boundedText(8L shl 20))
                    .jsonObject
                    .getValue("data")
                    .jsonArray
            rows.filterIsInstance<JsonObject>().firstOrNull { it["id"] == JsonPrimitive(model) }
                ?: throw CatalogFailure(
                    TextResult.Failed(ProviderReplyFailure(Reason.ModelUnavailable))
                )
        }
    }

    private fun request(url: HttpUrl, auth: Secret?): Request.Builder =
        Request.Builder().url(url).apply {
            if (auth != null) header("Authorization", "Bearer " + auth.reveal())
        }

    override suspend fun stream(
        messages: List<TextMessage>,
        preview: (String) -> Unit,
    ): TextResult = send(messages, null, preview)

    override suspend fun complete(request: ModelRequest, preview: (String) -> Unit): TextResult =
        send(request.messages, request, preview)

    private suspend fun send(
        messages: List<TextMessage>,
        contract: ModelRequest?,
        preview: (String) -> Unit,
    ): TextResult {
        return try {
            val auth =
                if (token != null)
                    token.token()
                        ?: return TextResult.Failed(
                            ProviderReplyFailure(Reason.CredentialsRejected)
                        )
                else null
            val caps = capabilities(auth)
            // A saved choice the model can't honour is settled rather than refused: a model that
            // always reasons gets its lowest level, and one that can't reason gets none.
            val choice =
                caps.reasoning?.options?.settle(reasoning, effort)
                    ?: ReasoningChoice(isOn = false, level = null)
            val baseOutput = structuredOutputAllowance(contract, if (auth == null) 256 else 1536)
            // The model's own limits, when the catalog gives them, cap what is asked for.
            val window = caps.contextTokens.takeIf { it > 0 }?.let { minOf(it, context) } ?: context
            val reasoningTokens =
                if (auth != null && choice.isOn) reasoningReserve(choice.level, window) else 0
            val output =
                (baseOutput + reasoningTokens).let { wanted ->
                    caps.replyTokens.takeIf { it > 0 }?.let { minOf(it, wanted) } ?: wanted
                }
            val budget =
                PromptBudget(
                    window,
                    output,
                    schemaBytes = contract?.schema?.toString()?.encodeToByteArray()?.size ?: 0,
                )
            val admitted =
                budget.admit(messages, preserveTailMessages = contract?.preserveTail ?: 1)
                    ?: return TextResult.Failed(
                        ProviderReplyFailure(ProviderReplyFailure.Reason.ContextLimit)
                    )
            val body = buildJsonObject {
                put("model", model)
                put("stream", true)
                put(
                    "messages",
                    JsonArray(
                        admitted.messages.map { m ->
                            buildJsonObject {
                                put(
                                    "role",
                                    if (m.role == TextRole.Developer) "system"
                                    else m.role.name.lowercase(),
                                )
                                put("content", m.text)
                            }
                        }
                    ),
                )
                val maximum =
                    if ("max_completion_tokens" in caps.parameters) "max_completion_tokens"
                    else "max_tokens"
                if (maximum in caps.parameters) put(maximum, output)
                if (auth != null) {
                    put(
                        "provider",
                        buildJsonObject {
                            // A fallback host still meets every rule here, so it only adds
                            // reliability.
                            put("allow_fallbacks", true)
                            put("require_parameters", true)
                            put("data_collection", "deny")
                            put("zdr", true)
                            // Motion waits for the whole reply, so the fastest host is chosen
                            // (desktop ADR 0065: 0.9 s instead of 8.6 s for DeepSeek).
                            put("sort", "throughput")
                        },
                    )
                    put("transforms", JsonArray(emptyList()))
                    if ("reasoning" in caps.parameters)
                        caps.reasoning?.let { put("reasoning", it.request(choice)) }
                }
                if (contract != null && "structured_outputs" in caps.parameters)
                    put(
                        "response_format",
                        buildJsonObject {
                            put("type", "json_schema")
                            put(
                                "json_schema",
                                buildJsonObject {
                                    put("name", "magichandy_response")
                                    if (token != null) put("strict", true)
                                    put("schema", contract.schema)
                                },
                            )
                        },
                    )
                else if (contract != null && "response_format" in caps.parameters)
                    put("response_format", buildJsonObject { put("type", "json_object") })
            }
            val call =
                request(requireNotNull(base.resolve("chat/completions")), auth)
                    .header("Accept", "text/event-stream")
                    .post(
                        body.toString().toRequestBody("application/json".toMediaType()).asOneShot()
                    )
                    .build()
            http.exchange(call) { res ->
                if (res.isSuccessful) compatibleStream(res.body.source(), preview)
                else compatibleFailure(res.code)
            }
        } catch (failure: CatalogFailure) {
            failure.result
        } catch (_: IOException) {
            TextResult.Failed(ProviderReplyFailure(Reason.ServiceUnavailable))
        } catch (_: IllegalArgumentException) {
            TextResult.Interrupted
        } catch (_: NoSuchElementException) {
            TextResult.Interrupted
        }
    }

    public companion object {
        /** Construct a credential-isolated, redirect-free hosted client. */
        public fun openRouter(
            client: OkHttpClient,
            token: AccessTokenSource,
            model: String,
            reasoning: Boolean,
            contextTokens: Int = 65536,
            effort: ReasoningEffort? = null,
        ): CompatibleProvider =
            CompatibleProvider(
                client
                    .newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .callTimeout(90, TimeUnit.SECONDS)
                    .build(),
                "https://openrouter.ai/api/v1/".toHttpUrl(),
                model,
                token,
                reasoning,
                contextTokens,
                effort,
            )
    }
}

/**
 * OpenRouter's `reasoning` object for [choice]: Off as `effort: "none"` where the model takes it
 * and `enabled: false` elsewhere, a level as its effort, and on without a level as `enabled: true`.
 * The reasoning itself is never shown, so it isn't sent back: that saves data and keeps long
 * reasoning within the stream's size limit.
 */
internal fun RouterReasoning.request(choice: ReasoningChoice): JsonObject = buildJsonObject {
    val level = choice.level
    when {
        !choice.isOn && acceptsNone -> put("effort", "none")
        !choice.isOn -> put("enabled", false)
        level != null -> put("effort", level.wireName)
        else -> put("enabled", true)
    }
    if (choice.isOn) put("exclude", true)
}

/**
 * The tokens kept for reasoning beside the reply, by level, as the desktop sizes them (ADR 0065):
 * 2,048 for minimal, 6,144 for low and 8,192 for medium and above or the model's own level, never
 * more than a quarter of [context].
 */
internal fun reasoningReserve(level: ReasoningEffort?, context: Int): Int {
    val reserve =
        when (level) {
            ReasoningEffort.Minimal -> 2048
            ReasoningEffort.Low -> 6144
            else -> 8192
        }
    return minOf(reserve, context / 4)
}

internal fun structuredOutputAllowance(contract: ModelRequest?, floor: Int): Int =
    contract?.outputAllowance?.takeIf { it > 0 }?.let { maxOf(floor, it) } ?: 1536

internal fun compatibleStream(source: BufferedSource, preview: (String) -> Unit): TextResult {
    val body = StringBuilder()
    var read = 0L
    var finished = false
    val fields = mutableListOf<String>()
    while (!source.exhausted()) {
        val raw = source.readUtf8LineStrict(1L shl 20)
        val line = if (read == 0L) raw.removePrefix("\uFEFF") else raw
        read += line.encodeToByteArray().size + 1
        if (read > 4L shl 20) return TextResult.Interrupted
        if (line.startsWith("data:")) fields += line.removePrefix("data:").removePrefix(" ")
        if (line.isNotEmpty() || fields.isEmpty()) continue
        val data = fields.joinToString("\n").trim()
        fields.clear()
        if (data.isEmpty()) continue
        if (data == "[DONE]")
            return if (finished && body.isNotEmpty()) TextResult.Completed(body.toString())
            else TextResult.Interrupted
        val chunk = Json.parseToJsonElement(data).jsonObject
        chunk.streamFailure()?.let {
            return it
        }
        val choices = chunk["choices"]?.takeUnless { it == JsonNull }?.jsonArray.orEmpty()
        for (item in choices) {
            val choice = item.jsonObject
            choice.streamFailure()?.let {
                return it
            }
            if (choice["index"]?.jsonPrimitive?.intOrNull != 0) return TextResult.Interrupted
            val delta = choice["delta"] as? JsonObject
            val reason =
                choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (reason == "error") return serviceFailure
            val content = delta?.get("content")?.takeUnless { it == JsonNull }
            val text =
                if (content == null) ""
                else
                    (content as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: return TextResult.Interrupted
            if (
                reason == "content_filter" ||
                    !(delta?.get("refusal") as? JsonPrimitive)?.contentOrNull.isNullOrEmpty()
            )
                return TextResult.Refused
            if (
                finished && text.isNotEmpty() ||
                    reason != null && reason != "stop" ||
                    body.length + text.length > 1 shl 20
            )
                return TextResult.Interrupted
            body.append(text)
            if (text.isNotEmpty()) preview(text)
            if (reason == "stop") finished = true
        }
    }
    return TextResult.Interrupted
}

/** A host that fails partway through a reply: sending again may reach another one. */
private val serviceFailure =
    TextResult.Failed(ProviderReplyFailure(ProviderReplyFailure.Reason.ServiceUnavailable))

/** An SSE error code describes the provider failure inside an already successful HTTP exchange. */
private fun JsonObject.streamFailure(): TextResult.Failed? {
    val error = this["error"]?.takeUnless { it == JsonNull } ?: return null
    val code = ((error as? JsonObject)?.get("code") as? JsonPrimitive)?.intOrNull
    if (code == null || code !in 400..599) return serviceFailure
    return TextResult.Failed(ProviderReplyFailure(compatibleFailure(code).error.reason))
}
