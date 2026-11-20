// Ported from internal/llm/{openai_responses,prompt_budget}.go at 9a4c36cf.
package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.ModelProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.transport.http.exchange
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

/** The account vault can refresh its bearer before the request is built. */
public fun interface AccessTokenSource {
    /** A usable token, or null when the connection needs sign-in. */
    public suspend fun token(): Secret?
}

/** Text-only ChatGPT plan Responses client. A terminal completed event is required to commit. */
public class ResponsesProvider
internal constructor(
    private val http: Call.Factory,
    private val tokens: AccessTokenSource,
    private val model: String = "gpt-6.1-terra",
) : ModelProvider {
    /**
     * Use the registered account's access token and a redirect-free, non-retrying HTTP connection.
     */
    public constructor(
        client: OkHttpClient,
        tokens: AccessTokenSource,
        model: String = "gpt-6.1-terra",
    ) : this(
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build() as Call.Factory,
        tokens,
        model,
    )

    override suspend fun stream(
        messages: List<TextMessage>,
        preview: (String) -> Unit,
    ): TextResult {
        if (messages.isEmpty() || messages.last().role != TextRole.User)
            return TextResult.Interrupted
        val size = messages.sumOf { it.text.encodeToByteArray().size.toLong() + 64 }
        if (size > 32768 - 2048 - 256) return TextResult.Interrupted
        return try {
            val token = tokens.token() ?: return TextResult.Interrupted
            val body = buildJsonObject {
                put("model", model)
                put("stream", true)
                put("store", false)
                put("reasoning", buildJsonObject { put("effort", "low") })
                put(
                    "input",
                    buildJsonArray {
                        for (message in messages) add(
                            buildJsonObject {
                                put("role", message.role.name.lowercase())
                                put("content", message.text)
                            }
                        )
                    },
                )
            }
            val request =
                Request.Builder()
                    .url("https://api.openai.com/v1/responses")
                    .header("Authorization", "Bearer " + token.reveal())
                    .header("Accept", "text/event-stream, application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            http.exchange(request) { response ->
                if (
                    !response.isSuccessful || response.body.contentType()?.subtype != "event-stream"
                )
                    TextResult.Interrupted
                else readStream(response.body.source(), preview)
            }
        } catch (_: IOException) {
            DiagnosticReporter.record(DiagnosticKind.ProviderUnavailable)
            TextResult.Interrupted
        } catch (_: SerializationException) {
            TextResult.Interrupted
        }
    }
}

private const val maximumReplyBytes = 1 shl 20

private fun readStream(source: BufferedSource, preview: (String) -> Unit): TextResult {
    val output = StringBuilder()
    val data = mutableListOf<String>()
    var inputBytes = 0L
    var replyBytes = 0
    while (!source.exhausted()) {
        val line = source.readUtf8LineStrict(maximumReplyBytes.toLong())
        inputBytes += line.encodeToByteArray().size + 1
        if (inputBytes > 4L * maximumReplyBytes) return TextResult.Interrupted
        if (line.startsWith("data:")) data += line.removePrefix("data:").trimStart()
        if (line.isNotEmpty() || data.isEmpty()) continue
        val event =
            Json.parseToJsonElement(data.joinToString("\n")) as? JsonObject
                ?: return TextResult.Interrupted
        data.clear()
        when ((event["type"] as? JsonPrimitive)?.content) {
            "response.output_text.delta" -> {
                val delta =
                    (event["delta"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: return TextResult.Interrupted
                replyBytes += delta.encodeToByteArray().size
                if (replyBytes > maximumReplyBytes) return TextResult.Interrupted
                output.append(delta)
                preview(delta)
            }
            "response.refusal.delta",
            "response.refusal.done" -> return TextResult.Refused
            "error",
            "response.failed",
            "response.incomplete" -> return TextResult.Interrupted
            "response.completed" -> {
                val result = event["response"] as? JsonObject ?: return TextResult.Interrupted
                val refused =
                    (result["output"] as? JsonArray).orEmpty().any { item ->
                        ((item as? JsonObject)?.get("content") as? JsonArray).orEmpty().any {
                            ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.content ==
                                "refusal"
                        }
                    }
                if (refused) return TextResult.Refused
                if (
                    (result["status"] as? JsonPrimitive)?.content != "completed" || output.isEmpty()
                )
                    return TextResult.Interrupted
                return TextResult.Completed(output.toString())
            }
        }
    }
    return TextResult.Interrupted
}
