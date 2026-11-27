// Ported from internal/llm/{openai_responses,prompt_budget}.go at 1b80d72e.
package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.chat.PromptBudget
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.transport.http.asOneShot
import com.magichandy.mobile.transport.http.exchange
import com.magichandy.mobile.transport.http.hasEventStream
import com.magichandy.mobile.transport.http.utf8Bom
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

    /** A 401 for this particular access token, delivered outside the OkHttp response callback. */
    public suspend fun rejected(token: Secret) {}
}

/** Text-only ChatGPT plan Responses client. A terminal completed event is required to commit. */
public class ResponsesProvider
internal constructor(
    private val http: Call.Factory,
    private val tokens: AccessTokenSource,
    private val model: String = "gpt-6.1-terra",
    contextTokens: Int = 65536,
) : StructuredProvider {
    private val budget = PromptBudget(maxOf(65536, contextTokens))

    /**
     * Use the registered account's access token and a redirect-free, non-retrying HTTP connection.
     */
    public constructor(
        client: OkHttpClient,
        tokens: AccessTokenSource,
        model: String = "gpt-6.1-terra",
        contextTokens: Int = 65536,
    ) : this(
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build() as Call.Factory,
        tokens,
        model,
        contextTokens,
    )

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
        if (messages.isEmpty() || messages.last().role != TextRole.User)
            return TextResult.Interrupted
        val admitted =
            budget.admit(messages, preserveTailMessages = contract?.preserveTail ?: 1)
                ?: return TextResult.Failed(
                    ProviderReplyFailure(ProviderReplyFailure.Reason.ContextLimit)
                )
        return try {
            val token =
                tokens.token()
                    ?: return TextResult.Failed(
                        ProviderReplyFailure(ProviderReplyFailure.Reason.SignInRequired)
                    )
            val body = buildJsonObject {
                put("model", model)
                put("stream", true)
                put("store", false)
                put("reasoning", buildJsonObject { put("effort", "low") })
                contract?.let {
                    put(
                        "text",
                        buildJsonObject {
                            put(
                                "format",
                                buildJsonObject {
                                    put("type", "json_schema")
                                    put("name", it.name)
                                    put("strict", true)
                                    put("schema", it.schema)
                                },
                            )
                        },
                    )
                }
                put(
                    "input",
                    buildJsonArray {
                        for (message in admitted.messages) add(
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
                    .post(
                        body.toString().toRequestBody("application/json".toMediaType()).asOneShot()
                    )
                    .build()
            var unauthorized = false
            val result =
                http.exchange(request) { response ->
                    unauthorized = response.code == 401
                    if (!response.isSuccessful) return@exchange response.responsesFailure()
                    val source = response.body.source()
                    if (response.hasEventStream()) readStream(source, preview)
                    else
                        TextResult.Failed(
                            ProviderReplyFailure(ProviderReplyFailure.Reason.UnexpectedResponse)
                        )
                }
            if (unauthorized) tokens.rejected(token)
            result
        } catch (_: IOException) {
            DiagnosticReporter.record(DiagnosticKind.ProviderUnavailable)
            TextResult.Failed(ProviderReplyFailure(ProviderReplyFailure.Reason.ServiceUnavailable))
        } catch (_: SerializationException) {
            TextResult.Failed(ProviderReplyFailure(ProviderReplyFailure.Reason.UnexpectedResponse))
        }
    }
}

private const val maximumReplyBytes = 1 shl 20

private fun readStream(source: BufferedSource, preview: (String) -> Unit): TextResult {
    if (source.rangeEquals(0, utf8Bom)) source.skip(utf8Bom.size.toLong())
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
            "error" ->
                return responsesFailure(
                    null,
                    event.string("code") ?: (event["error"] as? JsonObject)?.string("code"),
                )
            "response.failed" -> {
                val response = event["response"] as? JsonObject
                return responsesFailure(
                    null,
                    (response?.get("error") as? JsonObject)?.string("code"),
                )
            }
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
