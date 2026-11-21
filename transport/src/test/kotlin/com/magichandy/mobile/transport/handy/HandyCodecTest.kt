package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.motion.MotionFrame
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

internal fun vector(id: String): JsonObject =
    Json.parseToJsonElement(
            requireNotNull(HandyCodecTest::class.java.getResource("/" + id + ".json")).readText()
        )
        .jsonObject

internal class HandyCodecTest {
    @TestFactory
    fun `firmware requests match desktop bytes`(): List<DynamicTest> =
        (1..10).map { i ->
            val fixture = vector("handy-ble-" + i.toString().padStart(2, '0'))
            val path = fixture.getValue("path").jsonPrimitive.content
            DynamicTest.dynamicTest(path + " " + i) {
                val b = fixture.getValue("body").jsonObject
                fun number(key: String) = b.getValue(key).jsonPrimitive
                val command =
                    when (path) {
                        "rpc/stop-current-mode" -> HandyCommand.StopCurrentMode
                        "hsp/state" -> HandyCommand.State
                        "hsp/stop" -> HandyCommand.HspStop
                        "hsp/setup" -> HandyCommand.Setup(number("stream_id").long)
                        "slider/stroke" ->
                            HandyCommand.Stroke(number("min").double, number("max").double)
                        "hsp/add" ->
                            HandyCommand.Add(
                                b.getValue("points").jsonArray.map { p ->
                                    MotionFrame(
                                        p.jsonObject.getValue("t").jsonPrimitive.long,
                                        Percent(p.jsonObject.getValue("x").jsonPrimitive.int),
                                    )
                                },
                                number("tail_point_stream_index").long,
                                number("flush").boolean,
                            )
                        "hsp/play" ->
                            HandyCommand.Play(
                                number("start_time").int,
                                b["server_time"]?.jsonPrimitive?.long,
                            )
                        "clock/offset/set" ->
                            HandyCommand.ClockSet(number("clock_offset").long, number("rtd").int)
                        "clock/offset/get" -> HandyCommand.ClockGet
                        else -> error(path)
                    }
                assertEquals(
                    fixture["hex"]?.jsonPrimitive?.content,
                    command.encode(fixture.getValue("id").jsonPrimitive.long).toHexString(),
                )
            }
        }

    @Test
    fun `only a response with a single method can acknowledge a request`() {
        fun packet(kind: Long, secondMethod: Boolean = false) =
            ProtoWriter()
                .apply {
                    uint(1, kind)
                    message(4) {
                        uint(1, 16)
                        message(12) {}
                        message(715) {}
                        if (secondMethod) message(864) {}
                    }
                }
                .bytes()
        assertEquals(null, decodeReply(packet(4)))
        assertEquals(null, decodeReply(packet(5)))
        assertEquals(715, decodeReply(packet(3))?.method)
        assertEquals(null, decodeReply(packet(3, secondMethod = true))?.method)
    }

    @Test
    fun `malformed oversize truncated or overflowing replies cannot acknowledge Stop`() {
        for (bytes in
            listOf(
                byteArrayOf(0),
                byteArrayOf(8, -128),
                byteArrayOf(34, 127),
                ByteArray(513),
                ByteArray(10) { -1 },
            )) {
            assertFailsWith<IOException> { decodeReply(bytes) }
        }
    }
}
