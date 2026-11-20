package com.magichandy.mobile.transport.intiface

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.core.score.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mockwebserver3.*
import okhttp3.*

internal class VibratorTest {
    @Test
    fun `rounded envelope and coarse actuators stay within the user maximum`() {
        val e = VibrationEnvelope(VibrationMode.Pulse, 20, 80, 2.0)
        assertEquals(0.2, e.level(0, 0.0))
        assertEquals(0.8, e.level(1000, 0.0))
        assertEquals(0, quantize(0.10, 5, -1, 0.10))
        assertEquals(2, quantize(0.55, 5, -1, 0.55))
    }

    @Test
    fun `bridge acknowledgements for zero and StopAll never become device confirmation`() =
        runBlocking {
            val names = java.util.concurrent.CopyOnWriteArrayList<String>()
            val server = MockWebServer()
            val listener =
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val (name, body) =
                            Json.parseToJsonElement(text)
                                .jsonArray
                                .single()
                                .jsonObject
                                .entries
                                .single()
                        val id = body.jsonObject.getValue("Id")
                        names += name
                        val payload =
                            when (name) {
                                "RequestServerInfo" ->
                                    "ServerInfo" to
                                        """{"Id":$id,"MessageVersion":3,"MaxPingTime":0}"""
                                "RequestDeviceList" ->
                                    "DeviceList" to
                                        """{"Id":$id,"Devices":[{"DeviceIndex":5,"DeviceName":"Synthetic","DeviceMessages":{"ScalarCmd":[{"ActuatorType":"Rotate","StepCount":8},{"ActuatorType":"Vibrate","StepCount":20}]}}]}"""
                                else -> "Ok" to """{"Id":$id}"""
                            }
                        if (name == "ScalarCmd") {
                            val motor =
                                body.jsonObject.getValue("Scalars").jsonArray.single().jsonObject
                            assertEquals(JsonPrimitive(1), motor["Index"])
                            assertEquals(0.0, motor.getValue("Scalar").jsonPrimitive.double)
                        }
                        webSocket.send("[{\"" + payload.first + "\":" + payload.second + "}]")
                    }
                }
            repeat(2) { server.enqueue(MockResponse.Builder().webSocketUpgrade(listener).build()) }
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val ref =
                    DeviceRef(
                        DeviceId("v"),
                        "V",
                        DeviceFamily.Vibrator,
                        ConnectionKind.IntifaceOnPhone,
                    )
                val address = server.url("/").toString().replaceFirst("http", "ws")
                val hub = IntifaceHub(OkHttpClient(), scope)
                val choices = hub.targets(address)
                assertEquals(
                    listOf(IntifaceChoice(5, "Synthetic")),
                    choices.map { IntifaceChoice(it.deviceIndex, it.name) },
                )
                assertEquals(listOf("RequestServerInfo", "RequestDeviceList"), names.toList())
                val vibrator =
                    IntifaceVibrator.connect(
                        hub,
                        SavedDevice(
                            ref,
                            DeviceSettings.Vibrator(Percent(60)),
                            address = address,
                            bridgeIdentity = choices.single().identity,
                        ),
                        { Percent(60) },
                        {},
                    )
                assertEquals(
                    StopEvidence.Unconfirmed(UnconfirmedCause.BridgeOnly),
                    vibrator.confirmIdleForConnection(),
                )
                assertTrue("ScalarCmd" in names && "StopAllDevices" in names)
                vibrator.close()
            } finally {
                scope.cancel()
                server.close()
            }
        }
}
