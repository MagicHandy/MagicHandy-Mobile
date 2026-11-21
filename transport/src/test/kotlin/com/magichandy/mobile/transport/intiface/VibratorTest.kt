package com.magichandy.mobile.transport.intiface

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceLimit
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.IntifaceChoice
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.StepDirection
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.OutputSlice
import com.magichandy.mobile.core.ports.SavedDevice
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.TimedDevice
import com.magichandy.mobile.core.ports.confirmIdleForConnection
import com.magichandy.mobile.core.score.VibrationEnvelope
import com.magichandy.mobile.core.score.VibrationMode
import com.magichandy.mobile.transport.simulated.DebugFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener

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
                                        """{"Id":$id,"Devices":[{"DeviceIndex":5,"DeviceName":"Synthetic","DeviceMessages":{"LinearCmd":[],"ScalarCmd":[{"ActuatorType":"Rotate","StepCount":8},{"ActuatorType":"Vibrate","StepCount":20}]}}]}"""
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

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `editing a saved ceiling bounds each scalar received by the bridge`() = runTest {
        BridgePeer().use { peer ->
            var checkingWire = true
            var readsInSlice = 0
            var observedRefusal = false
            val id = DeviceId("vibrator")
            val choice = runBlocking { peer.find().single { it.family == DeviceFamily.Vibrator } }
            val fixture =
                DebugFixture(this) { dependencies ->
                    val directory = dependencies.devices
                    dependencies.copy(
                        devices =
                            object : DeviceDirectory by directory {
                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: CoroutineScope,
                                ): DeviceTransport {
                                    val row =
                                        saved
                                            .single { it.ref.id == id }
                                            .copy(
                                                address = peer.address,
                                                bridgeIdentity = choice.identity,
                                            )
                                    val driver = runBlocking {
                                        IntifaceVibrator.connect(
                                            peer.hub,
                                            row,
                                            {
                                                val configured =
                                                    (saved.single { it.ref.id == id }.settings
                                                            as DeviceSettings.Vibrator)
                                                        .maxLevel
                                                if (checkingWire && ++readsInSlice > 1) Percent(0)
                                                else configured
                                            },
                                            {},
                                        )
                                    }
                                    ownerScope.coroutineContext[kotlinx.coroutines.Job]
                                        ?.invokeOnCompletion { runBlocking { driver.close() } }
                                    // Let the loopback peer acknowledge each actual packet before
                                    // advancing virtual playback time.
                                    return object : TimedDevice by driver {
                                        override suspend fun output(
                                            slice: OutputSlice,
                                            fence: OutputFence,
                                        ) = runBlocking {
                                            readsInSlice = 0
                                            val before = peer.scalars.size
                                            val result = driver.output(slice, fence)
                                            if (
                                                result ==
                                                    com.magichandy.mobile.core.ports.WriteResult
                                                        .Stale && fence.isCurrent()
                                            ) {
                                                assertEquals(before, peer.scalars.size)
                                                observedRefusal = true
                                                checkingWire = false
                                            }
                                            result
                                        }

                                        override suspend fun stop(fence: OutputFence) =
                                            runBlocking {
                                                driver.stop(fence)
                                            }
                                    }
                                }
                            }
                    )
                }
            fixture.onboard()
            fixture.connect(id)
            fixture.send(UserIntent.StartMotion)
            advanceTimeBy(4500)
            runCurrent()
            assertTrue(observedRefusal)
            assertTrue(peer.scalars.any { it > 0.10 })
            assertTrue(peer.scalars.all { it <= 0.40 })
            repeat(6) {
                fixture.send(
                    UserIntent.StepDeviceLimit(id, DeviceLimit.VibrationMaximum, StepDirection.Down)
                )
            }
            val after = peer.scalars.size
            advanceTimeBy(3800)
            runCurrent()
            val lowered = peer.scalars.drop(after)
            assertTrue(lowered.any { it > 0.0 })
            assertTrue(lowered.all { it <= 0.10 })
            fixture.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            assertEquals(0.0, peer.scalars.last())
            fixture.scope.cancel()
        }
    }
}
