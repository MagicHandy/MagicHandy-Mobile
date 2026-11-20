package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.NetworkState
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.testing.InMemoryDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class DeviceRecoveryTest {
    @Test
    fun `local checks reconcile only unresolved devices without touching any transport`() =
        runTest {
            val f = DebugFixture(this)
            f.onboard()
            f.send(UserIntent.AcknowledgeCoyoteNotes)
            val bridge = DeviceId("vibrator")
            val writeOnly = DeviceId("coyote")
            val confirmed = DeviceId("ultra")
            listOf(bridge, writeOnly, confirmed).forEach(f::connect)
            f.send(UserIntent.StartMotion)
            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            val before = f.devices.output.value
            val initial = requireNotNull(f.core.state.value.stopReceipt)
            val log = (f.d.diagnostics as InMemoryDiagnostics).events
            val events = log.value
            for (id in listOf(confirmed, DeviceId("unknown"))) f.send(
                UserIntent.ReconcileDevice(id)
            )
            assertEquals(initial, f.core.state.value.stopReceipt)
            assertEquals(events, log.value)
            advanceTimeBy(120)
            f.send(UserIntent.ReconcileDevice(bridge))
            assertEquals(
                setOf(writeOnly, confirmed),
                requireNotNull(f.core.state.value.stopReceipt).outcomes.map { it.device }.toSet(),
            )
            assertEquals(listOf(writeOnly), f.d.journal.read()?.devices?.map { it.id })
            f.send(UserIntent.DismissStopReceipt)
            assertNotNull(f.core.state.value.stopReceipt)
            f.send(UserIntent.ReconcileDevice(writeOnly))
            assertNull(f.d.journal.read())
            assertEquals(
                listOf(f.clock.now(), f.clock.now()),
                log.value.filter { it.second == DiagnosticKind.DeviceReconciled }.map { it.first },
            )
            f.send(UserIntent.DismissStopReceipt)
            assertNull(f.core.state.value.stopReceipt)
            assertEquals(before, f.devices.output.value)
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            f.core.emergencyStop(StopSource.Notification)
            runCurrent()
            assertEquals(
                before.getValue(bridge).stops + 1,
                f.devices.output.value.getValue(bridge).stops,
            )
            assertEquals(
                UnconfirmedCause.BridgeOnly,
                requireNotNull(f.core.state.value.stopReceipt)
                    .outcomes
                    .single { it.device == bridge }
                    .cause,
            )
            f.scope.cancel()
        }

    @Test
    fun `a local check removes cold-start uncertainty from both recovery views`() = runTest {
        val f = DebugFixture(this)
        val id = DeviceId("vibrator")
        f.onboard()
        f.connect(id)
        f.send(UserIntent.StartMotion)
        f.scope.cancel()
        f.devices.hostDied()
        val next = createMagicHandyCore(f.d.copy(scope = backgroundScope))
        runCurrent()
        val before = f.devices.output.value
        assertEquals(
            StopOutcome.Unconfirmed,
            requireNotNull(next.state.value.interruptedRun).outcomes.single().outcome,
        )
        next.dispatch(UserIntent.ReconcileDevice(id))
        next.dispatch(UserIntent.DismissStopReceipt)
        runCurrent()
        assertTrue(requireNotNull(next.state.value.interruptedRun).outcomes.isEmpty())
        assertNull(next.state.value.stopReceipt)
        assertNull(f.d.journal.read())
        assertEquals(before, f.devices.output.value)
    }

    @Test
    fun `a process restart stops a VacuGlide that retained speed and displays the interrupted run`() =
        runTest {
            val f = DebugFixture(this)
            val id = DeviceId("vacuglide")
            f.onboard()
            f.connect(id)
            f.send(UserIntent.StartMotion)
            assertEquals(MotionMode.Tempo, f.core.state.value.settings.motionMode)
            assertNotNull(f.d.journal.read())
            val before = f.devices.output.value.getValue(id)
            assertTrue(before.speed.value > 0)
            f.scope.cancel()
            f.devices.hostDied()
            assertEquals(before.speed, f.devices.output.value.getValue(id).speed)
            val next = createMagicHandyCore(f.d.copy(scope = backgroundScope))
            runCurrent()
            assertEquals(before.batches, f.devices.output.value.getValue(id).batches)
            assertEquals(0, f.devices.output.value.getValue(id).speed.value)
            assertEquals(RunPhase.Idle, next.state.value.run.phase)
            assertEquals(
                StopOutcome.Confirmed,
                assertNotNull(next.state.value.interruptedRun).outcomes.single().outcome,
            )
            assertNull(f.d.journal.read())
        }

    @Test
    fun `a lost transport closes only after its recovery Stop finishes or expires`() = runTest {
        for (stall in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        devices =
                            object : DeviceDirectory by d.devices {
                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: CoroutineScope,
                                ): DeviceTransport? {
                                    val owner = d.devices.connect(device, ownerScope) ?: return null
                                    return object : DeviceTransport by owner {
                                        override suspend fun stop(
                                            fence: OutputFence
                                        ): StopEvidence {
                                            events += "Stop"
                                            release.await()
                                            events += "ack"
                                            return StopEvidence.Acknowledged
                                        }
                                    }
                                }

                                override suspend fun disconnect(device: DeviceId) {
                                    events += "close"
                                    d.devices.disconnect(device)
                                }
                            }
                    )
                }
            val id = DeviceId("vacuglide")
            f.onboard()
            f.connect(id)
            f.send(UserIntent.StartMotion)
            f.devices.loseLink(id)
            runCurrent()
            assertEquals(listOf("Stop"), events)
            if (stall) advanceTimeBy(900) else release.complete(Unit)
            runCurrent()
            assertEquals(
                if (stall) listOf("Stop", "close") else listOf("Stop", "ack", "close"),
                events,
            )
            f.scope.cancel()
        }
    }

    @Test
    fun `Stop again reaches an unresolved detached cloud device without reconnecting`() = runTest {
        val f = DebugFixture(this)
        val id = DeviceId("vacuglide")
        f.onboard()
        f.connect(id)
        f.send(UserIntent.StartMotion)
        f.devices.loseLink(id)
        runCurrent()
        assertEquals(
            DeviceStatus.Lost,
            f.core.state.value.devices.devices.single { it.id == id }.status,
        )
        assertFalse(requireNotNull(f.core.state.value.stopReceipt).isAllConfirmed)
        val before = f.devices.output.value.getValue(id)
        f.devices.restoreLink(id)
        f.core.emergencyStop(StopSource.AppFooter)
        runCurrent()
        val after = f.devices.output.value.getValue(id)
        assertEquals(before.stops + 1, after.stops)
        assertEquals(before.batches, after.batches)
        assertTrue(requireNotNull(f.core.state.value.stopReceipt).isAllConfirmed)
        assertNull(f.d.journal.read())
        f.scope.cancel()
    }

    @Test
    fun `a cloud loss preserves speed and evidence until a Stop on network return succeeds`() =
        runTest {
            val f = DebugFixture(this)
            val id = DeviceId("vacuglide")
            f.onboard()
            f.connect(id)
            f.send(UserIntent.StartMotion)
            f.devices.loseLink(id)
            f.network.value = NetworkState(false, 1)
            runCurrent()
            assertTrue(f.devices.output.value.getValue(id).speed.value > 0)
            assertNotNull(f.d.journal.read())
            assertFalse(assertNotNull(f.core.state.value.stopReceipt).isAllConfirmed)
            f.devices.restoreLink(id)
            f.network.value = NetworkState(true, 2)
            runCurrent()
            assertEquals(0, f.devices.output.value.getValue(id).speed.value)
            assertNull(f.d.journal.read())
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            f.scope.cancel()
        }

    @Test
    fun `a confirmed pause stays quiet and a bridge-only pause shows zero presses`() = runTest {
        for (name in listOf("ultra", "vibrator")) {
            val f = DebugFixture(this)
            f.onboard()
            val id = DeviceId(name)
            f.connect(id)
            f.send(UserIntent.StartMotion)
            f.send(UserIntent.PauseMotion)
            assertEquals(
                if (name == "ultra") RunPhase.Paused else RunPhase.Idle,
                f.core.state.value.run.phase,
            )
            val receipt = f.core.state.value.stopReceipt
            if (name == "ultra") assertNull(receipt)
            else {
                assertEquals(0, assertNotNull(receipt).pressCount)
                assertEquals(UnconfirmedCause.BridgeOnly, receipt.outcomes.single().cause)
                f.core.emergencyStop(StopSource.Notification)
                runCurrent()
                assertEquals(1, assertNotNull(f.core.state.value.stopReceipt).pressCount)
            }
            f.scope.cancel()
        }
    }
}
