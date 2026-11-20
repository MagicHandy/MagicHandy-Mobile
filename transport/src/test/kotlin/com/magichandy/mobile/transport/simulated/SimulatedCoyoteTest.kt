package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
internal class SimulatedCoyoteTest {
    @Test
    fun `Live steps wait for B1 respect each ceiling and cannot revive strength after Stop`() =
        runTest {
            val f = DebugFixture(this)
            val id = DeviceId("coyote")
            f.onboard()
            f.send(UserIntent.AcknowledgeCoyoteNotes)
            f.core.setCoyoteLimits(id, 2, 1)
            runCurrent()
            f.connect(id)
            f.send(UserIntent.StartMotion)
            fun row() =
                f.core.state.value.devices.devices.single { it.id == id }.output
                    as DeviceOutput.Channels
            fun step(channel: CoyoteChannel, up: Boolean = true) {
                f.core.stepCoyote(id, channel, up)
                runCurrent()
            }
            advanceTimeBy(60)
            runCurrent()
            step(CoyoteChannel.A)
            step(CoyoteChannel.A)
            advanceTimeBy(48)
            runCurrent()
            assertEquals(0, row().strengthA)
            advanceTimeBy(55)
            runCurrent()
            assertEquals(1, row().strengthA)
            step(CoyoteChannel.B)
            advanceTimeBy(130)
            runCurrent()
            assertEquals(1, row().strengthB)
            step(CoyoteChannel.B)
            advanceTimeBy(140)
            runCurrent()
            assertEquals(1, row().strengthB)
            step(CoyoteChannel.A)
            advanceTimeBy(150)
            runCurrent()
            assertEquals(2, row().strengthA)
            step(CoyoteChannel.A)
            advanceTimeBy(160)
            runCurrent()
            assertEquals(2, row().strengthA)
            step(CoyoteChannel.A, false)
            advanceTimeBy(160)
            runCurrent()
            assertEquals(1, row().strengthA)
            step(CoyoteChannel.A)
            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            val out = f.devices.output.value.getValue(id)
            assertEquals(0, out.strengthA)
            assertEquals(0, out.strengthB)
            assertEquals(
                StopOutcome.Confirmed,
                f.core.state.value.stopReceipt?.outcomes?.single()?.outcome,
            )
            f.scope.cancel()
        }
}
