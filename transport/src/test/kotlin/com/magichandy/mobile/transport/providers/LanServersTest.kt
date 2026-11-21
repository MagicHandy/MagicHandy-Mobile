package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ServerCheck
import com.magichandy.mobile.core.api.ServerCheckFailure
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient

internal class LanServersTest {
    @Test
    fun `LAN endpoints require private numeric addresses and private DNS answers for HTTP and HTTPS`() =
        runTest {
            val dns =
                object : Dns {
                    override fun lookup(hostname: String) =
                        listOf(
                            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
                            InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
                        )
                }
            val servers = LanServers(OkHttpClient.Builder().dns(dns).build())
            for (origin in
                listOf(
                    "http://192.0.2.1",
                    "https://192.0.2.1",
                    "http://fixture.invalid",
                    "https://fixture.invalid",
                )) {
                assertEquals(
                    ServerCheckFailure.NotPrivateAddress,
                    assertIs<ServerCheck.Failed>(servers.check(origin, null, null)).cause,
                )
            }
        }

    @Test
    fun `only the named server is probed and Ollama has to complete a readiness turn`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().body("""{"models":[{"name":"gemma4:12b"}]}""").build()
            )
            server.enqueue(
                MockResponse.Builder()
                    .body(
                        """{"message":{"content":"{\"ready\":true}"},"done":true,"done_reason":"stop"}""" +
                            "\n"
                    )
                    .build()
            )
            server.start()
            val timeouts = mutableListOf<Long>()
            val peer =
                LanServers(
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            timeouts +=
                                TimeUnit.NANOSECONDS.toSeconds(
                                    chain.call().timeout().timeoutNanos()
                                )
                            if (chain.request().url.encodedPath == "/api/chat") {
                                val payload =
                                    Json.parseToJsonElement(chain.request().payload()).jsonObject
                                assertEquals(JsonPrimitive("gemma4:12b"), payload["model"])
                                assertEquals(
                                    JsonPrimitive("object"),
                                    payload.getValue("format").jsonObject["type"],
                                )
                            }
                            chain.proceed(chain.request())
                        }
                        .build()
                )
            val result =
                assertIs<ServerCheck.Found>(peer.check(server.url("/").toString(), null, null))
            assertEquals(ProviderKind.Ollama, result.provider)
            assertEquals(CapabilityLevel.Intermediate, result.suggested)
            assertNotNull(result.repliedIn)
            assertEquals("/api/tags", server.takeRequest().url.encodedPath)
            assertEquals("/api/chat", server.takeRequest().url.encodedPath)
            assertEquals(5L, timeouts.first())
            assertTrue(timeouts.last() in 6L..30L)
        }
    }

    @Test
    fun `llama cpp and generic compatible servers are identified at their supplied origin`() =
        runTest {
            for (llama in listOf(true, false)) MockWebServer().use { server ->
                fun response(text: String) = MockResponse.Builder().body(text).build()
                server.enqueue(MockResponse.Builder().code(404).build())
                server.enqueue(
                    if (llama) response("""{"default_generation_settings":{"n_ctx":32768}}""")
                    else MockResponse.Builder().code(404).build()
                )
                server.enqueue(response("""{"data":[{"id":"synthetic"}]}"""))
                server.enqueue(
                    response(
                        """data: {"choices":[{"index":0,"delta":{"content":"{\"ready\":true}"},"finish_reason":"stop"}]}""" +
                            "\n\ndata: [DONE]\n\n"
                    )
                )
                server.start()
                val peer =
                    LanServers(
                        OkHttpClient.Builder()
                            .addInterceptor { chain ->
                                if (chain.request().url.encodedPath == "/v1/chat/completions") {
                                    val payload =
                                        Json.parseToJsonElement(chain.request().payload())
                                            .jsonObject
                                    assertEquals(
                                        JsonPrimitive("json_schema"),
                                        payload.getValue("response_format").jsonObject["type"],
                                    )
                                }
                                chain.proceed(chain.request())
                            }
                            .build()
                    )
                val found =
                    assertIs<ServerCheck.Found>(peer.check(server.url("/").toString(), null, null))
                assertNotNull(found.repliedIn)
                assertEquals(
                    if (llama) ProviderKind.LlamaCpp else ProviderKind.OpenAiCompatible,
                    found.provider,
                )
                assertEquals(if (llama) 32768 else null, found.contextAllowed)
                assertEquals(
                    listOf("/api/tags", "/props", "/v1/models", "/v1/chat/completions"),
                    List(4) { server.takeRequest().url.encodedPath },
                )
            }
        }

    @Test
    fun `hosted clients require TLS and a named private server can use HTTP`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client =
                OkHttpClient.Builder()
                    .addInterceptor(com.magichandy.mobile.transport.http.HttpsOnly)
                    .build()
            val url = server.url("/")
            val request = okhttp3.Request.Builder().url(url).build()
            kotlin.test.assertFailsWith<java.io.IOException> {
                client.newCall(request).execute().close()
            }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse.Builder().body("fixture").build())
            originClient(client, url).newCall(request).execute().use { assertEquals(200, it.code) }
        }
    }

    @Test
    fun `the exact selected model must still be available before inference is sent`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("""{"models":[{"name":"other"}]}""").build())
            server.enqueue(MockResponse.Builder().code(400).build())
            server.start()
            val result =
                LanServers(OkHttpClient())
                    .verify(server.url("/").toString(), "removed", CapabilityLevel.Simple)
            assertEquals(
                ServerCheckFailure.ModelUnavailable,
                assertIs<ServerCheck.Failed>(result.check).cause,
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `plain text or a rejected schema does not mark a discovered model ready`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val peer = LanServers(OkHttpClient())
            for (reply in
                listOf(
                    MockResponse.Builder().code(400).build(),
                    MockResponse.Builder()
                        .body("""{"message":{"content":"OK"},"done":true}""" + "\n")
                        .build(),
                    MockResponse.Builder()
                        .body("""{"message":{"content":"{\"ready\":false}"},"done":true}""" + "\n")
                        .build(),
                )) {
                server.enqueue(
                    MockResponse.Builder()
                        .body("""{"models":[{"name":"sample"},{"name":"another"}]}""")
                        .build()
                )
                server.enqueue(reply)
                val found =
                    assertIs<ServerCheck.Found>(peer.check(server.url("/").toString(), null, null))
                assertNull(found.repliedIn)
                assertEquals(ServerCheckFailure.IncompleteReply, found.testFailure)
                assertEquals(listOf("sample", "another"), found.models)
            }
        }
    }

    @Test
    fun `a compatible model declining the readiness turn remains visible with the refusal`() =
        runTest {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(404).build())
                server.enqueue(MockResponse.Builder().code(404).build())
                server.enqueue(
                    MockResponse.Builder().body("""{"data":[{"id":"sample"}]}""").build()
                )
                server.enqueue(
                    MockResponse.Builder()
                        .body(
                            """data: {"choices":[{"index":0,"delta":{},"finish_reason":"content_filter"}]}""" +
                                "\n\n"
                        )
                        .build()
                )
                server.start()
                val assessment =
                    LanServers(OkHttpClient())
                        .verify(server.url("/").toString(), "sample", CapabilityLevel.Simple)
                val found = assertIs<ServerCheck.Found>(assessment.check)
                assertTrue(assessment.refused)
                assertEquals(ServerCheckFailure.ProviderRefused, found.testFailure)
                assertNull(found.repliedIn)
            }
        }
}
