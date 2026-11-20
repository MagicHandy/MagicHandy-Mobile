package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.CoreDispatchers
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticReporter
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.transport.ble.BluetoothConnector
import com.magichandy.mobile.transport.ble.BluetoothLink
import com.magichandy.mobile.transport.ble.GattProfile
import com.magichandy.mobile.transport.simulated.SimulatedDevices
import com.magichandy.mobile.transport.simulated.simulatedDependencies
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

internal class HandyBluetoothTest {
    private val device =
        DeviceRef(DeviceId("fake"), "fake", DeviceFamily.Handy, ConnectionKind.Bluetooth)

    private class Link : BluetoothLink {
        var retired = false
        val events = Channel<ByteArray>(Channel.UNLIMITED)
        val methods = mutableListOf<Int>()
        var replyIdDelta = 0
        var negotiation = true
        var blockSetup = false
        val nativeClose = CompletableDeferred<Unit>()
        var setupDelayMillis = 0L
        var failWrite = false
        var beforeAdmission: () -> Unit = {}

        override suspend fun requestMtu(bytes: Int): Int {
            assertEquals(517, bytes)
            if (!negotiation) throw IOException("synthetic")
            return bytes
        }

        override suspend fun discover(service: String) =
            setOf(GattProfile.Handy.write, GattProfile.Handy.notify)

        override suspend fun notifications(characteristic: String) = events.receiveAsFlow()

        override suspend fun write(
            characteristic: String,
            bytes: ByteArray,
            withResponse: Boolean,
            admitted: () -> Boolean,
        ) {
            assertTrue(withResponse)
            beforeAdmission()
            if (!admitted()) throw IOException("synthetic pre-write refusal")
            val packet = fields(requireNotNull(fields(bytes).single { it.number == 2 }.data))
            val method = packet.single { it.data != null }.number
            if (failWrite) throw IOException("synthetic write failure")
            methods += method
            if (method == 860) delay(setupDelayMillis)
            if (method == 860 && blockSetup) withContext(NonCancellable) { nativeClose.await() }
            val id = requireNotNull(packet.single { it.number == 2 }.value)
            events.send(
                ProtoWriter()
                    .apply {
                        uint(1, 3)
                        message(4) {
                            uint(1, id + replyIdDelta)
                            message(method) {}
                        }
                    }
                    .bytes()
            )
        }

        override suspend fun read(characteristic: String): ByteArray = error("Unexpected read")

