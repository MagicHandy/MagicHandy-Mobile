package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.transport.http.exchange
import java.io.IOException
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
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
        send(request.messages, request.schema, preview)

    private suspend fun send(
        messages: List<TextMessage>,
        schema: JsonObject?,
        preview: (String) -> Unit,
    ): TextResult {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put(
                "options",
                buildJsonObject {
                    put("num_ctx", context)
                    put("num_predict", 1536)
                },
            )
            schema?.let { put("format", it) }
            put(
                "messages",
                JsonArray(
                    messages.map { m ->
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
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        return try {
            http.exchange(r) { res ->
                if (!res.isSuccessful) return@exchange TextResult.Interrupted
                val source = res.body.source()
                val out = StringBuilder()
                var bytes = 0L
                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict(1L shl 20)
                    bytes += line.encodeToByteArray().size + 1
                    if (bytes > 4L shl 20) return@exchange TextResult.Interrupted
                    val item = Json.parseToJsonElement(line).jsonObject
                    if ("error" in item) return@exchange TextResult.Interrupted
                    val text =
                        (item["message"] as? JsonObject)
                            ?.get("content")
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                    out.append(text)
                    if (out.length > 1 shl 20) return@exchange TextResult.Interrupted
                    if (text.isNotEmpty()) preview(text)
                    if (item["done"] == JsonPrimitive(true))
                        return@exchange if (
                            out.isNotEmpty() &&
                                (item["done_reason"]?.jsonPrimitive?.content ?: "stop") == "stop"
                        )
                            TextResult.Completed(out.toString())
                        else TextResult.Interrupted
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
