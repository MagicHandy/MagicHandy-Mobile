// Ported from internal/llm/{compatible,openrouter_reasoning}.go at 1b80d72e.
package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.chat.PromptBudget
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.transport.http.boundedText
import com.magichandy.mobile.transport.http.exchange
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
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
) : StructuredProvider {
    private data class Capabilities(
        val parameters: Set<String>,
        val reasoning: Boolean,
        val mandatory: Boolean,
    )

    private val lock = Mutex()
    private var cached: Capabilities? = null
    private var sampled = 0L

    private suspend fun capabilities(auth: String?): Capabilities = lock.withLock {
        if (token == null)
            return@withLock Capabilities(setOf("structured_outputs", "max_tokens"), false, false)
        cached
            ?.takeIf { System.nanoTime() - sampled < TimeUnit.MINUTES.toNanos(15) }
            ?.let {
                return@withLock it
            }
        val result =
            http.exchange(request("models", auth).get().build()) { res ->
                if (!res.isSuccessful) throw IOException("Catalog unavailable")
                val rows =
                    Json.parseToJsonElement(res.boundedText(8L shl 20))
                        .jsonObject
                        .getValue("data")
                        .jsonArray
                val row =
                    rows.firstOrNull { it.jsonObject["id"] == JsonPrimitive(model) }?.jsonObject
                        ?: throw IOException("Model unavailable")
                val options = row["reasoning"] as? JsonObject
                Capabilities(
                    row["supported_parameters"]
                        ?.jsonArray
                        .orEmpty()
                        .map { it.jsonPrimitive.content }
                        .toSet(),
                    options != null,
                    options?.get("mandatory") == JsonPrimitive(true),
                )
            }
        cached = result
        sampled = System.nanoTime()
        result
    }

    private fun request(path: String, auth: String?): Request.Builder =
        Request.Builder().url(requireNotNull(base.resolve(path))).apply {
            if (auth != null) header("Authorization", "Bearer " + auth)
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
                if (token != null) token.token()?.reveal() ?: return TextResult.Interrupted
                else null
            val caps = capabilities(auth)
            if (caps.mandatory && !reasoning || auth != null && reasoning && !caps.reasoning)
                return TextResult.Interrupted
            val output = 1536 + if (auth != null && reasoning) minOf(8192, context / 4) else 0
            val budget =
                PromptBudget(
                    context,
                    output,
                    schemaBytes = contract?.schema?.toString()?.encodeToByteArray()?.size ?: 0,
                )
            val admitted =
                budget.admit(messages, preserveTailMessages = contract?.preserveTail ?: 1)
                    ?: return TextResult.Interrupted
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
                            put("allow_fallbacks", false)
                            put("require_parameters", true)
                            put("data_collection", "deny")
                            put("zdr", true)
                        },
                    )
                    put("transforms", JsonArray(emptyList()))
                    if (caps.reasoning)
                        put("reasoning", buildJsonObject { put("enabled", reasoning) })
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
                request("chat/completions", auth)
                    .header("Accept", "text/event-stream")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            http.exchange(call) { res ->
                if (res.isSuccessful) compatibleStream(res.body.source(), preview)
                else TextResult.Interrupted
            }
        } catch (_: IOException) {
            TextResult.Interrupted
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
            )
    }
}

internal fun compatibleStream(source: BufferedSource, preview: (String) -> Unit): TextResult {
    val body = StringBuilder()
    var read = 0L
    var finished = false
    while (!source.exhausted()) {
        val line = source.readUtf8LineStrict(1L shl 20)
        read += line.encodeToByteArray().size + 1
        if (read > 4L shl 20) return TextResult.Interrupted
        if (!line.startsWith("data:")) continue
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]")
            return if (finished && body.isNotEmpty()) TextResult.Completed(body.toString())
            else TextResult.Interrupted
        val chunk = Json.parseToJsonElement(data).jsonObject
        if (chunk["error"]?.let { it != JsonNull } == true) return TextResult.Interrupted
        for (item in chunk["choices"]?.jsonArray.orEmpty()) {
            val choice = item.jsonObject
            if (choice["index"]?.jsonPrimitive?.intOrNull != 0) return TextResult.Interrupted
            val delta = choice["delta"] as? JsonObject
            val reason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
            val text = (delta?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
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