        override fun retire() {
            nativeClose.complete(Unit)
            retired = true
            events.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Stop waits for a healthy GATT write and retires a stalled generation within its deadline`() =
        runTest {
            for (stall in listOf(false, true)) {
                val dispatcher = StandardTestDispatcher(testScheduler)
                val clock =
                    object : Clock {
                        override fun elapsed() = testScheduler.currentTime.milliseconds

                        override fun now() =
                            Instant.fromEpochMilliseconds(testScheduler.currentTime)
                    }
                val radio =
                    Link().apply {
                        blockSetup = stall
                        setupDelayMillis = 50
                    }
                val sims = SimulatedDevices()
                val saved = sims.saved.first()
                val directory =
                    object : DeviceDirectory by sims {
                        override suspend fun connect(
                            device: DeviceId,
                            ownerScope: kotlinx.coroutines.CoroutineScope,
                        ): DeviceTransport =
                            HandyBluetoothSession.connect(
                                saved.ref,
                                ScanResultId("fake"),
                                BluetoothConnector { _, _, _ -> radio },
                                backgroundScope,
                            )
                    }
                val deps =
                    simulatedDependencies(
                        backgroundScope,
                        CoreDispatchers(dispatcher, dispatcher, dispatcher, dispatcher, dispatcher),
                        clock,
                        sims,
                    )
                val core = createMagicHandyCore(deps.copy(devices = directory))
                core.dispatch(UserIntent.AcknowledgeAdultsOnly)
                core.dispatch(UserIntent.Connect(saved.ref.id))
                runCurrent()
                core.dispatch(UserIntent.StartMotion)
                runCurrent()
                assertEquals(listOf(715, 841, 860), radio.methods.take(3))
                core.emergencyStop(StopSource.Notification)
                try {
                    runCurrent()
                    assertEquals(listOf(715, 841, 860), radio.methods)
                    assertTrue(!radio.retired)
                    advanceTimeBy(if (stall) 1000 else 50)
                    runCurrent()
                    val receipt = requireNotNull(core.state.value.stopReceipt)
                    assertEquals(!stall, receipt.isAllConfirmed)
                    if (stall) {
                        assertTrue(radio.retired)
                        assertEquals(UnconfirmedCause.WriteFailed, receipt.outcomes.single().cause)
                        assertEquals(listOf(715, 841, 860), radio.methods)
                    } else {
                        assertEquals(715, radio.methods.last())
                        assertTrue(!radio.retired)
                    }
                    val count = radio.methods.size
                    advanceTimeBy(3000)
                    runCurrent()
                    assertEquals(count, radio.methods.size)
                } finally {
                    radio.nativeClose.complete(Unit)
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a stale batch at final native admission sends no motion and keeps the link usable`() =
        runTest {
            val radio = Link()
            val sims = SimulatedDevices()
            val handy = sims.saved.first().ref
            val lane = StandardTestDispatcher(testScheduler)
            val clock =
                object : Clock {
                    override fun elapsed() = testScheduler.currentTime.milliseconds

                    override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)
                }
            val deps =
                simulatedDependencies(
                    backgroundScope,
                    CoreDispatchers(lane, lane, lane, lane, lane),
                    clock,
                    sims,
                )
            val core =
                createMagicHandyCore(
                    deps.copy(
                        devices =
                            object : DeviceDirectory by sims {
                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: kotlinx.coroutines.CoroutineScope,
                                ) =
                                    HandyBluetoothSession.connect(
                                        handy,
                                        ScanResultId("fake"),
                                        BluetoothConnector { _, _, _ -> radio },
                                        ownerScope,
                                    )
                            }
                    )
                )
            core.dispatch(UserIntent.AcknowledgeAdultsOnly)
            core.dispatch(UserIntent.Connect(handy.id))
            runCurrent()
            core.dispatch(UserIntent.StartMotion)
            runCurrent()
            val before = radio.methods.toList()
            radio.beforeAdmission = {
                radio.beforeAdmission = {}
                core.emergencyStop(StopSource.Notification)
            }
            advanceTimeBy(10000)
            runCurrent()
            assertEquals(before + 715, radio.methods)
            assertTrue(requireNotNull(core.state.value.stopReceipt).isAllConfirmed)
            core.dispatch(UserIntent.StartMotion)
            runCurrent()
            assertEquals(RunPhase.Playing, core.state.value.run.phase)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the independent Stop deadline retires a busy link even when ordinary dispatchers cannot run`() =
        runTest {
            val ready = StandardTestDispatcher(testScheduler)
            var hold = false
            val parked = mutableListOf<Pair<CoroutineContext, Runnable>>()
            val io =
                object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        if (hold) parked += context to block else ready.dispatch(context, block)
                    }
                }
            val clock =
                object : Clock {
                    override fun elapsed() = testScheduler.currentTime.milliseconds

                    override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)
                }
            val radio = Link().apply { blockSetup = true }
            val sims = SimulatedDevices()
            val handy = sims.saved.first().ref
            val deps =
                simulatedDependencies(
                    backgroundScope,
                    CoreDispatchers(ready, ready, ready, io, ready),
                    clock,
                    sims,
                )
            val core =
                createMagicHandyCore(
                    deps.copy(
                        devices =
                            object : DeviceDirectory by sims {
                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: kotlinx.coroutines.CoroutineScope,
                                ) =
                                    HandyBluetoothSession.connect(
                                        handy,
                                        ScanResultId("fake"),
                                        BluetoothConnector { _, _, _ -> radio },
                                        ownerScope,
                                    )
                            }
                    )
                )
            core.dispatch(UserIntent.AcknowledgeAdultsOnly)
            core.dispatch(UserIntent.Connect(handy.id))
            runCurrent()
            core.dispatch(UserIntent.StartMotion)
            runCurrent()
            assertEquals(listOf(715, 841, 860), radio.methods)
            hold = true
            try {
                core.emergencyStop(StopSource.AppFooter)
                runCurrent()
                advanceTimeBy(2000)
                runCurrent()
                assertEquals(
                    UnconfirmedCause.WriteFailed,
                    requireNotNull(core.state.value.stopReceipt).outcomes.single().cause,
                )
                assertTrue(radio.retired)
                assertEquals(listOf(715, 841, 860), radio.methods)
            } finally {
                hold = false
                radio.nativeClose.complete(Unit)
                parked.forEach { (context, task) -> ready.dispatch(context, task) }
                runCurrent()
            }
        }

    @Test
    fun `connect and orderly teardown both send only correlated RPC 715`() = runTest {
        val link = Link()
        val session =
            HandyBluetoothSession.connect(
                device,
                ScanResultId("one"),
                BluetoothConnector { _, _, _ -> link },
                backgroundScope,
            )
        assertEquals(StopEvidence.Acknowledged, session.disconnect())
        assertEquals(listOf(715, 715), link.methods)
        assertTrue(link.retired)
    }

    @Test
    fun `a wrong response id or a successful GATT write alone leaves Stop unconfirmed`() = runTest {
        val link = Link()
        val session =
            HandyBluetoothSession.connect(
                device,
                ScanResultId("one"),
                BluetoothConnector { _, _, _ -> link },
                backgroundScope,
            )
        link.replyIdDelta = 1
        assertEquals(StopEvidence.Unconfirmed(UnconfirmedCause.NoReply), session.disconnect())
        assertTrue(link.retired)
    }

    @Test
    fun `a Stop that fails its GATT write reports WriteFailed`() = runTest {
        val link = Link()
        val session =
            HandyBluetoothSession.connect(
                device,
                ScanResultId("one"),
                BluetoothConnector { _, _, _ -> link },
                backgroundScope,
            )
        link.failWrite = true
        val categories = mutableListOf<DiagnosticKind>()
        val evidence = withContext(DiagnosticReporter(categories::add)) { session.disconnect() }
        assertEquals(StopEvidence.Unconfirmed(UnconfirmedCause.WriteFailed), evidence)
        assertEquals(listOf(DiagnosticKind.TransportStopFailed), categories)
        assertEquals(listOf(715), link.methods)
    }

    @Test
    fun `only prewrite failures are retried up to twice and failed generations are retired`() =
        runTest {
            val links = mutableListOf<Link>()
            assertFailsWith<IOException> {
                HandyBluetoothSession.connect(
                    device,
                    ScanResultId("one"),
                    BluetoothConnector { _, _, _ ->
                        Link().also {
                            it.negotiation = false
                            links += it
                        }
                    },
                    backgroundScope,
                )
            }
            assertEquals(3, links.size)
            assertTrue(links.all { it.retired && it.methods.isEmpty() })
            val written = Link().apply { replyIdDelta = 1 }
            var opened = 0
            assertFailsWith<IOException> {
                HandyBluetoothSession.connect(
                    device,
                    ScanResultId("one"),
                    BluetoothConnector { _, _, _ ->
                        opened++
                        written
                    },
                    backgroundScope,
                )
            }
            assertEquals(1, opened)
            assertTrue(written.retired)
        }
}
