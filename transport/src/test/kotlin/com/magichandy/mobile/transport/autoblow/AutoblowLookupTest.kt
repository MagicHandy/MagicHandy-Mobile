package com.magichandy.mobile.transport.autoblow

import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.transport.providers.reply
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

internal class AutoblowLookupTest {
    private val online =
        """{"connected":true,"deviceType":"vacuglide","cluster":"fixture.autoblowapi.com"}"""

    @Test
    fun `only a framed vendor subscription opens the button feed`() = runTest {
        var status = 200
        var type = "application/json"
        var feed = ""
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    if (req.url.encodedPath.endsWith("/connected")) req.reply(online)
                    else req.reply(feed, type).newBuilder().code(status).build()
                }
                .build()
        val wire = AutoblowCloud(DeviceFamily.VacuGlide, Secret("synthetic"), client, client)
        wire.discover()
        val buttons = mutableListOf<String>()
        var opened = false
        for ((code, mime, text) in
            listOf(
                Triple(204, "text/event-stream", ""),
                Triple(200, "text/html", "<html>Unavailable</html>"),
            )) {
            status = code
            type = mime
            feed = text
            assertFailsWith<IOException> { wire.events({ opened = true }) { buttons += it } }
            assertFalse(opened)
        }
        status = 200
        type = "application/octet-stream"
        feed = ": beat\n\nevent: mode-button-pressed\n\n"
        wire.events({ opened = true }) { buttons += it }
        assertFalse(opened)
        assertTrue(buttons.isEmpty())
        feed =
            "\uFEFFevent: connected\ndata: {}\n\nevent: mode-button-pressed\ndata: {}\n\nevent: speed-plus-button-pressed\n"
        wire.events({ opened = true }) { buttons += it }
        assertTrue(opened)
        assertEquals(listOf("mode-button-pressed"), buttons)
    }

    @Test
    fun `cloud motion needs the matching command receipt and a refused PUT is sent once`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val client =
                    OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            val req = chain.request()
                            if (req.url.encodedPath.endsWith("/connected")) req.reply(online)
                            else
                                chain.proceed(
                                    req.newBuilder().url(server.url(req.url.encodedPath)).build()
                                )
                        }
                        .build()
                val (normal, priority) = AutoblowCloud.clients(client)
                val wire =
                    AutoblowCloud(DeviceFamily.VacuGlide, Secret("synthetic"), normal, priority)
                wire.discover()
                val speed = buildJsonObject { put("targetSpeed", 20) }
                server.enqueue(
                    MockResponse.Builder().code(503).setHeader("Retry-After", "0").build()
                )
                server.enqueue(
                    MockResponse.Builder()
                        .body("""{"operationalMode":"TARGET_SPEED_PLAYING","targetSpeed":20}""")
                        .build()
                )
                assertFailsWith<IOException> { wire.put("target-speed", speed, false) { true } }
                assertEquals(1, server.requestCount)
                wire.put("target-speed", speed, false) { true }
                for (reply in
                    listOf(
                        "{}",
                        """{"error":"refused"}""",
                        """{"operationalMode":"TARGET_SPEED_PAUSED","targetSpeed":20}""",
                    )) {
                    server.enqueue(MockResponse.Builder().body(reply).build())
                    assertFailsWith<IOException> { wire.put("target-speed", speed, false) { true } }
                }
                server.enqueue(MockResponse.Builder().body("""{"strokePlusValve":false}""").build())
                assertFailsWith<IOException> {
                    wire.put(
                        "valve/stroke-plus",
                        buildJsonObject { put("valveState", true) },
                        false,
                    ) {
                        true
                    }
                }
                server.enqueue(
                    MockResponse.Builder()
                        .body("""{"operationalMode":"TARGET_SPEED_PAUSED"}""")
                        .build()
                )
                wire.put("target-speed/stop", null, true) { true }
                repeat(server.requestCount - 1) { server.takeRequest() }
                val stop = server.takeRequest()
                assertEquals(null, stop.headers["Content-Type"])
                assertEquals(0L, stop.bodySize)
            }
        }

    @Test
    fun `discovery requires the exact device family and an allowlisted regional host`() = runTest {
        var response = ""
        val requests = mutableListOf<String>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request()
                    requests += req.url.host
                    req.reply(response)
                }
                .build()
        val wire = AutoblowCloud(DeviceFamily.VacuGlide, Secret("test-token"), http, http)
        for (invalid in
            listOf(
                """{"connected":true,"cluster":"test.autoblowapi.com"}""",
                """{"connected":true,"deviceType":"autoblow-ultra","cluster":"test.autoblowapi.com"}""",
                """{"connected":true,"deviceType":"vacuglide","cluster":"another.invalid"}""",
            )) {
            response = invalid
            assertFailsWith<IOException> { wire.discover() }
        }
        assertEquals(setOf("latency.autoblowapi.com"), requests.toSet())
        response =
            """{"connected":true,"deviceType":"vacuglide","cluster":"test.autoblowapi.com"}"""
        wire.discover()
        response = """{"operationalMode":"ONLINE_CONNECTED"}"""
        wire.state(priorityRead = true)
        assertEquals("test.autoblowapi.com", requests.last())
    }
}
