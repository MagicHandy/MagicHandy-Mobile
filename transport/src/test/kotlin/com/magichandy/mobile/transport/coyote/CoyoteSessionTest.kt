package com.magichandy.mobile.transport.coyote

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.CoyoteChannel
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceOutput
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.transport.ble.BluetoothConnector
import com.magichandy.mobile.transport.ble.BluetoothLink
import com.magichandy.mobile.transport.ble.GattProfile
import com.magichandy.mobile.transport.simulated.DebugFixture
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class CoyoteSessionTest {
    @Test
    fun `setup needs the matching B1 zero and never retries a submitted zero or reports failed setup as a live loss`() =
        runTest {
            for (acknowledge in listOf(false, true)) {
                val notifications = Channel<ByteArray>(8)
                val written = mutableListOf<ByteArray>()
                var attempts = 0
                var discoveries = 0
                var lost = 0
                val peer =
                    object : BluetoothLink {
                        override suspend fun requestMtu(bytes: Int) = 23

                        override suspend fun discover(service: String): Set<String> {
                            discoveries++
                            return setOf(GattProfile.Coyote.write, GattProfile.Coyote.notify)
                        }

                        override suspend fun notifications(characteristic: String) =
                            notifications.receiveAsFlow()

                        override suspend fun read(characteristic: String) = error("unexpected read")

                        override suspend fun write(
                            characteristic: String,
                            bytes: ByteArray,
                            withResponse: Boolean,
                            admitted: () -> Boolean,
                        ) {
                            check(admitted())
                            written += bytes.copyOf()
                        }

                        override fun retire() {
                            notifications.close()
                        }
                    }
                val connecting = async {
                    runCatching {
                        CoyoteSession.connect(
                            DeviceRef(
                                DeviceId("coyote"),
                                "Fixture",
                                DeviceFamily.Coyote,
                                ConnectionKind.Bluetooth,
                            ),
                            ScanResultId("fixture"),
                            BluetoothConnector { _, _, _ ->
                                attempts++
                                peer
                            },
                            backgroundScope,
                            { DeviceSettings.Coyote(10, 10) },
                            { lost++ },
                        )
                    }
                }
                runCurrent()
                assertEquals(2, written.size)
                val seq = (written.last()[1].toInt() and 255) ushr 4
                notifications.send(byteArrayOf(0xb1.toByte(), 0, 0, 0))
                notifications.send(byteArrayOf(0xb1.toByte(), (seq % 15 + 1).toByte(), 0, 0))
                notifications.send(byteArrayOf(0xb1.toByte(), seq.toByte(), 1, 0))
                runCurrent()
                assertFalse(connecting.isCompleted)
                if (acknowledge) {
                    notifications.send(byteArrayOf(0xb1.toByte(), seq.toByte(), 0, 0))
                    val session = connecting.await().getOrThrow()
                    assertTrue(session.checkConnection())
                    assertEquals(1, discoveries)
                    notifications.close()
                    runCurrent()
                    assertFalse(session.checkConnection())
                    assertEquals(1, lost)
                    session.close()
                } else {
                    advanceTimeBy(1001)
                    assertIs<IOException>(connecting.await().exceptionOrNull())
                    runCurrent()
                    assertEquals(0, lost)
                }
                assertEquals(1, attempts)
                assertEquals(2, written.size)
            }
        }

    @Test
    fun `100 ms output never raises strength without a reported baseline and Stop zeros both channels`() =
        runTest {
            val events = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
            val sent = mutableListOf<Pair<Long, ByteArray>>()
            var acknowledge = false
            var packetLatency = 0L
            var rejectStep = false
            val link =
                object : BluetoothLink {
                    override suspend fun requestMtu(bytes: Int) = 517

                    override suspend fun discover(service: String) =
                        setOf(GattProfile.Coyote.write, GattProfile.Coyote.notify)

                    override suspend fun notifications(characteristic: String) = events

                    override suspend fun read(characteristic: String) = byteArrayOf()

                    override fun retire() {}

                    override suspend fun write(
                        characteristic: String,
                        bytes: ByteArray,
                        withResponse: Boolean,
                        admitted: () -> Boolean,
                    ) {
                        if (!admitted()) return
                        if (rejectStep && bytes.size == 20 && bytes[2].toInt() == 1) {
                            rejectStep = false
                            throw com.magichandy.mobile.transport.ble.BluetoothWriteFenced()
                        }
                        sent += testScheduler.currentTime to bytes.copyOf()
                        if (bytes.size == 20 && bytes[1].toInt() == 0) delay(packetLatency)
                        if (
                            (sent.size == 2 || acknowledge) &&
                                bytes.size == 20 &&
                                bytes[1].toInt() and 15 == 15
                        )
                            events.emit(
                                byteArrayOf(
                                    0xb1.toByte(),
                                    ((bytes[1].toInt() and 255) ushr 4).toByte(),
                                    0,
                                    0,
                                )
                            )
                    }
                }
            val id = DeviceId("coyote")
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        devices =
                            object : DeviceDirectory by d.devices {
                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: CoroutineScope,
                                ): DeviceTransport =
                                    CoyoteSession.connect(
                                        saved.single { it.ref.id == device }.ref,
                                        ScanResultId("synthetic"),
                                        BluetoothConnector { _, _, _ -> link },
                                        ownerScope,
                                        { DeviceSettings.Coyote(20, 20) },
                                        {},
                                    )
                            }
                    )
                }
            f.onboard()
            f.send(UserIntent.AcknowledgeCoyoteNotes)
            f.connect(id)
            f.send(UserIntent.StartMotion)
            fun levels() =
                f.core.state.value.devices.devices.single { it.id == id }.output
                    as DeviceOutput.Channels
            assertEquals(0, levels().strengthA)
            advanceTimeBy(301)
            runCurrent()
            assertEquals(listOf(0L, 100L, 200L, 300L), sent.drop(2).map { it.first })
            assertTrue(sent.drop(2).all { it.second[1].toInt() == 0 })
            // An unrelated delayed B1 cannot replace the confirmed setup baseline.
            f.send(UserIntent.CheckConnection(id))
            assertEquals(6, sent.size)
            events.emit(byteArrayOf(0xb1.toByte(), 14, 3, 2))
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(0, sent.last().second[1].toInt())
            assertEquals(0, levels().strengthA)
            events.emit(byteArrayOf(0xb1.toByte(), 0, 3, 2))
            runCurrent()
            f.core.stepCoyote(id, CoyoteChannel.A, true)
            events.emit(byteArrayOf(0xb1.toByte(), 0, 20, 2))
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(0, sent.last().second[1].toInt())
            events.emit(byteArrayOf(0xb1.toByte(), 0, 3, 2))
            runCurrent()
            val beforeRejected = sent.size
            rejectStep = true
            f.core.stepCoyote(id, CoyoteChannel.A, true)
            advanceTimeBy(100)
            runCurrent()
            assertFalse(rejectStep)
            assertEquals(beforeRejected, sent.size)
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            f.core.stepCoyote(id, CoyoteChannel.A, true)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(1, sent.last().second[2].toInt())
            acknowledge = true
            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            val stop = sent.last().second
            assertEquals(15, stop[1].toInt() and 15)
            assertEquals(0, stop[2].toInt())
            assertEquals(0, stop[3].toInt())
            assertTrue(requireNotNull(f.core.state.value.stopReceipt).isAllConfirmed)
            val size = sent.size
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(size, sent.size)
            acknowledge = false
            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            events.emit(
                byteArrayOf(0xb1.toByte(), ((stop[1].toInt() and 255) ushr 4).toByte(), 0, 0)
            )
            advanceTimeBy(1501)
            runCurrent()
            assertEquals(
                StopOutcome.Unconfirmed,
                requireNotNull(f.core.state.value.stopReceipt).outcomes.single().outcome,
            )
            f.send(UserIntent.ReconcileDevice(id))
            acknowledge = true
            packetLatency = 120
            f.send(UserIntent.StartMotion)
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            advanceTimeBy(900)
            runCurrent()
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            assertEquals(15, sent.last().second[1].toInt() and 15)
            f.scope.cancel()
        }
}
