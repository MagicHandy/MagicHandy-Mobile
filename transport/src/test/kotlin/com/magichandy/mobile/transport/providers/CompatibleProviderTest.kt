package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

internal class CompatibleProviderTest {
    private fun sse(content: String, finish: String) =
        """data: {"choices":[{"index":0,"delta":{"content":"$content"},"finish_reason":$finish}]}""" +
            "\n\n"

    @Test
    fun `only a completed first choice followed by DONE can authorize output`() {
        val good = sse("ok", "\"stop\"")
        val done = "data: [DONE]\n\n"
        assertEquals(
            TextResult.Completed("ok"),
            compatibleStream(Buffer().writeUtf8(good + done)) {},
        )
        for (raw in
            listOf(
                done,
                good,
                sse("a", "null") + done,
                good + sse("late", "null") + done,
                sse("a", "\"length\"") + done,
            )) assertEquals(TextResult.Interrupted, compatibleStream(Buffer().writeUtf8(raw)) {})
        assertEquals(
            TextResult.Refused,
            compatibleStream(Buffer().writeUtf8(sse("", "\"content_filter\""))) {},
        )
    }

    @Test
    fun `catalog schema and reasoning are negotiated once before hosted completion`() = runTest {
        val paths = mutableListOf<String>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val r = chain.request()
                    paths += r.url.encodedPath
                    assertEquals("Bearer synthetic", r.header("Authorization"))
                    val body =
                        if (r.url.encodedPath.endsWith("models"))
                            """{"data":[{"id":"sample","reasoning":{"mandatory":false},"supported_parameters":["structured_outputs","max_tokens"]}]}"""
                        else {
                            val buf = Buffer()
                            requireNotNull(r.body).writeTo(buf)
                            val json = Json.parseToJsonElement(buf.readUtf8()).jsonObject
                            assertEquals(
                                JsonPrimitive(true),
                                json.getValue("reasoning").jsonObject["enabled"],
                            )
                            assertEquals(
                                JsonPrimitive("json_schema"),
                                json.getValue("response_format").jsonObject["type"],
                            )
                            sse("ok", "\"stop\"") + "data: [DONE]\n\n"
                        }
                    Response.Builder()
                        .request(r)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val peer =
            CompatibleProvider(
                http,
                "https://offline.invalid/v1/".toHttpUrl(),
                "sample",
                AccessTokenSource { Secret("synthetic") },
                true,
                65536,
            )
        repeat(2) {
            assertEquals(
                TextResult.Completed("ok"),
                peer.complete(
                    ModelRequest(
                        listOf(TextMessage(TextRole.User, "Synthetic.")),
                        JsonObject(emptyMap()),
                    )
                ),
            )
        }
        assertEquals(listOf("/v1/models", "/v1/chat/completions", "/v1/chat/completions"), paths)
    }
}
