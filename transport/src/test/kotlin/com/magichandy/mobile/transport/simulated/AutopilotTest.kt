package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
internal class AutopilotTest {
    @Test
    fun `a model outage holds planning while the Bluetooth segment keeps playing then Stop cancels ownership`() =
        runTest {
            val f = DebugFixture(this)
            val id = DeviceId("handy")
            f.onboard()
            f.connect(id)
            f.send(UserIntent.SetFinishTarget(2.minutes))
            f.send(UserIntent.StartAutopilot)
            advanceTimeBy(150)
            runCurrent()
            assertEquals(AutopilotStatus.Active, f.core.state.value.autopilot.status)
            assertEquals(true, f.d.journal.read()?.autopilot)
            assertTrue(f.core.state.value.run.recentChanges.isNotEmpty())
            val before = f.devices.output.value.getValue(id).batches
            f.network.value = NetworkState(false, 1)
            runCurrent()
            assertEquals(AutopilotStatus.Held, f.core.state.value.autopilot.status)
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            advanceTimeBy(8000)
            runCurrent()
            assertTrue(f.devices.output.value.getValue(id).batches > before)
            f.network.value = NetworkState(true, 2)
            runCurrent()
            assertEquals(AutopilotStatus.Active, f.core.state.value.autopilot.status)
            f.core.emergencyStop(StopSource.QuickSettingsTile)
            runCurrent()
            assertTrue(requireNotNull(f.core.state.value.stopReceipt).didTurnOffAutopilot)
            assertEquals(AutopilotStatus.Off, f.core.state.value.autopilot.status)
            advanceTimeBy(310000)
            runCurrent()
            assertNull(f.d.journal.read())
            assertEquals(0, f.devices.output.value.getValue(id).speed.value)
            f.scope.cancel()
        }

    @Test
    fun `turning planning off keeps manual playback and updates the same durable run`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        f.connect(DeviceId("handy"))
        f.send(UserIntent.StartMotion)
        val id = f.d.journal.read()?.runId
        f.send(UserIntent.StartAutopilot)
        assertEquals(true, f.d.journal.read()?.autopilot)
        f.send(UserIntent.PauseAutopilot)
        assertEquals(AutopilotStatus.Paused, f.core.state.value.autopilot.status)
        f.send(UserIntent.TurnOffAutopilot)
        assertEquals(false, f.d.journal.read()?.autopilot)
        assertEquals(id, f.d.journal.read()?.runId)
        assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
        f.scope.cancel()
    }
}
