package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ChatId
import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceLimit
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.HandyProfile
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.SendBlock
import com.magichandy.mobile.core.api.StartupState
import com.magichandy.mobile.core.api.StepDirection
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.api.canSendMessage
import com.magichandy.mobile.core.api.maximumMessageLength
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.NetworkState
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.OutputSlice
import com.magichandy.mobile.core.ports.RunHost
import com.magichandy.mobile.core.ports.RunId
import com.magichandy.mobile.core.ports.RunIntent
import com.magichandy.mobile.core.ports.RunJournal
import com.magichandy.mobile.core.ports.RunJournalStore
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.TimedDevice
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.core.testing.InMemoryRunJournal
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class RunBoundaryTest {
    @Test
    fun `validation loss keeps a local bridge on the same Wi-Fi`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        val id = DeviceId("vibrator")
        val records = requireNotNull(f.d.devices.configuration)
        val row = records.rows.value.single { it.ref.id == id }
        records.save(row.copy(ref = row.ref.copy(connection = ConnectionKind.IntifaceOnNetwork)))
        runCurrent()
        f.connect(id)
        f.send(UserIntent.StartMotion)
        assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
        f.network.value = NetworkState(isOnline = false, routeGeneration = 0, hasLocalRoute = true)
        runCurrent()
        assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
        assertEquals(
            DeviceStatus.Playing,
            f.core.state.value.devices.devices.single { it.id == id }.status,
        )
        f.scope.cancel()
    }

    @Test
    fun `excluding the tempo lead admits the included Handy`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        val vacu = DeviceId("vacuglide")
        val handy = DeviceId("handy")
        f.connect(vacu)
        f.connect(handy)
        f.send(UserIntent.SetInNextRun(vacu, false))
        assertEquals(
            RunMembership.NextRun,
            f.core.state.value.devices.devices.single { it.id == handy }.membership,
        )
        f.send(UserIntent.StartMotion)
        assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
        assertEquals(MotionMode.Layered, f.core.state.value.settings.motionMode)
        f.scope.cancel()
    }

    @Test
    fun `a persistent device remains available until its return Stop is confirmed`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        val added =
            f.core.submit(
                UserIntent.AddAutoblowDevice(
                    DeviceFamily.VacuGlide,
                    "Audit fixture",
                    Secret("audit-fixture-only"),
                )
            )
        assertEquals(FormResult.Accepted, added)
        runCurrent()
        val id = f.devices.saved.single { it.ref.name == "Audit fixture" }.ref.id
        f.connect(id)
        f.send(UserIntent.StartMotion)
        f.network.value = NetworkState(false, 1)
        runCurrent()
        assertNotNull(f.d.journal.read())
        val removed = f.core.submit(UserIntent.Remove(id))
        runCurrent()
        assertEquals(FormResult.Rejected(FormReason.ReconcileDevice), removed)
        assertTrue(f.devices.saved.any { it.ref.id == id })
        f.network.value = NetworkState(true, 2)
        runCurrent()
        assertNull(f.d.journal.read())
        assertEquals(FormResult.Accepted, f.core.submit(UserIntent.Remove(id)))
        f.scope.cancel()
    }

    @Test
    fun `an unresolved idle Stop survives process replacement`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        val id = DeviceId("vibrator")
        f.connect(id)
        f.core.emergencyStop(StopSource.AppFooter)
        runCurrent()
        assertEquals(
            StopOutcome.Unconfirmed,
            requireNotNull(f.core.state.value.stopReceipt).outcomes.single().outcome,
        )
        assertNotNull(f.d.journal.read())
        f.scope.cancel()
        f.devices.hostDied()
        val replacement =
            com.magichandy.mobile.core.createMagicHandyCore(f.d.copy(scope = backgroundScope))
        runCurrent()
        assertNotNull(replacement.state.value.interruptedRun)
        assertEquals(
            StopOutcome.Unconfirmed,
            replacement.state.value.interruptedRun?.outcomes?.single()?.outcome,
        )
    }

    @Test
    fun `reconnected device clears the earlier loss banner`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        val handy = DeviceId("handy")
        f.connect(handy)
        f.send(UserIntent.StartMotion)
        f.devices.loseLink(handy)
        runCurrent()
        f.devices.restoreLink(handy)
        runCurrent()
        f.connect(handy)
        f.send(UserIntent.ReconcileDevice(handy))
        f.send(UserIntent.DismissStopReceipt)
        f.send(UserIntent.StartMotion)
        assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
        f.core.emergencyStop(StopSource.AppFooter)
        runCurrent()
        val state = f.core.state.value
        val receipt = requireNotNull(state.stopReceipt)
        assertTrue(receipt.isAllConfirmed)
        assertTrue(state.banners.none { it is Banner.DeviceLost })
        f.scope.cancel()
    }

    @Test
    fun `loss of the run host fences the next device write`() = runTest {
        var serviceUp = true
        val f =
            DebugFixture(this) {
                it.copy(
                    runHost =
                        object : RunHost {
                            override suspend fun ensureRunning() = serviceUp

                            override val isRunning: Boolean
                                get() = serviceUp
                        }
                )
            }
        f.onboard()
        val id = DeviceId("vacuglide")
        f.connect(id)
        f.send(UserIntent.StartMotion)
        advanceTimeBy(800)
        runCurrent()
        val before = f.devices.output.value.getValue(id).batches
        serviceUp = false
        advanceTimeBy(8500)
        runCurrent()
        val after = f.devices.output.value.getValue(id)
        assertEquals(before, after.batches)
        assertEquals(0, after.speed.value)
        assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
        f.scope.cancel()
    }

    @Test
    fun `the connected device uses the selected Handy profile and the saved vibrator ceiling`() =
        runTest {
            val fixture = DebugFixture(this)
            fixture.onboard()
            val handy = DeviceId("handy")
            val vibrator = DeviceId("vibrator")
            fun settings(id: DeviceId) = fixture.devices.saved.single { it.ref.id == id }.settings
            assertEquals(40, (settings(vibrator) as DeviceSettings.Vibrator).maxLevel.value)
            fixture.send(UserIntent.SetHandyModel(handy, HandyProfile.Pro))
            fixture.send(
                UserIntent.StepDeviceLimit(
                    vibrator,
                    DeviceLimit.VibrationMaximum,
                    StepDirection.Down,
                )
            )
            assertEquals("handy_2_pro", (settings(handy) as DeviceSettings.Handy).model)
            assertEquals(35, (settings(vibrator) as DeviceSettings.Vibrator).maxLevel.value)
            fixture.connect(handy)
            fixture.send(UserIntent.StartMotion)
            fixture.send(UserIntent.SetHandyModel(handy, HandyProfile.Original))
            assertEquals("handy_2_pro", (settings(handy) as DeviceSettings.Handy).model)
            fixture.scope.cancel()
        }

    @Test
    fun `the chat composer preserves an ineligible draft and always accepts a typed Stop`() =
        runTest {
            val fixture = DebugFixture(this)
            fixture.onboard()
            fixture.send(UserIntent.SendMessage("First message"))
            val chat = fixture.core.state.value.chat
            assertIs<ReplyState.Streaming>(chat.reply)
            assertFalse(chat.canSendMessage("Second message"))
            assertTrue(chat.canSendMessage("please stop"))
            assertFalse(
                chat
                    .copy(reply = ReplyState.Idle)
                    .canSendMessage("x".repeat(maximumMessageLength + 1))
            )
            assertTrue(chat.copy(sendBlockedBy = SendBlock.ModelUnreachable).canSendMessage("stop"))
            fixture.scope.cancel()
        }

    @Test
    fun `loss writes one timestamped event to the open chat after Stop`() = runTest {
        val fixture = DebugFixture(this)
        fixture.onboard()
        fixture.send(UserIntent.SendMessage("Hello"))
        advanceTimeBy(300)
        runCurrent()
        val id = DeviceId("handy")
        fixture.connect(id)
        fixture.send(UserIntent.StartMotion)
        fixture.devices.loseLink(id)
        runCurrent()
        val event =
            fixture.core.state.value.chat.entries
                .filterIsInstance<ChatEntry.MotionStopped>()
                .single()
        assertEquals("The Handy", event.lostDevice)
        fixture.core.emergencyStop(StopSource.AppFooter)
        runCurrent()
        assertEquals(
            1,
            fixture.core.state.value.chat.entries.filterIsInstance<ChatEntry.MotionStopped>().size,
        )
        fixture.scope.cancel()
    }

    @Test
    fun `a return Stop remains owed until that attempt confirms`() = runTest {
        val id = DeviceId("vacuglide")
        var mode = 0
        var calls = 0
        val finish = CompletableDeferred<Unit>()
        val fixture =
            DebugFixture(this) { d ->
                d.copy(
                    devices =
                        object : DeviceDirectory by d.devices {
                            override suspend fun stopSavedCloud(
                                device: DeviceRef,
                                fence: OutputFence,
                            ): StopEvidence {
                                calls++
                                if (mode == 1) finish.await()
                                return if (mode == 1)
                                    StopEvidence.Unconfirmed(UnconfirmedCause.Offline)
                                else StopEvidence.Acknowledged
                            }
                        }
                )
            }
        fixture.onboard()
        fixture.connect(id)
        fixture.send(UserIntent.StartMotion)
        fixture.network.value = NetworkState(false, 1)
        runCurrent()
        assertTrue(id in fixture.core.state.value.devices.awaitingReturn)
        fixture.core.emergencyStop(StopSource.AppFooter)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(id in fixture.core.state.value.devices.awaitingReturn)
        mode = 1
        fixture.network.value = NetworkState(true, 2)
        runCurrent()
        assertTrue(id in fixture.core.state.value.devices.awaitingReturn)
        finish.complete(Unit)
        runCurrent()
        assertTrue(id in fixture.core.state.value.devices.awaitingReturn)
        assertNotNull(fixture.d.journal.read())
        mode = 2
        fixture.devices.restoreLink(id)
        runCurrent()
        assertTrue(id !in fixture.core.state.value.devices.awaitingReturn)
        assertNull(fixture.d.journal.read())
        fixture.scope.cancel()
    }

    @Test
    fun `a current spoken Stop bypasses chat and a stale transcript cannot stop the next run`() =
        runTest {
            val f = DebugFixture(this)
            f.onboard()
            f.connect(DeviceId("handy"))
            f.send(UserIntent.StartMotion)
            val original = f.core.currentStopEpoch
            val word =
                UserIntent.SendVoiceMessage(
                    "stop",
                    original,
                    ChatId("other"),
                )
            f.core.dispatch(word)
            assertTrue(f.core.currentStopEpoch > original)
            runCurrent()
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            f.send(UserIntent.StartMotion)
            f.send(word)
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            f.scope.cancel()
        }

    @Test
    fun `two host loss notifications wait for the same suspended disconnect`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val f =
            DebugFixture(this) { d ->
                d.copy(
                    devices =
                        object : DeviceDirectory by d.devices {
                            override suspend fun disconnect(device: DeviceId) {
                                entered.complete(Unit)
                                finish.await()
                                d.devices.disconnect(device)
                            }
                        }
                )
            }
        f.onboard()
        f.connect(DeviceId("handy"))
        f.core.emergencyStop(StopSource.ServiceLost)
        runCurrent()
        assertTrue(entered.isCompleted)
        f.core.emergencyStop(StopSource.TaskRemoved)
        val draining = async { f.core.awaitEmergencyStop() }
        runCurrent()
        assertFalse(draining.isCompleted)
        finish.complete(Unit)
        draining.await()
        f.scope.cancel()
    }

    @Test
    fun `task removal settles and releases an idle connected owner`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        val id = DeviceId("handy")
        f.connect(id)
        f.core.emergencyStop(StopSource.TaskRemoved)
        f.core.awaitEmergencyStop()
        runCurrent()
        assertEquals(
            DeviceStatus.NotConnected,
            f.core.state.value.devices.devices.single { it.id == id }.status,
        )
        assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
        f.scope.cancel()
    }

    @Test
    fun `storage startup gates restored settings and exposes a failed load`() = runTest {
        for (fail in listOf(false, true)) {
            val ready = CompletableDeferred<Unit>()
            val f =
                DebugFixture(this) {
                    it.copy(
                        awaitStorage = {
                            ready.await()
                            if (fail) throw IOException("synthetic")
                        }
                    )
                }
            runCurrent()
            assertEquals(StartupState.Loading, f.core.state.value.startup)
            ready.complete(Unit)
            runCurrent()
            assertEquals(
                if (fail) StartupState.StorageUnavailable else StartupState.Ready,
                f.core.state.value.startup,
            )
            f.scope.cancel()
        }
    }

    @Test
    fun `a cold tile Stop collects its cloud receipt once the journal is loaded`() = runTest {
        val saved = InMemoryRunJournal()
        val ref =
            DeviceRef(
                DeviceId("vacuglide"),
                "VacuGlide",
                DeviceFamily.VacuGlide,
                ConnectionKind.AutoblowCloud,
            )
        val record =
            RunJournal(
                RunId("cold"),
                listOf(ref),
                RunIntent.Start,
                false,
                Instant.fromEpochSeconds(10),
            )
        assertTrue(saved.armIfEmpty(record))
        val ready = CompletableDeferred<Unit>()
        val f =
            DebugFixture(this) { d ->
                d.copy(
                    journal =
                        object : RunJournalStore by saved {
                            override suspend fun read(): RunJournal? {
                                ready.await()
                                return saved.read()
                            }
                        }
                )
            }
        f.core.emergencyStop(StopSource.QuickSettingsTile)
        runCurrent()
        assertNull(f.core.state.value.stopReceipt)
        ready.complete(Unit)
        f.core.awaitEmergencyStop()
        runCurrent()
        val receipt = assertNotNull(f.core.state.value.stopReceipt)
        assertEquals(ref.id, receipt.outcomes.single().device)
        assertEquals(StopOutcome.Confirmed, receipt.outcomes.single().outcome)
        assertEquals(1, receipt.pressCount)
        assertNull(saved.read())
        f.scope.cancel()
    }

    @Test
    fun `a refused pre-wire timed slice is resampled without retiring a healthy owner`() = runTest {
        var denied = false
        val fixture =
            DebugFixture(this) { d ->
                d.copy(
                    devices =
                        object : DeviceDirectory by d.devices {
                            override suspend fun connect(
                                device: DeviceId,
                                ownerScope: CoroutineScope,
                            ): DeviceTransport? {
                                val driver =
                                    d.devices.connect(device, ownerScope) as? TimedDevice
                                        ?: return null
                                return object : TimedDevice by driver {
                                    override suspend fun output(
                                        slice: OutputSlice,
                                        fence: OutputFence,
                                    ): WriteResult {
                                        if (!denied) {
                                            denied = true
                                            return WriteResult.Stale
                                        }
                                        return driver.output(slice, fence)
                                    }
                                }
                            }
                        }
                )
            }
        val id = DeviceId("vacuglide")
        fixture.onboard()
        fixture.connect(id)
        fixture.send(UserIntent.StartMotion)
        advanceTimeBy(1400)
        runCurrent()
        assertTrue(denied)
        assertEquals(RunPhase.Playing, fixture.core.state.value.run.phase)
        assertTrue(fixture.devices.output.value.getValue(id).speed.value > 0)
        fixture.core.emergencyStop(StopSource.AppFooter)
        runCurrent()
        assertTrue(assertNotNull(fixture.core.state.value.stopReceipt).isAllConfirmed)
        fixture.scope.cancel()
    }
}
