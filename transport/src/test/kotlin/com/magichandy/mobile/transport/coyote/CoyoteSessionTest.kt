package com.magichandy.mobile.transport.coyote

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.transport.ble.*
import com.magichandy.mobile.transport.simulated.DebugFixture
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
internal class CoyoteSessionTest {
    @Test
    fun `100 ms output never raises strength without a reported baseline and Stop zeros both channels`() =
        runTest {
            val events = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
            val sent = mutableListOf<Pair<Long, ByteArray>>()
            var acknowledge = false
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
                        sent += testScheduler.currentTime to bytes.copyOf()
                        if (acknowledge && bytes.size == 20 && bytes[1].toInt() and 15 == 15)
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
            f.core.stepCoyote(id, CoyoteChannel.A, true)
            advanceTimeBy(301)
            runCurrent()
            assertEquals(listOf(0L, 100L, 200L, 300L), sent.drop(2).map { it.first })
            assertTrue(sent.drop(2).all { it.second[1].toInt() == 0 })
            // An unrelated delayed B1 cannot supply a baseline.
            f.send(UserIntent.CheckConnection(id))
            assertEquals(6, sent.size)
            events.emit(byteArrayOf(0xb1.toByte(), 14, 3, 2))
            runCurrent()
            f.core.stepCoyote(id, CoyoteChannel.A, true)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(0, sent.last().second[1].toInt())
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
            f.scope.cancel()
        }
}
