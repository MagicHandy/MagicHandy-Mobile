package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.LimitKind
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StepDirection
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.RunIntent
import com.magichandy.mobile.core.ports.WriteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class RunControlsTest {
    private val handy = DeviceId("handy")
    private val late = DeviceId("ultra")

    @Test
    fun `a process restored from a paused journal can only Stop the saved owner`() = runTest {
        val first = DebugFixture(this)
        first.onboard()
        first.connect(late)
        first.send(UserIntent.StartMotion)
        first.send(UserIntent.PauseMotion)
        val before = first.devices.output.value.getValue(late)
        assertEquals(RunIntent.Paused, first.d.journal.read()?.intended)
        first.scope.cancel()
        first.devices.hostDied()
        val restored =
            DebugFixture(this) {
                it.copy(
                    journal = first.d.journal,
                    devices = first.devices,
                    settings = first.d.settings,
                )
            }
        runCurrent()
        restored.send(UserIntent.ResumeMotion)
        assertEquals(RunPhase.Idle, restored.core.state.value.run.phase)
        assertNotNull(restored.core.state.value.interruptedRun)
        val after = first.devices.output.value.getValue(late)
        assertEquals(before.batches, after.batches)
        assertEquals(before.stops + 1, after.stops)
        assertNull(first.d.journal.read())
        restored.scope.cancel()
    }

    private fun TestScope.capture(batches: MutableList<MotionBatch>): DebugFixture =
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
                                    override suspend fun write(
                                        batch: MotionBatch,
                                        fence: OutputFence,
                                    ): WriteResult {
                                        val result = owner.write(batch, fence)
                                        if (device == handy && result == WriteResult.Accepted)
                                            batches += batch
                                        return result
                                    }
                                }
                            }
                        }
                )
            }
            .also {
                it.onboard()
                it.connect(handy)
                it.send(UserIntent.StartMotion)
            }

    @Test
    fun `Pause holds the journal and phase and Resume uses the saved limits and original roster`() =
        runTest {
            val batches = mutableListOf<MotionBatch>()
            val f = capture(batches)
            val original = assertNotNull(f.d.journal.read())
            advanceTimeBy(275)
            f.send(UserIntent.PauseMotion)
            assertEquals(RunPhase.Paused, f.core.state.value.run.phase)
            assertEquals(RunIntent.Paused, f.d.journal.read()?.intended)
            val paused = batches.size
            f.send(UserIntent.StepLimit(LimitKind.StrokeLow, StepDirection.Up))
            f.connect(late)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(paused, batches.size)
            f.send(UserIntent.ResumeMotion)
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            val resumed = batches[paused]
            assertTrue(resumed.isFirst)
            assertEquals(Percent(5), resumed.limits.strokeLow)
            assertEquals(0L, resumed.frames.first().timeMillis)
            assertNotEquals(
                batches.first().frames.first().position,
                resumed.frames.first().position,
            )
            assertEquals(original.runId, f.d.journal.read()?.runId)
            assertEquals(RunIntent.Start, f.d.journal.read()?.intended)
            assertEquals(0, f.devices.output.value.getValue(late).batches)
            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            f.send(UserIntent.ResumeMotion)
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            assertNull(f.d.journal.read())
            f.scope.cancel()
        }

    @Test
    fun `Edge narrows and slows the same run and releasing it restores the previous travel`() =
        runTest {
            val batches = mutableListOf<MotionBatch>()
            val f = capture(batches)
            val id = assertNotNull(f.d.journal.read()).runId
            val stops = f.devices.output.value.getValue(handy).stops
            f.send(UserIntent.SetEdge(true))
            advanceTimeBy(8000)
            runCurrent()
            assertEquals(RunPhase.Edging, f.core.state.value.run.phase)
            assertEquals(Percent(10), batches.last().pace)
            assertTrue(batches.last().frames.all { it.position.value in 25..75 })
            f.send(UserIntent.SetEdge(false))
            advanceTimeBy(8000)
            runCurrent()
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            assertEquals(Percent(25), batches.last().pace)
            assertTrue(batches.last().frames.any { it.position.value !in 25..75 })
            assertEquals(1, batches.count { it.isFirst })
            assertEquals(stops, f.devices.output.value.getValue(handy).stops)
            assertEquals(id, f.d.journal.read()?.runId)
            f.core.emergencyStop(StopSource.AppFooter)
            f.send(UserIntent.SetEdge(true))
            f.send(UserIntent.StartMotion)
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            f.scope.cancel()
        }

    @Test
    fun `three virtual minutes of Handy playback advance a single timestamped stream then Stop stays final`() =
        runTest {
            val batches = mutableListOf<MotionBatch>()
            val f = capture(batches)
            advanceTimeBy(180_000)
            runCurrent()
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            assertEquals(1, batches.count { it.isFirst })
            assertTrue(batches.last().frames.last().timeMillis > 180_000)
            assertTrue(
                batches.zipWithNext().all { (a, b) ->
                    a.frames.last().timeMillis < b.frames.first().timeMillis
                }
            )
            f.core.emergencyStop(StopSource.Notification)
            runCurrent()
            val count = batches.size
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(count, batches.size)
            assertTrue(f.core.state.value.stopReceipt?.isAllConfirmed == true)
            f.scope.cancel()
        }

    @Test
    fun `live limits coalesce at the first unsent batch without a Stop or stream restart`() =
        runTest {
            val batches = mutableListOf<MotionBatch>()
            val f = capture(batches)
            val id = assertNotNull(f.d.journal.read()).runId
            val before = batches.size
            val stops = f.devices.output.value.getValue(handy).stops
            repeat(13) { f.send(UserIntent.StepLimit(LimitKind.TopSpeed, StepDirection.Down)) }
            f.send(UserIntent.StepLimit(LimitKind.StrokeHigh, StepDirection.Down))
            advanceTimeBy(2200)
            runCurrent()
            val edited = batches[before]
            assertFalse(edited.isFirst)
            assertEquals(Percent(15), edited.pace)
            assertEquals(Percent(95), edited.limits.strokeHigh)
            assertTrue(
                edited.frames.first().timeMillis > batches[before - 1].frames.last().timeMillis
            )
            assertEquals(stops, f.devices.output.value.getValue(handy).stops)
            assertEquals(id, f.d.journal.read()?.runId)
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            f.scope.cancel()
        }
}
