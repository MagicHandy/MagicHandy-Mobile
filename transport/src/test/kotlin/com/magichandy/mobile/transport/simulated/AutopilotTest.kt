package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.CheckIns
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.ChatStore
import com.magichandy.mobile.core.ports.ModelProvider
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.NetworkState
import com.magichandy.mobile.core.ports.PlanningProvider
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.builtInPersona
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
internal class AutopilotTest {
    @Test
    fun `a chat motion edit keeps a phrase interval while Autopilot prepares the next plan`() =
        runTest {
            val canned = SimulatedModelProvider()
            val gate = CompletableDeferred<Unit>()
            var holdPlans = false
            val peer =
                object : StructuredProvider by canned {
                    override suspend fun plan(request: ModelRequest): TextResult {
                        if (holdPlans) gate.await()
                        return canned.plan(request)
                    }

                    override suspend fun complete(
                        request: ModelRequest,
                        preview: (String) -> Unit,
                    ) =
                        TextResult.Completed(
                            """{"action":"update","edits":[{"geometry":"full_slider"},{"controls":{"speed_percent":35}}],"reply":"Changed."}"""
                        )
                }
            val f = DebugFixture(this) { it.copy(provider = { peer }) }
            f.onboard()
            val saved = f.d.models.state.value.models.values.single()
            f.d.models.save(saved.copy(info = saved.info.copy(capability = CapabilityLevel.Simple)))
            f.connect(DeviceId("handy"))
            f.send(UserIntent.StartMotion)
            f.send(UserIntent.StartAutopilot)
            advanceTimeBy(1500)
            holdPlans = true
            f.send(UserIntent.SendMessage("Change only the pace to 35 percent."))
            advanceTimeBy(1000)
            runCurrent()
            val state = f.core.state.value
            assertTrue(
                state.chat.entries.filterIsInstance<ChatEntry.Message>().any {
                    it.text == "Changed."
                }
            )
            assertTrue((state.autopilot.nextChangeIn?.inWholeSeconds ?: 0) >= 4)
            assertTrue(state.run.commandedPace?.value == 35 || state.run.pendingPace?.value == 35)
            f.core.emergencyStop(StopSource.AppFooter)
            gate.complete(Unit)
            f.scope.cancel()
        }

