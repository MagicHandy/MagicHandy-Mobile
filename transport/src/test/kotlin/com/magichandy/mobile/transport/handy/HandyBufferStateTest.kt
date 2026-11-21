package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.motion.MotionFrame
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.transport.ble.BluetoothConnector
import com.magichandy.mobile.transport.simulated.DebugFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalCoroutinesApi::class)
internal class HandyBufferStateTest {
    @Test
    fun `the same idle HSP reply is valid during prefilling but cannot confirm an active refill`() {
        val add = HandyCommand.Add(listOf(MotionFrame(0, Percent(50))), 0, true)
        for (code in listOf(0, 1, 2, 3, 4)) {
            val reply =
                Json.parseToJsonElement("""{"ok":true,"result":{"play_state":$code}}""").jsonObject
            assertTrue(reply.acknowledges(HandyCommand.Setup(1)))
            assertTrue(reply.acknowledges(add))
            assertEquals(code !in 3..4, reply.acknowledges(HandyCommand.Play(0)))
            assertEquals(code == 1, reply.acknowledges(add.copy(refilling = true)))
        }
        val ack = Json.parseToJsonElement("""{"result":"ok"}""").jsonObject
        assertTrue(ack.acknowledges(add.copy(refilling = true)))
    }

    @Test
    fun `a Bluetooth starvation notification stops only its own active HSP stream without another Play`() =
        runTest {
            val peer = HandyPeerLink()
            val id = DeviceId("handy")
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        devices =
                            object : DeviceDirectory by d.devices {
                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: CoroutineScope,
                                ): DeviceTransport =
                                    HandyBluetoothSession.connect(
                                        saved.first { it.ref.id == device }.ref,
                                        ScanResultId("fixture"),
                                        BluetoothConnector { _, _, _ -> peer },
                                        ownerScope,
                                    )
                            }
                    )
                }
            f.onboard()
            f.connect(id)
            f.send(UserIntent.StartMotion)
            val stream = peer.methods.count { it == 860 }.toLong()
            assertTrue(stream > 0)
            suspend fun note(which: Long) {
                peer.events.send(
                    ProtoWriter()
                        .apply {
                            uint(1, 4)
                            message(5) {
                                message(867) {
                                    message(1) {
                                        uint(1, 4)
                                        uint(10, which)
                                    }
                                }
                            }
                        }
                        .bytes()
                )
                runCurrent()
            }
            note(stream + 1)
            advanceTimeBy(2100)
            runCurrent()
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            val before = peer.methods.count { it == 861 }
            note(stream)
            advanceTimeBy(2100)
            runCurrent()
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            assertEquals(before, peer.methods.count { it == 861 })
            assertEquals(1, peer.methods.count { it == 863 })
            assertEquals(715, peer.methods.last())
            f.scope.cancel()
        }
}
