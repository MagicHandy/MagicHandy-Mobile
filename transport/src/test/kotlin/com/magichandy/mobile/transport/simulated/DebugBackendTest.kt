package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.CoreDispatchers
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.core.testing.InMemoryDiagnostics
import com.magichandy.mobile.transport.handy.HandyCommand
import com.magichandy.mobile.transport.handy.HandyTransport
import com.magichandy.mobile.transport.handy.HandyWire
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class DebugBackendTest {
    @Test
    fun `a failed core coroutine records a category and leaves the intent and Stop paths working`() =
        runTest {
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        devices =
                            object : DeviceDirectory by d.devices {
                                override val losses =
                                    flow<DeviceId> { throw SecurityException("synthetic") }
                            }
                    )
                }
            f.onboard()
            assertTrue(f.core.state.value.firstRun.hasAcknowledgedAdultsOnly)
            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            assertTrue(requireNotNull(f.core.state.value.stopReceipt).isNoDevice)
            assertTrue(
                (f.d.diagnostics as InMemoryDiagnostics).events.value.any {
                    it.second == DiagnosticKind.CoreTaskFailed
                }
            )
            f.scope.cancel()
        }

    @Test
    fun `standalone debug wiring is ready for text chat with every simulated motor idle`() =
        runTest {
            val lifetime = SupervisorJob()
            val scope = CoroutineScope(lifetime + StandardTestDispatcher(testScheduler))
            val core = createMagicHandyCore(simulatedDependencies(scope))
            assertEquals("Simulated", core.state.value.chat.modelName)
            assertNull(core.state.value.chat.sendBlockedBy)
            assertTrue(
                core.state.value.devices.devices.all { it.status == DeviceStatus.NotConnected }
            )
            assertFalse(core.state.value.firstRun.hasAcknowledgedAdultsOnly)
            lifetime.cancel()
            lifetime.join()
        }

    @Test
    fun `the public factory connects idle and runs all five simulated device families through the engine`() =
        runTest {
            val f = DebugFixture(this)
            f.onboard()
            val coyote = DeviceId("coyote")
            f.connect(coyote)
            assertEquals(
                DeviceStatus.NotConnected,
                f.core.state.value.devices.devices.first { it.id == coyote }.status,
            )
            f.send(UserIntent.AcknowledgeCoyoteNotes)
            for (device in f.devices.saved) f.connect(device.ref.id)
            assertTrue(f.devices.output.value.values.all { it.batches == 0 })
            f.send(UserIntent.StartMotion)
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            assertTrue(f.devices.output.value.values.all { it.batches > 0 })
            assertEquals(0, f.devices.output.value.getValue(coyote).strengthA)
            f.core.emergencyStop(StopSource.Notification)
            runCurrent()
            val receipt = assertNotNull(f.core.state.value.stopReceipt)
            assertEquals(5, receipt.outcomes.size)
            assertEquals(
                listOf(StopOutcome.Unconfirmed, StopOutcome.Unconfirmed),
                receipt.outcomes.take(2).map { it.outcome },
            )
            assertEquals(
                UnconfirmedCause.BridgeOnly,
                receipt.outcomes.single { it.device == DeviceId("vibrator") }.cause,
            )
            assertTrue(
                f.devices.output.value.values.all { it.speed.value == 0 && it.strengthB == 0 }
            )
            val count = f.devices.output.value.values.sumOf { it.batches }
            advanceTimeBy(10000)
            runCurrent()
            assertEquals(count, f.devices.output.value.values.sumOf { it.batches })
            f.scope.cancel()
        }

    @Test
    fun `Stop sends to the device while session output storage and model dispatchers are all parked`() =
        runTest {
            val ready = StandardTestDispatcher(testScheduler)
            val parked =
                object : CoroutineDispatcher() {
                    var hold = false
                    val backlog = mutableListOf<Pair<CoroutineContext, Runnable>>()

                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        if (hold) backlog.add(context to block) else ready.dispatch(context, block)
                    }
                }
            val f = DebugFixture(this, CoreDispatchers(parked, parked, ready, parked, ready))
            val core = f.core
            val id = DeviceId("handy")
            core.dispatch(UserIntent.AcknowledgeAdultsOnly)
            core.dispatch(UserIntent.Connect(id))
            runCurrent()
            core.dispatch(UserIntent.StartMotion)
            runCurrent()
            assertTrue(f.devices.output.value.getValue(id).speed.value > 0)
            parked.hold = true
            core.emergencyStop(StopSource.QuickSettingsTile)
            runCurrent()
            assertEquals(0, f.devices.output.value.getValue(id).speed.value)
            assertTrue(parked.backlog.isNotEmpty())
            parked.hold = false
            parked.backlog.toList().forEach { (context, task) -> ready.dispatch(context, task) }
            runCurrent()
            assertTrue(assertNotNull(core.state.value.stopReceipt).isAllConfirmed)
            f.scope.cancel()
        }

    @Test
    fun `a motor test deadline stops a persistent device while its first write is still awaiting a reply`() =
        runTest {
            val f =
                DebugFixture(this) { d ->
                    val directory =
                        object : DeviceDirectory by d.devices {
                            override suspend fun connect(
                                device: DeviceId,
                                ownerScope: CoroutineScope,
                            ): DeviceTransport? {
                                val delegate = d.devices.connect(device, ownerScope) ?: return null
                                return object : DeviceTransport by delegate {
                                    override suspend fun write(
                                        batch: MotionBatch,
                                        fence: OutputFence,
                                    ): WriteResult {
                                        delegate.write(batch, fence)
                                        awaitCancellation()
                                    }
                                }
                            }
                        }
                    d.copy(devices = directory)
                }
            val id = DeviceId("vacuglide")
            f.onboard()
            f.connect(id)
            f.send(UserIntent.TestMotion(id))
            assertTrue(f.devices.output.value.getValue(id).speed.value > 0)
            advanceTimeBy(1501)
            runCurrent()
            assertEquals(0, f.devices.output.value.getValue(id).speed.value)
            assertNull(f.d.journal.read())
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            f.scope.cancel()
        }

    @Test
    fun `Stop before the first wire command of the next batch leaves the same Handy usable`() =
        runTest {
            val id = DeviceId("handy")
            var stopNow: (() -> Unit)? = null
            var stops = 0
            var setups = 0
            val wire =
                object : HandyWire {
                    override val maxPoints = 100
                    override val stopTimeout = 900.milliseconds

                    override suspend fun command(
                        command: HandyCommand,
                        allowed: () -> Boolean,
                    ): WriteResult {
                        stopNow?.invoke()
                        if (!allowed()) return WriteResult.Stale
                        if (command is HandyCommand.Setup) setups++
                        return WriteResult.Accepted
                    }

                    override suspend fun stop(allowed: () -> Boolean): StopEvidence {
                        stops++
                        return StopEvidence.Acknowledged
                    }
                }
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        devices =
                            object : DeviceDirectory by d.devices {
                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: CoroutineScope,
                                ) =
                                    HandyTransport(
                                        saved.first { it.ref.id == device }.ref,
                                        wire,
                                        initiallyStopped = true,
                                    )
                            }
                    )
                }
            f.onboard()
            f.connect(id)
            f.send(UserIntent.StartMotion)
            assertEquals(1, setups)
            stopNow = {
                stopNow = null
                f.core.emergencyStop(StopSource.AppFooter)
            }
            advanceTimeBy(10000)
            runCurrent()
            assertEquals(1, stops)
            assertTrue(requireNotNull(f.core.state.value.stopReceipt).isAllConfirmed)
            f.send(UserIntent.StartMotion)
            assertEquals(2, setups)
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            f.scope.cancel()
        }

    @Test
    fun `a motor test is short and targets only the explicitly selected device`() = runTest {
        val f = DebugFixture(this)
        val handy = DeviceId("handy")
        val ultra = DeviceId("ultra")
        f.onboard()
        f.connect(handy)
        f.connect(ultra)
        f.send(UserIntent.TestMotion(ultra))
        assertEquals(0, f.devices.output.value.getValue(handy).batches)
        assertEquals(10, f.devices.output.value.getValue(ultra).speed.value)
        advanceTimeBy(1501)
        runCurrent()
        assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
        assertNull(f.d.journal.read())
        f.scope.cancel()
    }

    @Test
    fun `a start still in the intent queue cannot undo a later footer Stop`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        f.connect(DeviceId("handy"))
        f.core.dispatch(UserIntent.StartMotion)
        f.core.emergencyStop(StopSource.AppFooter)
        runCurrent()
        assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
        assertTrue(f.devices.output.value.values.all { it.batches == 0 })
        f.scope.cancel()
    }
}
