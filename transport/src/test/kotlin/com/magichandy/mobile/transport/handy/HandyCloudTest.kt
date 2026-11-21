package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.motion.MotionFrame
import com.magichandy.mobile.core.ports.SliderReading
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.transport.http.forDeviceStops
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

internal class HandyCloudTest {
    @TestFactory
    fun `HSP method path and bodies match the desktop requests`(): List<DynamicTest> {
        val commands =
            mapOf(
                "stroke_window" to HandyCommand.Stroke(10.0, 90.0),
                "hsp_setup" to HandyCommand.Setup(1),
                "points_add" to
                    HandyCommand.Add(
                        listOf(
                            MotionFrame(0, Percent(0)),
                            MotionFrame(125, Percent(50)),
                            MotionFrame(250, Percent(100)),
                        ),
                        2,
                        true,
                    ),
                "points_play" to HandyCommand.Play(0),
                "stop" to HandyCommand.HspStop,
            )
        return commands.map { (name, command) ->
            DynamicTest.dynamicTest(name) {
                val fixture = vector("handy-cloud-" + name)
                val expectedBody = fixture.getValue("body").jsonObject
                val expectedPath = "/" + fixture.getValue("path").jsonPrimitive.content
                var requests = 0
                val peer =
                    wire(
                        observe = { request ->
                            requests++
                            assertEquals(
                                fixture.getValue("method").jsonPrimitive.content,
                                request.method,
                            )
                            assertEquals(expectedPath, request.url.encodedPath)
                            val buffer = Buffer()
                            requireNotNull(request.body).writeTo(buffer)
                            assertEquals(expectedBody, Json.parseToJsonElement(buffer.readUtf8()))
                        }
                    ) { path ->
                        if (path == "/slider/stroke") """{"result":{"min":0.1,"max":0.9}}"""
                        else """{"play_state":2}"""
                    }
                runTest {
                    if (command == HandyCommand.HspStop)
                        assertEquals(StopEvidence.Acknowledged, peer.stop { true })
                    else assertEquals(WriteResult.Accepted, peer.command(command) { true })
                }
                assertEquals(1, requests)
            }
        }
    }

    private fun wire(observe: (Request) -> Unit = {}, handler: (String) -> String): CloudWire {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    observe(request)
                    assertEquals("synthetic", request.header("X-Connection-Key"))
                    assertEquals("app-public-id", request.header("X-Api-Key"))
                    assertEquals(null, request.url.query)
                    val path = request.url.encodedPath
                    assertEquals(
                        if (
                            path == "/hsp/state" ||
                                path.startsWith("/slider/") && request.body == null
                        )
                            "GET"
                        else "PUT",
                        request.method,
                    )
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(handler(path).toResponseBody())
                        .build()
                }
                .build()
        return CloudWire(
            Secret("synthetic"),
            Secret("app-public-id"),
            client,
            "https://fixture.invalid/".toHttpUrl(),
        )
    }

    @Test
    fun `both authenticated reads must provide complete calibration and position`() = runTest {
        val peer = wire { path ->
            when (path) {
                "/slider/state" ->
                    """{"result":{"position":0.4,"position_absolute":52,"speed_absolute":0}}"""
                "/slider/stroke" ->
                    """{"result":{"min":0.1,"max":0.9,"min_absolute":19,"max_absolute":85}}"""
                else -> error("unexpected route")
            }
        }
        assertEquals(
            SliderReading(52.0, 0.0, 10.0, 90.0, 19.0, 85.0),
            peer.readPosition { true },
        )
        kotlin.test.assertFailsWith<java.io.IOException> {
            wire { """{"result":{}}""" }.readPosition { true }
        }
    }

    @Test
    fun `cloud owners share a Stop dispatcher independent of both motion clients`() {
        val first = OkHttpClient()
        val second = OkHttpClient()
        val priority = first.forDeviceStops().dispatcher
        assertSame(priority, second.forDeviceStops().dispatcher)
        assertNotSame(first.dispatcher, priority)
        assertNotSame(second.dispatcher, priority)
    }

    @Test
    fun `a bare cloud acknowledgement needs stopped device state`() = runTest {
        val paths = mutableListOf<String>()
        val wire = wire { path ->
            paths += path
            if (path == "/hsp/state") """{"result":{"hsp_state":{"play_state":2}}}""" else "{}"
        }
        assertEquals(StopEvidence.Acknowledged, wire.stop { true })
        assertEquals(listOf("/hsp/stop", "/hsp/state"), paths)
        for (reply in
            listOf(
                "{}",
                """{"error":true,"play_state":2}""",
                """{"play_state":1}""",
                """{"available":false,"play_state":2}""",
                """{"state":2}""",
                """{"connected":false,"play_state":2}""",
                """{"result":{"error":{"code":1001},"play_state":2}}""",
                "malformed",
            )) {
            assertIs<StopEvidence.Unconfirmed>(wire { reply }.stop { true })
        }
    }

    @Test
    fun `a retryable HTTP status cannot repeat a point submission`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(503).setHeader("Retry-After", "0").build())
            server.enqueue(MockResponse.Builder().body("""{"result":"ok"}""").build())
            val peer =
                CloudWire(Secret("synthetic"), Secret("fixture"), OkHttpClient(), server.url("/"))
            val points = HandyCommand.Add(listOf(MotionFrame(0, Percent(0))), 0, true)
            assertFailsWith<IOException> { peer.command(points) { true } }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `an unknown success object is not a motion receipt or a clock sample`() = runTest {
        assertEquals(
            WriteResult.Uncertain,
            wire { """{"detail":"unrecognized"}""" }.command(HandyCommand.Setup(1)) { true },
        )
        assertEquals(null, parseServerTimeMillis("""{"result":{"state":2}}"""))
        assertEquals(12345L, parseServerTimeMillis("""{"result":{"server_time":12345}}"""))
    }

    @Test
    fun `stale Cloud commands never reach the HTTP client`() = runTest {
        val wire = wire { error("Unexpected HTTP request") }
        assertEquals(WriteResult.Stale, wire.command(HandyCommand.Play(0)) { false })
        assertIs<StopEvidence.Unconfirmed>(wire.stop { false })
    }

    @Test
    fun `connection check reads HSP state without sending a mutating command`() = runTest {
        val paths = mutableListOf<String>()
        val peer = wire { path ->
            paths += path
            """{"result":{"hsp_state":{"play_state":2}}}"""
        }
        assertEquals(WriteResult.Accepted, peer.command(HandyCommand.State) { true })
        assertEquals(listOf("/hsp/state"), paths)
        assertEquals(WriteResult.Uncertain, wire { "{}" }.command(HandyCommand.State) { true })
        assertEquals(
            WriteResult.Accepted,
            wire { """{"play_state":1}""" }.command(HandyCommand.State) { true },
        )
    }
}
