package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.Palette
import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.api.ProviderReplyFailure.Reason
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StartRefusal
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.ModelProvider
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.PlanningProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Autopilot after its model refuses: hold for a fix, or retry what may pass on its own. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AutopilotFailureTest {
    /** A planning model that fails with [failure] until [isFixed], then plans as the simulator. */
    private class FailingPlanner(private val failure: ProviderReplyFailure) :
        ModelProvider, PlanningProvider {
        var calls = 0
        var isFixed = false

        override suspend fun plan(request: ModelRequest): TextResult {
            calls++
            return if (isFixed) SimulatedModelProvider().plan(request)
            else TextResult.Failed(failure)
        }

        override suspend fun stream(messages: List<TextMessage>, preview: (String) -> Unit) =
            TextResult.Interrupted
    }

    @Test
    fun `a refusal that needs a fix holds planning without retrying until Try again`() = runTest {
        val failure = ProviderReplyFailure(Reason.ModelUnavailable, 404)
        val planner = FailingPlanner(failure)
        val f = start(planner)
        val held = f.core.state.value
        assertEquals(AutopilotStatus.Held, held.autopilot.status)
        assertTrue(held.run.isPlanningHeld)
        assertEquals(listOf(Banner.PlanningFailed(failure)), held.planningBanners)
        assertEquals(RunPhase.Playing, held.run.phase)
        val tries = planner.calls
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(tries, planner.calls)
        assertEquals(AutopilotStatus.Held, f.core.state.value.autopilot.status)
        planner.isFixed = true
        f.send(UserIntent.ResumeAutopilot)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(planner.calls > tries)
        assertEquals(AutopilotStatus.Active, f.core.state.value.autopilot.status)
        assertEquals(emptyList(), f.core.state.value.planningBanners)
        f.scope.cancel()
    }

    @Test
    fun `a changed model connection wakes a held refusal`() = runTest {
        val planner = FailingPlanner(ProviderReplyFailure(Reason.SignInRequired))
        val f = start(planner)
        assertEquals(AutopilotStatus.Held, f.core.state.value.autopilot.status)
        planner.isFixed = true
        val catalog = f.d.models.state.value
        val saved = catalog.models.getValue(requireNotNull(catalog.chat))
        f.d.models.save(saved.copy(contextTokens = saved.contextTokens + 1))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(AutopilotStatus.Active, f.core.state.value.autopilot.status)
        f.scope.cancel()
    }

    @Test
    fun `unrelated account and chat updates cannot retry a refused planner`() = runTest {
        for (cause in listOf(Reason.CredentialsRejected, Reason.BillingRequired)) {
            val planner = FailingPlanner(ProviderReplyFailure(cause))
            val f = start(planner)
            val tries = planner.calls
            val model = f.d.models.state.value.models.values.single()
            f.d.models.save(model.copy(info = model.info.copy(id = ConnectionId("unrelated"))))
            f.send(UserIntent.SendMessage("A short check-in."))
            advanceTimeBy(120000)
            runCurrent()
            assertEquals(tries, planner.calls, cause.name)
            assertEquals(
                listOf(Banner.PlanningFailed(ProviderReplyFailure(cause))),
                f.core.state.value.planningBanners,
            )
            f.scope.cancel()
        }
    }

    @Test
    fun `Stop during a held refusal turns Autopilot off and clears the banner`() = runTest {
        val f = start(FailingPlanner(ProviderReplyFailure(Reason.PermissionDenied, 403)))
        assertEquals(AutopilotStatus.Held, f.core.state.value.autopilot.status)
        f.core.emergencyStop(StopSource.AppFooter)
        runCurrent()
        assertTrue(requireNotNull(f.core.state.value.stopReceipt).didTurnOffAutopilot)
        assertEquals(AutopilotStatus.Off, f.core.state.value.autopilot.status)
        assertEquals(emptyList(), f.core.state.value.planningBanners)
        f.scope.cancel()
    }

    @Test
    fun `a service that may recover keeps the backoff and the dropped-connection hold`() = runTest {
        val planner = FailingPlanner(ProviderReplyFailure(Reason.ServiceUnavailable, 503))
        val f = start(planner)
        assertEquals(listOf(Banner.PlanningHeld), f.core.state.value.planningBanners)
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(planner.calls >= 3)
        planner.isFixed = true
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(AutopilotStatus.Active, f.core.state.value.autopilot.status)
        assertEquals(emptyList(), f.core.state.value.planningBanners)
        f.scope.cancel()
    }

    @Test
    fun `visual settings and unrelated accounts preserve the next scheduled plan`() = runTest {
        val planner =
            FailingPlanner(ProviderReplyFailure(Reason.ServiceUnavailable)).apply { isFixed = true }
        val f = start(planner)
        val before = planner.calls
        f.send(UserIntent.SetPalette(Palette.Lowlight))
        val current = f.d.models.state.value.models.values.single()
        f.d.models.save(current.copy(info = current.info.copy(id = ConnectionId("other"))))
        advanceTimeBy(500)
        runCurrent()
        assertEquals(before, planner.calls)
        f.scope.cancel()
    }

    @Test
    fun `Autopilot refuses an empty next roster before requesting a plan`() = runTest {
        val planner = FailingPlanner(ProviderReplyFailure(Reason.ServiceUnavailable))
        val f = DebugFixture(this) { it.copy(provider = { planner }) }
        f.onboard()
        val handy = DeviceId("handy")
        f.connect(handy)
        f.send(UserIntent.SetInNextRun(handy, false))
        f.send(UserIntent.StartAutopilot)
        assertEquals(0, planner.calls)
        assertEquals(AutopilotStatus.Off, f.core.state.value.autopilot.status)
        assertTrue(
            f.core.state.value.banners.contains(Banner.StartRefused(StartRefusal.NoDeviceInRun))
        )
        f.scope.cancel()
    }

    /** Plays The Handy with Autopilot planning through [planner], until its first answer. */
    private fun TestScope.start(planner: FailingPlanner): DebugFixture {
        val f = DebugFixture(this) { it.copy(provider = { planner }) }
        f.onboard()
        f.connect(DeviceId("handy"))
        f.send(UserIntent.StartMotion)
        f.send(UserIntent.StartAutopilot)
        advanceTimeBy(1_000)
        runCurrent()
        return f
    }

    private val AppState.planningBanners: List<Banner>
        get() = banners.filter { it == Banner.PlanningHeld || it is Banner.PlanningFailed }
}
