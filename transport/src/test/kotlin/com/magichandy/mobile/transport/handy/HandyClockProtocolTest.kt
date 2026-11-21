package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.transport.ble.BluetoothConnector
import com.magichandy.mobile.transport.simulated.DebugFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class HandyClockProtocolTest {
    @Test
    fun `a correlated clock exchange timestamps Play and firmware without clock replies plays unstamped`() =
        runTest {
            for ((canRead, canSet) in listOf(true to true, false to false, true to false)) {
                val link =
                    HandyPeerLink().apply {
                        clockMillis = 230
                        omitClockReply = !canRead
                        omitClockSetReply = !canSet
                    }
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
                                            BluetoothConnector { _, _, _ -> link },
                                            ownerScope,
                                            d.clock,
                                        )
                                }
                        )
                    }
                f.onboard()
                f.connect(id)
                advanceTimeBy(2500)
                runCurrent()
                assertEquals(
                    DeviceStatus.Connected,
                    f.core.state.value.devices.devices.single { it.id == id }.status,
                )
                assertEquals(
                    listOf(715, 712, 712, 712) + (if (canRead) listOf(709) else emptyList()),
                    link.methods,
                )
                f.send(UserIntent.StartMotion)
                assertEquals(
                    listOf(if (canSet) f.clock.now().toEpochMilliseconds() else null),
                    link.playTimestamps,
                )
                f.scope.cancel()
                runCurrent()
            }
        }

    @Test
    fun `permission revocation during discovery releases the opened link without a retry or write`() =
        runTest {
            val link = HandyPeerLink().apply { denyDiscovery = true }
            var attempts = 0
            assertFailsWith<SecurityException> {
                HandyBluetoothSession.connect(
                    DeviceRef(
                        DeviceId("handy"),
                        "Sim",
                        DeviceFamily.Handy,
                        ConnectionKind.Bluetooth,
                    ),
                    ScanResultId("fixture"),
                    BluetoothConnector { _, _, _ ->
                        attempts++
                        link
                    },
                    backgroundScope,
                )
            }
            assertTrue(link.retired)
            assertTrue(link.methods.isEmpty())
            assertEquals(1, attempts)
        }
}
