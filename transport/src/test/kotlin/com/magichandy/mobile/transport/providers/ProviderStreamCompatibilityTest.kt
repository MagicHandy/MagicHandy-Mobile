package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer

internal class ProviderStreamCompatibilityTest {
    private val origin = "https://fixture.invalid/".toHttpUrl()
    private val messages = listOf(TextMessage(TextRole.User, "Fixture"))

    @Test
    fun `compatible SSE metadata and multiline frames preserve explicit terminal markers`() {
        val body =
            "\uFEFF: heartbeat\r\n\r\n" +
                """data: {"choices":null,"error":null}""" +
                "\r\n\r\n" +
                """data: {"choices":[""" +
                "\r\n" +
                """data: {"index":0,"delta":{"content":"hello"},"finish_reason":""}]}""" +
                "\r\n\r\n"
        val end =
            """data: {"choices":[{"index":0,"delta":{"content":null},"finish_reason":"stop"}]}""" +
                "\n\n"
        val done = "data: [DONE]\n\n"
        assertEquals(
            TextResult.Completed("hello"),
            compatibleStream(Buffer().writeUtf8(body + end + done)) {},
        )
        for (partial in listOf(body + done, body + end, body + end + "data: [DONE]\n")) {
            assertEquals(TextResult.Interrupted, compatibleStream(Buffer().writeUtf8(partial)) {})
        }
    }

    @Test
    fun `Ollama null metadata and blank lines do not add text or replace a terminal frame`() =
        runTest {
            val metadata =
                "\uFEFF\n" + """{"message":{"content":null},"error":null,"done":false}""" + "\n\n"
            val delta = """{"message":{"content":"hello"},"done":false}""" + "\n"
            suspend fun receive(wire: String): TextResult {
                val provider =
                    OllamaProvider(
                        MemoryHttp {
                            assertTrue(requireNotNull(it.body).isOneShot())
                            it.reply(wire)
                        },
                        origin,
                        "sample",
                        16384,
                    )
                return provider.stream(messages) {}
            }
            for (reason in listOf("null", "\"\"", "\"stop\"")) {
                val terminal =
                    """{"message":{"content":null},"done":true,"done_reason":$reason}""" + "\n"
                assertEquals(TextResult.Completed("hello"), receive(metadata + delta + terminal))
            }
            assertEquals(TextResult.Interrupted, receive(metadata + delta))
            assertEquals(
                TextResult.Interrupted,
                receive(metadata + delta + """{"done":true,"done_reason":"length"}""" + "\n"),
            )
            assertEquals(
                TextResult.Interrupted,
                receive(metadata + delta + """{"error":"model failed","done":true}""" + "\n"),
            )
        }

    @Test
    fun `OpenRouter honors advertised reasoning and tolerates null optional catalog metadata`() =
        runTest {
            for (enabled in listOf(false, true)) {
                val parameters = if (enabled) """["reasoning"]""" else "null"
                val http = MemoryHttp { request ->
                    if (request.url.encodedPath.endsWith("models")) {
                        request.reply(
                            """{"data":[null,{"id":"sample","supported_parameters":$parameters}]}"""
                        )
                    } else {
                        val payload = Json.parseToJsonElement(request.payload()).jsonObject
                        assertTrue(requireNotNull(request.body).isOneShot())
                        assertEquals(
                            if (enabled) JsonPrimitive(true) else null,
                            payload["reasoning"]?.jsonObject?.get("enabled"),
                        )
                        request.reply(
                            """data: {"choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}""" +
                                "\n\ndata: [DONE]\n\n"
                        )
                    }
                }
                val peer =
                    CompatibleProvider(
                        http,
                        origin,
                        "sample",
                        AccessTokenSource { Secret("test") },
                        enabled,
                        16384,
                    )
                assertEquals(TextResult.Completed("hi"), peer.stream(messages) {})
            }
        }
}
