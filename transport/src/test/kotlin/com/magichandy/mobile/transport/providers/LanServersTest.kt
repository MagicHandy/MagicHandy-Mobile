package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.*
import java.net.InetAddress
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.*

internal class LanServersTest {
    @Test
    fun `cleartext is refused for public numeric addresses and mixed DNS answers`() = runTest {
        val dns =
            object : Dns {
                override fun lookup(hostname: String) =
                    listOf(
                        InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
                        InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
                    )
            }
        val servers = LanServers(OkHttpClient.Builder().dns(dns).build())
        for (origin in listOf("http://8.8.8.8", "http://fixture.invalid")) {
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
                        """{"message":{"content":"OK"},"done":true,"done_reason":"stop"}""" + "\n"
                    )
                    .build()
            )
            server.start()
            val peer = LanServers(OkHttpClient())
            val result =
                assertIs<ServerCheck.Found>(peer.check(server.url("/").toString(), null, null))
            assertEquals(ProviderKind.Ollama, result.provider)
            assertEquals(CapabilityLevel.Intermediate, result.suggested)
            assertNotNull(result.repliedIn)
            assertEquals("/api/tags", server.takeRequest().url.encodedPath)
            assertEquals("/api/chat", server.takeRequest().url.encodedPath)
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
                        """data: {"choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":"stop"}]}""" +
                            "\n\ndata: [DONE]\n\n"
                    )
                )
                server.start()
                val found =
                    assertIs<ServerCheck.Found>(
                        LanServers(OkHttpClient()).check(server.url("/").toString(), null, null)
                    )
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
}
