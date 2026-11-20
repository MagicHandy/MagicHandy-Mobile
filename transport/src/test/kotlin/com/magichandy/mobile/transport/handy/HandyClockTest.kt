package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.WriteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

internal class HandyClockTest {
    private class TestClock : Clock {
        var monotonicMillis = 0L
        var wallOffsetMillis = 10000L

        override fun elapsed() = monotonicMillis.milliseconds

        override fun now() = Instant.fromEpochMilliseconds(wallOffsetMillis + monotonicMillis)
    }

    @Test
    fun `Cloud Play uses monotonic age and omits an expired offset when refresh fails`() =
        runBlocking {
            val clock = TestClock()
            var queries = 0
            val playTimes = mutableListOf<Long?>()
            val client =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val req = chain.request()
                        val text =
                            when (req.url.encodedPath) {
                                "/api/v3/servertime" -> {
                                    assertEquals("GET", req.method)
                                    assertNull(req.header("X-Connection-Key"))
                                    assertNull(req.header("X-Api-Key"))
                                    clock.monotonicMillis += 40
                                    queries++
                                    if (queries == 1) """{"result":{"serverTime":"123400"}}"""
                                    else "{}"
                                }
                                "/api/v3/hsp/play" -> {
                                    val body =
                                        Buffer()
                                            .also { requireNotNull(req.body).writeTo(it) }
                                            .readUtf8()
                                    playTimes +=
                                        Json.parseToJsonElement(body)
                                            .jsonObject["server_time"]
                                            ?.jsonPrimitive
                                            ?.content
                                            ?.toLong()
                                    "{}"
                                }
                                else -> error("Unexpected in-process request")
                            }
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(text.toResponseBody())
                            .build()
                    }
                    .build()
            val wire =
                CloudWire(
                    Secret("synthetic"),
                    Secret("synthetic-app"),
                    client,
                    "https://fixture.invalid/api/v3/".toHttpUrl(),
                    clock,
                )
            suspend fun play() =
                assertEquals(WriteResult.Accepted, wire.command(HandyCommand.Play(0)) { true })
            play()
            clock.wallOffsetMillis -= 12 * 60 * 60 * 1000
            clock.monotonicMillis += 75
            play()
            clock.monotonicMillis += 300_001
            play()
            assertEquals(2, queries)
            assertEquals(listOf(123440L, 123515L, null), playTimes)
        }

    @Test
    fun `Bluetooth uses the fastest stable round trip and retains the synchronized time after a wall jump`() =
        runTest {
            val clock = TestClock()
            val delays = listOf(80L, 10L, 6L).iterator()
            var set: HandyCommand.ClockSet? = null
            val reading =
                synchronizeBluetoothClock(
                    clock,
                    read = {
                        val rtt = delays.next()
                        clock.monotonicMillis += rtt
                        if (rtt == 6L) clock.wallOffsetMillis += 3600000
                        clock.monotonicMillis - rtt / 2
                    },
                    set = {
                        set = it
                        true
                    },
                )
            assertEquals(HandyCommand.ClockSet(10000, 10), set)
            assertEquals(10096L, reading?.at(clock.elapsed()))
            clock.wallOffsetMillis -= 12 * 60 * 60 * 1000
            clock.monotonicMillis += 40
            assertEquals(10136L, reading?.at(clock.elapsed()))
        }

    @Test
    fun `an empty clock reply never becomes a synthetic zero time`() {
        fun packet(time: Long?) =
            ProtoWriter()
                .apply {
                    uint(1, 3)
                    message(4) {
                        uint(1, 5)
                        message(712) { time?.let { uint(1, it) } }
                    }
                }
                .bytes()
        assertNull(decodeReply(packet(null))?.deviceTimeMillis)
        assertEquals(0L, decodeReply(packet(0L))?.deviceTimeMillis)
        assertNull(decodeReply(packet(1L shl 32))?.deviceTimeMillis)
    }
}
