package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.chat.PromptBudget
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.transport.http.asOneShot
import com.magichandy.mobile.transport.http.exchange
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class OllamaProvider(
    private val http: Call.Factory,
    private val base: HttpUrl,
    private val model: String,
    private val context: Int,
) : StructuredProvider {
    override suspend fun stream(messages: List<TextMessage>, preview: (String) -> Unit) =
        send(messages, null, preview)

    override suspend fun complete(request: ModelRequest, preview: (String) -> Unit) =
        send(request.messages, request, preview)

    private suspend fun send(
        messages: List<TextMessage>,
        contract: ModelRequest?,
        preview: (String) -> Unit,
    ): TextResult {
        val output = structuredOutputAllowance(contract, 256)
        val budget =
            PromptBudget(
                context,
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
                "options",
                buildJsonObject {
                    put("num_ctx", context)
                    put("num_predict", output)
                },
            )
            contract?.schema?.let { put("format", it) }
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
        }
        val r =
            Request.Builder()
                .url(requireNotNull(base.resolve("api/chat")))
                .post(body.toString().toRequestBody("application/json".toMediaType()).asOneShot())
                .build()
        return try {
            http.exchange(r) { res ->
                if (!res.isSuccessful) return@exchange compatibleFailure(res.code)
                val source = res.body.source()
                val out = StringBuilder()
                var bytes = 0L
                while (!source.exhausted()) {
                    val raw = source.readUtf8LineStrict(1L shl 20)
                    val line = if (bytes == 0L) raw.removePrefix("\uFEFF") else raw
                    bytes += line.encodeToByteArray().size + 1
                    if (bytes > 4L shl 20) return@exchange TextResult.Interrupted
                    if (line.isBlank()) continue
                    val item = Json.parseToJsonElement(line).jsonObject
                    val error = item["error"]
                    if (error != null && error != JsonNull && error != JsonPrimitive(""))
                        return@exchange TextResult.Interrupted
                    val content =
                        (item["message"] as? JsonObject)?.get("content")?.takeUnless {
                            it == JsonNull
                        }
                    val text =
                        if (content == null) ""
                        else
                            (content as? JsonPrimitive)?.takeIf { it.isString }?.content
                                ?: return@exchange TextResult.Interrupted
                    out.append(text)
                    if (out.length > 1 shl 20) return@exchange TextResult.Interrupted
                    if (text.isNotEmpty()) preview(text)
                    if (item["done"] == JsonPrimitive(true)) {
                        val reason = item["done_reason"]?.jsonPrimitive?.contentOrNull
                        return@exchange if (
                            out.isNotEmpty() && (reason.isNullOrBlank() || reason == "stop")
                        )
                            TextResult.Completed(out.toString())
                        else TextResult.Interrupted
                    }
                }
                TextResult.Interrupted
            }
        } catch (_: IOException) {
            TextResult.Interrupted
        } catch (_: IllegalArgumentException) {
            TextResult.Interrupted
        }
    }
}
