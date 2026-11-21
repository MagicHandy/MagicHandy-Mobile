package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.UserIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `a pending check-in shows its remaining time`() = runTest {
        val f = DebugFixture(this)
        val id = DeviceId("vacuglide")
        f.onboard()
        f.connect(id)
        f.send(UserIntent.SetCheckInInterval(id, Duration.parse("10s")))
        f.send(UserIntent.StartMotion)
        advanceTimeBy(69000)
        runCurrent()
        val due = f.core.state.value.banners.filterIsInstance<Banner.CheckInDue>().single()
        assertEquals(1L, due.answerWithin.inWholeSeconds)
        advanceTimeBy(1100)
        runCurrent()
        assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
        f.scope.cancel()
    }
}
