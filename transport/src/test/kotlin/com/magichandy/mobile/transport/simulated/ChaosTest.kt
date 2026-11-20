package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.core.ports.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
internal class ChaosTest {
    @Test
    fun `airplane mode stops a persistent motor on loss and repeats Stop on return even after an acknowledged first Stop`() =
        runTest {
            val f = DebugFixture(this)
            val id = DeviceId("vacuglide")
            f.onboard()
            f.connect(id)
            f.send(UserIntent.StartMotion)
            val run = f.devices.output.value.getValue(id)
            f.network.value = NetworkState(false, 1)
            runCurrent()
            val first = f.devices.output.value.getValue(id)
            assertTrue(first.stops > run.stops)
            assertEquals(0, first.speed.value)
            assertNotNull(f.d.journal.read())
            f.network.value = NetworkState(true, 2)
            runCurrent()
            val returned = f.devices.output.value.getValue(id)
            assertTrue(returned.stops > first.stops)
            assertNull(f.d.journal.read())
            assertEquals(run.batches, returned.batches)
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            f.scope.cancel()
        }

    @Test
    fun `Wi-Fi handover interrupts a reply and a Bluetooth permission loss stops the whole linked session`() =
        runTest {
            val radio = MutableStateFlow(true)
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        devices =
                            object : DeviceDirectory by d.devices {
                                override val bluetoothOn = radio
                            }
                    )
                }
            f.onboard()
            f.connect(DeviceId("handy"))
            f.send(UserIntent.StartMotion)
            f.send(UserIntent.SendMessage("A synthetic turn."))
            f.network.value = NetworkState(true, 1, false)
            runCurrent()
            assertEquals(
                ReplyState.Interrupted(InterruptCause.ConnectionLost),
                f.core.state.value.chat.reply,
            )
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            assertEquals(SendBlock.ModelUnreachable, f.core.state.value.chat.sendBlockedBy)
            f.network.value = NetworkState(false, 2, true)
            runCurrent()
            assertNull(f.core.state.value.chat.sendBlockedBy)
            f.connect(DeviceId("ultra"))
            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            f.send(UserIntent.StartMotion)
            radio.value = false
            runCurrent()
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            assertEquals(0, f.devices.output.value.getValue(DeviceId("ultra")).speed.value)
            assertTrue(
                f.core.state.value.banners.any {
                    it is Banner.DeviceLost && it.device == DeviceId("handy")
                }
            )
            f.scope.cancel()
        }

    @Test
    fun `a cloud-only return retries a loss even when the phone network never changes`() = runTest {
        val returned = MutableSharedFlow<Unit>()
        val f =
            DebugFixture(this) { d ->
                d.copy(
                    devices =
                        object : DeviceDirectory by d.devices {
                            override val returns = emptyFlow<DeviceId>()

                            override fun reachableAgain(device: DeviceRef) = returned
                        }
                )
            }
        val id = DeviceId("vacuglide")
        f.onboard()
        f.connect(id)
        f.send(UserIntent.StartMotion)
        f.devices.loseLink(id)
        runCurrent()
        assertNotNull(f.d.journal.read())
        f.devices.restoreLink(id)
        returned.emit(Unit)
        runCurrent()
        assertEquals(0, f.devices.output.value.getValue(id).speed.value)
        assertNull(f.d.journal.read())
        f.scope.cancel()
    }

    @Test
    fun `process death with a lost persistent device retries on its return without a playback restart`() =
        runTest {
            val f = DebugFixture(this)
            val id = DeviceId("vacuglide")
            f.onboard()
            f.connect(id)
            f.send(UserIntent.StartMotion)
            f.devices.loseLink(id)
            runCurrent()
            val old = f.devices.output.value.getValue(id)
            assertTrue(old.speed.value > 0)
            f.scope.cancel()
            f.devices.hostDied()
            val revived = createMagicHandyCore(f.d.copy(scope = backgroundScope))
            runCurrent()
            assertNotNull(revived.state.value.interruptedRun)
            assertNotNull(f.d.journal.read())
            f.devices.restoreLink(id)
            runCurrent()
            assertEquals(old.batches, f.devices.output.value.getValue(id).batches)
            assertEquals(0, f.devices.output.value.getValue(id).speed.value)
            assertNull(f.d.journal.read())
            assertEquals(AutopilotStatus.Off, revived.state.value.autopilot.status)
        }
}
