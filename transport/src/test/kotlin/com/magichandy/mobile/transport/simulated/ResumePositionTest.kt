package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.RunIntent
import com.magichandy.mobile.core.ports.SliderReading
import com.magichandy.mobile.core.ports.StartupPosition
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class ResumePositionTest {
    private val handy = DeviceId("handy")

    private class Slider : StartupPosition {
        override val reversed = false
        var position = 0.0
        var frozen = true
        var leads = 0

        override suspend fun halt(fence: OutputFence) = StopEvidence.Acknowledged

        override suspend fun read(fence: OutputFence) =
            SliderReading(position, 0.0, 0.0, 100.0, 0.0, 100.0)

        override suspend fun position(batch: MotionBatch, fence: OutputFence): WriteResult {
            leads++
            if (!frozen) {
                position =
                    batch.limits.strokeLow.value +
                        (batch.limits.strokeHigh.value - batch.limits.strokeLow.value) *
                            batch.frames.last().position.value / 100.0
            }
            return WriteResult.Accepted
        }
    }

    private fun TestScope.startAndPause(slider: Slider, linked: Boolean = false): DebugFixture {
        val fixture =
            DebugFixture(this) { d ->
                d.copy(
                    devices =
                        object : DeviceDirectory by d.devices {
                            override suspend fun connect(
                                device: DeviceId,
                                ownerScope: CoroutineScope,
                            ): DeviceTransport? {
                                val owner = d.devices.connect(device, ownerScope) ?: return null
                                if (device != handy) return owner
                                return object : DeviceTransport by owner {
                                    override val startupPosition = slider
                                }
                            }
                        }
                )
            }
        fixture.onboard()
        fixture.connect(handy)
        if (linked) fixture.connect(DeviceId("ultra"))
        fixture.send(UserIntent.StartMotion)
        assertEquals(RunPhase.Playing, fixture.core.state.value.run.phase)
        advanceTimeBy(400)
        fixture.send(UserIntent.PauseMotion)
        return fixture
    }

    @Test
    fun `a stopped slider short of its resume target keeps the same paused run until explicit retry`() =
        runTest {
            val slider = Slider()
            val f = startAndPause(slider)
            val record = f.d.journal.read()
            val before = f.devices.output.value.getValue(handy).batches
            f.send(UserIntent.ResumeMotion)
            advanceTimeBy(20000)
            runCurrent()
            val paused = f.core.state.value
            assertEquals(1, slider.leads)
            assertEquals(RunPhase.Paused, paused.run.phase)
            assertEquals(
                DeviceStatus.Paused,
                paused.devices.devices.single { it.id == handy }.status,
            )
            assertTrue(Banner.ResumePositionNotReached in paused.banners)
            assertEquals(RunIntent.Paused, f.d.journal.read()?.intended)
            assertEquals(record?.runId, f.d.journal.read()?.runId)
            assertEquals(before, f.devices.output.value.getValue(handy).batches)
            slider.frozen = false
            advanceTimeBy(10000)
            assertEquals(before, f.devices.output.value.getValue(handy).batches)
            f.send(UserIntent.ResumeMotion)
            advanceTimeBy(6000)
            runCurrent()
            assertEquals(2, slider.leads)
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            assertEquals(record?.runId, f.d.journal.read()?.runId)
            assertEquals(RunIntent.Start, f.d.journal.read()?.intended)
            assertFalse(Banner.ResumePositionNotReached in f.core.state.value.banners)
            assertTrue(f.devices.output.value.getValue(handy).batches > before)
            f.scope.cancel()
        }

    @Test
    fun `a stopped resume miss prevents main playback on the entire linked roster`() = runTest {
        val f = startAndPause(Slider(), linked = true)
        val ids = setOf(handy, DeviceId("ultra"))
        val before = ids.associateWith { f.devices.output.value.getValue(it).batches }
        f.send(UserIntent.ResumeMotion)
        advanceTimeBy(20000)
        runCurrent()
        val paused = f.core.state.value
        assertTrue(Banner.ResumePositionNotReached in paused.banners)
        for (id in ids) {
            assertEquals(DeviceStatus.Paused, paused.devices.devices.single { it.id == id }.status)
            assertEquals(before[id], f.devices.output.value.getValue(id).batches)
        }
        assertEquals(RunIntent.Paused, f.d.journal.read()?.intended)
        f.scope.cancel()
    }

    @Test
    fun `an explicit Stop during resume cannot be reverted to a retryable pause`() = runTest {
        val slider = Slider()
        val f = startAndPause(slider)
        val before = f.devices.output.value.getValue(handy).batches
        f.send(UserIntent.ResumeMotion)
        assertEquals(1, slider.leads)
        f.core.emergencyStop(StopSource.AppFooter)
        advanceTimeBy(20000)
        runCurrent()
        f.send(UserIntent.ResumeMotion)
        assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
        assertFalse(Banner.ResumePositionNotReached in f.core.state.value.banners)
        assertEquals(before, f.devices.output.value.getValue(handy).batches)
        f.scope.cancel()
    }
}
