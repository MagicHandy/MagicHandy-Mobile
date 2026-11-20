package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class VacuCheckInTest {
    @Test
    fun `answering a check-in rearms it while an unanswered minute triggers a real Stop`() =
        runTest {
            val f = DebugFixture(this)
            val id = DeviceId("vacuglide")
            f.onboard()
            f.connect(id)
            assertEquals(
                15.minutes,
                (f.devices.saved.single { it.ref.id == id }.settings as DeviceSettings.VacuGlide)
                    .checkInEvery,
            )
            f.send(UserIntent.SetCheckInInterval(id, 10.seconds))
            f.send(UserIntent.StartMotion)
            advanceTimeBy(10001)
            runCurrent()
            assertTrue(f.core.state.value.banners.any { it is Banner.CheckInDue })
            f.send(UserIntent.AnswerCheckIn(id))
            assertTrue(f.core.state.value.banners.none { it is Banner.CheckInDue })
            advanceTimeBy(70001)
            runCurrent()
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            assertTrue(requireNotNull(f.core.state.value.stopReceipt).isAllConfirmed)
            assertEquals(0, f.devices.output.value.getValue(id).speed.value)
            assertNull(f.d.journal.read())
            f.scope.cancel()
        }

    @Test
    fun `switching the timer off cancels its pending deadline without interrupting playback`() =
        runTest {
            val f = DebugFixture(this)
            val id = DeviceId("vacuglide")
            f.onboard()
            f.connect(id)
            f.send(UserIntent.SetCheckInInterval(id, 1.seconds))
            f.send(UserIntent.StartMotion)
            advanceTimeBy(1001)
            runCurrent()
            f.send(UserIntent.SetCheckInInterval(id, null))
            advanceTimeBy(65000)
            runCurrent()
            assertTrue(f.core.state.value.banners.none { it is Banner.CheckInDue })
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            f.scope.cancel()
        }
}