    @Test
    fun `check-in storage failure leaves the plan worker active and able to plan again`() =
        runTest {
            val canned = SimulatedModelProvider()
            var plans = 0
            var rejected = 0
            val peer =
                object : StructuredProvider by canned {
                    override suspend fun plan(request: ModelRequest): TextResult {
                        if ("Prepare the next" in request.messages.last().text) plans++
                        return canned.plan(request)
                    }
                }
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        provider = { peer },
                        chats =
                            object : ChatStore by d.chats {
                                override suspend fun append(
                                    chat: ChatId,
                                    entry: ChatEntry,
                                    persona: PersonaId,
                                    memories: List<String>,
                                    admitted: () -> Boolean,
                                ): Boolean {
                                    rejected++
                                    throw IOException("Synthetic disk failure")
                                }
                            },
                    )
                }
            f.onboard()
            f.connect(DeviceId("handy"))
            f.send(UserIntent.SetCheckIns(CheckIns.Talk))
            f.send(UserIntent.StartAutopilot)
            advanceTimeBy(125000)
            runCurrent()
            assertTrue(rejected > 0)
            val earlier = plans
            advanceTimeBy(100000)
            runCurrent()
            assertTrue(plans > earlier)
            assertEquals(AutopilotStatus.Active, f.core.state.value.autopilot.status)
            f.scope.cancel()
        }

    @Test
    fun `check in keeps its original speaker when selection changes while waiting for the model`() =
        runTest {
            val canned = SimulatedModelProvider()
            val asked = CompletableDeferred<ModelRequest>()
            val answer = CompletableDeferred<Unit>()
            val peer =
                object : StructuredProvider by canned {
                    override suspend fun plan(request: ModelRequest): TextResult {
                        if (
                            request.messages
                                .last()
                                .text
                                .contains("Decide whether a spoken check-in") && !asked.isCompleted
                        ) {
                            asked.complete(request)
                            answer.await()
                        }
                        return canned.plan(request)
                    }
                }
            val f = DebugFixture(this) { it.copy(provider = { peer }) }
            f.onboard()
            f.core.submit(
                UserIntent.SavePersona(
                    null,
                    "Check-in speaker",
                    Personality(Explicitness.Warm),
                    use = true,
                )
            )
            f.connect(DeviceId("handy"))
            f.send(UserIntent.SetCheckIns(CheckIns.Talk))
            f.send(UserIntent.StartAutopilot)
            advanceTimeBy(125_000)
            runCurrent()
            assertTrue(asked.isCompleted)
            val request = asked.await()
            assertTrue(request.messages.first().text.contains("Check-in speaker"))
            val journal = f.d.journal.read()?.runId
            f.send(UserIntent.SetPersona(builtInPersona.id))
            answer.complete(Unit)
            advanceTimeBy(150)
            runCurrent()
            val message =
                f.core.state.value.chat.entries.filterIsInstance<ChatEntry.Message>().single()
            assertEquals("Check-in speaker", message.speaker)
            assertEquals(journal, f.d.journal.read()?.runId)
            assertEquals(AutopilotStatus.Active, f.core.state.value.autopilot.status)
            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            assertEquals(AutopilotStatus.Off, f.core.state.value.autopilot.status)
            f.scope.cancel()
        }

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

    @Test
    fun `a transient planner failure retries inside the same playing run`() = runTest {
        var calls = 0
        val peer =
            object : ModelProvider, PlanningProvider {
                override suspend fun plan(request: ModelRequest): TextResult {
                    calls++
                    return if (calls == 1) TextResult.Interrupted
                    else SimulatedModelProvider().plan(request)
                }

                override suspend fun stream(
                    messages: List<TextMessage>,
                    preview: (String) -> Unit,
                ) = TextResult.Interrupted
            }
        val f = DebugFixture(this) { it.copy(provider = { peer }) }
        f.onboard()
        f.connect(DeviceId("handy"))
        f.send(UserIntent.StartMotion)
        f.send(UserIntent.StartAutopilot)
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(calls >= 2)
        assertEquals(AutopilotStatus.Active, f.core.state.value.autopilot.status)
        assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
        f.scope.cancel()
    }

    @Test
    fun `MotionMode Off cancels a pending planner before first motion closes its job and leaves outputs idle`() =
        runTest {
            var cancelled = false
            var calls = 0
            val asked = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            val canned = SimulatedModelProvider()
            val peer =
                object : ModelProvider, PlanningProvider {
                    override suspend fun plan(request: ModelRequest): TextResult {
                        calls++
                        if (!asked.isCompleted) asked.complete(Unit)
                        try {
                            hold.await()
                        } catch (stop: CancellationException) {
                            cancelled = true
                            throw stop
                        }
                        return canned.plan(request)
                    }

                    override suspend fun stream(
                        messages: List<TextMessage>,
                        preview: (String) -> Unit,
                    ) = TextResult.Interrupted
                }
            val f = DebugFixture(this) { it.copy(provider = { peer }) }
            val id = DeviceId("handy")
            f.onboard()
            f.connect(id)
            val initialStops = f.devices.output.value.getValue(id).stops
            f.send(UserIntent.StartAutopilot)
            assertTrue(asked.isCompleted)
            assertEquals(AutopilotStatus.Active, f.core.state.value.autopilot.status)
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            assertEquals(0, f.devices.output.value.getValue(id).batches)

            f.send(UserIntent.SetMotionMode(MotionMode.Off))
            assertTrue(cancelled)
            assertEquals(MotionMode.Off, f.core.state.value.settings.motionMode)
            assertEquals(AutopilotStatus.Off, f.core.state.value.autopilot.status)
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            assertFalse(f.core.state.value.run.isPlanningHeld)
            assertTrue(f.core.state.value.banners.none { it == Banner.PlanningHeld })
            assertNull(f.d.journal.read())
            assertEquals(0, f.devices.output.value.getValue(id).batches)
            assertEquals(0, f.devices.output.value.getValue(id).speed.value)
            assertEquals(initialStops, f.devices.output.value.getValue(id).stops)

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, calls)
            assertEquals(AutopilotStatus.Off, f.core.state.value.autopilot.status)
            assertEquals(0, f.devices.output.value.getValue(id).batches)

            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            val receipt = requireNotNull(f.core.state.value.stopReceipt)
            assertTrue(receipt.isAllConfirmed)
            assertFalse(receipt.didTurnOffAutopilot)

            hold.complete(Unit)
            f.send(UserIntent.SetMotionMode(MotionMode.Layered))
            f.send(UserIntent.StartAutopilot)
            advanceTimeBy(150)
            runCurrent()
            assertEquals(AutopilotStatus.Active, f.core.state.value.autopilot.status)
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            f.scope.cancel()
        }

    @Test
    fun `MotionMode Off before first motion keeps outputs idle even when a late planner result completes`() =
        runTest {
            val asked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val canned = SimulatedModelProvider()
            val peer =
                object : ModelProvider, PlanningProvider {
                    override suspend fun plan(request: ModelRequest): TextResult {
                        if (!asked.isCompleted) asked.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        return canned.plan(request)
                    }

                    override suspend fun stream(
                        messages: List<TextMessage>,
                        preview: (String) -> Unit,
                    ) = TextResult.Interrupted
                }
            val f = DebugFixture(this) { it.copy(provider = { peer }) }
            val id = DeviceId("handy")
            f.onboard()
            f.connect(id)
            f.send(UserIntent.StartAutopilot)
            assertTrue(asked.isCompleted)
            assertEquals(AutopilotStatus.Active, f.core.state.value.autopilot.status)
            assertEquals(0, f.devices.output.value.getValue(id).batches)

            f.send(UserIntent.SetMotionMode(MotionMode.Off))
            assertEquals(AutopilotStatus.Off, f.core.state.value.autopilot.status)
            assertEquals(MotionMode.Off, f.core.state.value.settings.motionMode)
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            assertFalse(f.core.state.value.run.isPlanningHeld)
            assertTrue(f.core.state.value.banners.isEmpty())

            release.complete(Unit)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(AutopilotStatus.Off, f.core.state.value.autopilot.status)
            assertEquals(MotionMode.Off, f.core.state.value.settings.motionMode)
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            assertFalse(f.core.state.value.run.isPlanningHeld)
            assertTrue(f.core.state.value.banners.isEmpty())
            assertNull(f.d.journal.read())
            assertEquals(0, f.devices.output.value.getValue(id).batches)
            assertEquals(0, f.devices.output.value.getValue(id).speed.value)

            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            assertFalse(requireNotNull(f.core.state.value.stopReceipt).didTurnOffAutopilot)
            f.scope.cancel()
        }

    @Test
    fun `MotionMode Off during active Autopilot playback stops motion and preserves direct Stop`() =
        runTest {
            val f = DebugFixture(this)
            val id = DeviceId("handy")
            f.onboard()
            f.connect(id)
            f.send(UserIntent.StartAutopilot)
            advanceTimeBy(150)
            runCurrent()
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            assertEquals(AutopilotStatus.Active, f.core.state.value.autopilot.status)

            f.send(UserIntent.SetMotionMode(MotionMode.Off))
            advanceTimeBy(100)
            runCurrent()
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            assertEquals(AutopilotStatus.Off, f.core.state.value.autopilot.status)
            assertEquals(0, f.devices.output.value.getValue(id).speed.value)
            assertNull(f.d.journal.read())

            val settledBatches = f.devices.output.value.getValue(id).batches
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(settledBatches, f.devices.output.value.getValue(id).batches)

            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            val receipt = requireNotNull(f.core.state.value.stopReceipt)
            assertTrue(receipt.isAllConfirmed)
            assertFalse(receipt.didTurnOffAutopilot)
            f.scope.cancel()
        }
}
