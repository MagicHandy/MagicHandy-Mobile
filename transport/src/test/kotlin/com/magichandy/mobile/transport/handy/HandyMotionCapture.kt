package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.transport.ble.BluetoothLink
import com.magichandy.mobile.transport.providers.payload
import com.magichandy.mobile.transport.providers.reply
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Records decoded requests at the simulated HTTP or GATT boundary, including packet partitions. */
internal class HandyMotionCapture(private val now: () -> Long) {
    val events = CopyOnWriteArrayList<JsonObject>()

    fun record(operation: String, fields: JsonObjectBuilder.() -> Unit = {}) {
        events += buildJsonObject {
            put("at_ms", now())
            put("op", operation)
            fields()
        }
    }

    fun cloud(device: DeviceRef, clock: Clock): DeviceTransport {
        // Ordinary requests finish in the same virtual tick. The independent priority Stop client
        // uses its own dispatcher, which the test drains before advancing the clock.
        val direct =
            object : ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS, SynchronousQueue()) {
                override fun execute(command: Runnable) = command.run()
            }
        val client =
            OkHttpClient.Builder()
                .dispatcher(Dispatcher(direct))
                .addInterceptor { chain ->
                    val request = chain.request()
                    val path = request.url.encodedPath.removePrefix("/")
                    val body =
                        request.body?.let { Json.parseToJsonElement(request.payload()).jsonObject }
                    val reply =
                        when (path) {
                            "connected" -> """{"result":{"connected":true}}"""
                            "mode" -> """{"result":{"mode":7}}"""
                            "slider/state" ->
                                """{"result":{"position":0,"position_absolute":0,"speed_absolute":0}}"""
                            "slider/stroke" ->
                                if (body == null)
                                    """{"result":{"min":0,"max":1,"min_absolute":0,"max_absolute":110}}"""
                                else {
                                    record("stroke")
                                    """{"result":{"min":0,"max":1}}"""
                                }
                            "servertime" -> """{"server_time":100000}"""
                            "hsp/stop" -> {
                                record("stop")
                                """{"result":{"play_state":2}}"""
                            }
                            "hsp/setup" -> {
                                record("setup")
                                """{"result":{"play_state":2}}"""
                            }
                            "hsp/play" -> {
                                record("play") {
                                    put("offset_ms", requireNotNull(body).getValue("start_time"))
                                }
                                """{"result":{"play_state":1}}"""
                            }
                            "hsp/add" -> {
                                val payload = requireNotNull(body)
                                val points =
                                    payload.getValue("points").jsonArray.map {
                                        val point = it.jsonObject
                                        JsonArray(listOf(point.getValue("t"), point.getValue("x")))
                                    }
                                record("add") {
                                    put("points", JsonArray(points))
                                    put("tail", payload.getValue("tail_point_stream_index"))
                                    put("flush", payload.getValue("flush"))
                                }
                                """{"result":"ok"}"""
                            }
                            else -> error("Unexpected simulated endpoint")
                        }
                    if (body == null) record("read") { put("field", path) }
                    request.reply(reply)
                }
                .build()
        return cloudOwner(
            device,
            Secret("fixture"),
            Secret("synthetic"),
            client,
            "https://handy.invalid/".toHttpUrl(),
            clock,
        )
    }

    fun bluetooth(): BluetoothLink {
        val peer = HandyPeerLink().apply { clockMillis = 0L }
        return object : BluetoothLink by peer {
            override suspend fun write(
                characteristic: String,
                bytes: ByteArray,
                withResponse: Boolean,
                admitted: () -> Boolean,
            ) {
                peer.write(characteristic, bytes, withResponse, admitted)
                val wrapper = fields(requireNotNull(fields(bytes).single { it.number == 2 }.data))
                val command = wrapper.single { it.data != null }
                val body = fields(requireNotNull(command.data))
                fun field(number: Int) = requireNotNull(body.single { it.number == number }.value)
                when (command.number) {
                    715 -> record("stop")
                    841 -> record("stroke")
                    860 -> record("setup")
                    863 -> record("play") { put("offset_ms", field(1)) }
                    861 ->
                        record("add") {
                            put(
                                "points",
                                buildJsonArray {
                                    for (entry in body.filter { it.number == 1 }) {
                                        val point = fields(requireNotNull(entry.data))
                                        add(
                                            JsonArray(
                                                listOf(
                                                    JsonPrimitive(
                                                        point.single { it.number == 1 }.value
                                                    ),
                                                    JsonPrimitive(
                                                        point.single { it.number == 2 }.value
                                                    ),
                                                )
                                            )
                                        )
                                    }
                                },
                            )
                            put("flush", field(2) == 1L)
                            put("tail", field(3))
                        }
                    709,
                    712 -> record("clock")
                    else -> error("Unexpected simulated method")
                }
            }

            override suspend fun writeStop(
                characteristic: String,
                bytes: ByteArray,
                withResponse: Boolean,
                admitted: () -> Boolean,
            ) = write(characteristic, bytes, withResponse, admitted)
        }
    }
}
