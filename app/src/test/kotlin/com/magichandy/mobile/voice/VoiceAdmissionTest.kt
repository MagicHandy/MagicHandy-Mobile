package com.magichandy.mobile.voice

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Messenger
import com.magichandy.mobile.core.CoreDispatchers
import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.AutopilotStatus
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.Detector
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DownloadPhase
import com.magichandy.mobile.core.api.InterruptCause
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StartupState
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.api.VoiceEnd
import com.magichandy.mobile.core.api.VoiceModel
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.transport.simulated.SimulatedDevices
import com.magichandy.mobile.transport.simulated.simulatedDependencies
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Duration as JavaDuration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
internal class VoiceAdmissionTest {
    @get:Rule val temp = TemporaryFolder()

    private class TestContext(private val local: File) :
        ContextWrapper(RuntimeEnvironment.getApplication()) {
        val launches = mutableListOf<Intent>()

        override fun getNoBackupFilesDir(): File = local

        override fun checkSelfPermission(permission: String): Int =
            PackageManager.PERMISSION_GRANTED

        override fun startForegroundService(service: Intent): ComponentName {
            launches += service
            return ComponentName(this, VoiceService::class.java)
        }

        override fun stopService(service: Intent): Boolean = true
    }

    private fun fakeFiles(ctx: TestContext) {
        for (spec in VoiceModel.Compact.files(Detector.Silero)) {
            val file = File(VoiceService.modelFolder(ctx), spec.path)
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { it.setLength(spec.bytes) }
        }
    }

    private fun TestScope.until(check: () -> Boolean) {
        repeat(600) {
            runCurrent()
            if (check()) return
            Thread.sleep(5)
        }
        error("Condition did not arrive")
    }

    private fun TestScope.core(onHardware: (SimulatedDevices) -> Unit = {}): MagicHandyCore {
        val lane = StandardTestDispatcher(testScheduler)
        val clock =
            object : Clock {
                override fun elapsed() = testScheduler.currentTime.milliseconds

                override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)
            }
        val hardware = SimulatedDevices(clock)
        onHardware(hardware)
        return createMagicHandyCore(
                simulatedDependencies(
                    backgroundScope,
                    CoreDispatchers(lane, lane, lane, lane, lane),
                    clock,
                    hardware,
                )
            )
            .also {
                it.dispatch(UserIntent.AcknowledgeAdultsOnly)
                runCurrent()
                assertEquals(StartupState.Ready, it.state.value.startup)
            }
    }

    @Test
    fun aPreviousInterruptionDoesNotDiscardSpeechDuringAdmission() = runTest {
        val actual = core()
        val sent = mutableListOf<UserIntent>()
        val recorder =
            object : MagicHandyCore by actual {
                override fun dispatch(intent: UserIntent) {
                    sent += intent
                }
            }
        val turns = VoiceTurns(recorder, actual.currentStopEpoch, {}, {})
        val initial = actual.state.value
        val old = ReplyState.Interrupted(InterruptCause.ProviderError)
        val before = initial.copy(chat = initial.chat.copy(reply = old, sendBlockedBy = null))
        turns.heard("first", before)
        turns.heard("second", before)
        turns.observe(before)
        turns.observe(
            before.copy(chat = before.chat.copy(reply = ReplyState.Streaming("a", "Fixture")))
        )
        turns.observe(before.copy(chat = before.chat.copy(reply = ReplyState.Idle)))
        assertEquals(
            listOf("first", "second"),
            sent.filterIsInstance<UserIntent.SendVoiceMessage>().map { it.text },
        )
    }

    @Test
    fun aLateHelloAndBinderDeathCannotEndNewListening() = runTest {
        val core = core()
        val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ctx = TestContext(temp.newFolder())
            fakeFiles(ctx)
            val voice = VoiceController(ctx, core, voiceScope)
            until { voice.state.value.installed == VoiceModel.Compact }
            voice.appShown()
            class ProcessLink : IBinder by Binder() {
                var callback: IBinder.DeathRecipient? = null
                var unlinked = false

                override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
                    callback = recipient
                }

                override fun unlinkToDeath(
                    recipient: IBinder.DeathRecipient,
                    flags: Int,
                ): Boolean {
                    unlinked = true
                    return true
                }
            }
            fun hello(id: Int, binder: ProcessLink) =
                voice.receive(
                    VoiceProtocol.message(VoiceProtocol.HELLO, id).apply {
                        replyTo = Messenger(binder)
                    }
                )
            voice.start()
            val oldId = ctx.launches.last().getIntExtra(VoiceService.EXTRA_SESSION, 0)
            val old = ProcessLink()
            hello(oldId, old)
            val late = requireNotNull(old.callback)
            voice.end()
            voice.start()
            val nextId = ctx.launches.last().getIntExtra(VoiceService.EXTRA_SESSION, 0)
            val current = ProcessLink()
            hello(nextId, current)
            hello(oldId, old)
            late.binderDied()
            assertTrue(old.unlinked)
            assertFalse(current.unlinked)
            assertTrue(voice.state.value.listening != null)
            requireNotNull(current.callback).binderDied()
            assertTrue(voice.state.value.listening == null)
        } finally {
            voiceScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleTranscriptIsRejectedBeforeSaving() = runTest {
        val core = core()
        val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ctx = TestContext(temp.newFolder())
            fakeFiles(ctx)
            val voice = VoiceController(ctx, core, voiceScope)
            until { voice.state.value.installed == VoiceModel.Compact }
            voice.appShown()
            voice.start()
            val id = ctx.launches.single().getIntExtra(VoiceService.EXTRA_SESSION, 0)
            assertTrue(id > 0)
            val before = core.state.value.stopEpoch
            core.emergencyStop(StopSource.AppFooter)
            assertEquals(before, core.state.value.stopEpoch)
            voice.receive(VoiceProtocol.turn(id, "Synthetic delayed transcription"))
            core.dispatch(
                UserIntent.SendVoiceMessage(
                    "Synthetic delayed transcription",
                    before,
                    core.state.value.chat.chatId,
                )
            )
            runCurrent()
            val saved =
                core.state.value.chat.entries.filterIsInstance<ChatEntry.Message>().any {
                    it.text == "Synthetic delayed transcription" && it.author == Author.You
                }
            assertTrue(core.state.value.stopEpoch > before)
            assertFalse(saved)
        } finally {
            voiceScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleSpokenStartCannotRestartStoppedHandy() = runTest {
        lateinit var hardware: SimulatedDevices
        val device = DeviceId("handy")
        val core = core { hardware = it }
        val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ctx = TestContext(temp.newFolder())
            fakeFiles(ctx)
            val voice = VoiceController(ctx, core, voiceScope)
            until { voice.state.value.installed == VoiceModel.Compact }
            core.dispatch(UserIntent.Connect(DeviceId("handy")))
            runCurrent()
            core.dispatch(UserIntent.StartMotion)
            runCurrent()
            assertEquals(
                RunPhase.Playing,
                core.state.value.run.phase,
            )
            voice.appShown()
            voice.start()
            val id = ctx.launches.single().getIntExtra(VoiceService.EXTRA_SESSION, 0)
            val before = core.state.value.stopEpoch
            core.emergencyStop(StopSource.AppFooter)
            assertEquals(before, core.state.value.stopEpoch)
            voice.receive(VoiceProtocol.turn(id, "Start motion"))
            runCurrent()
            val afterStop = core.state.value.run.phase
            val batchesAtStop = hardware.output.value.getValue(device).batches
            advanceTimeBy(800)
            runCurrent()
            val afterReply = core.state.value.run.phase
            val newBatches = hardware.output.value.getValue(device).batches - batchesAtStop
            assertEquals(0, newBatches)

            assertEquals(RunPhase.Idle, afterStop)
            assertEquals(RunPhase.Idle, afterReply)
        } finally {
            core.emergencyStop(StopSource.AppFooter)
            voiceScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun stopRevokesListeningButLetsDownloadFinish() = runTest {
        val core = core()
        val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val release = CountDownLatch(1)
        try {
            val ctx = TestContext(temp.newFolder())
            val entered = CountDownLatch(1)
            val store =
                ModelStore(
                    VoiceService.modelFolder(ctx),
                    Fetcher { _, _ -> error("Network forbidden") },
                ) {
                    entered.countDown()
                    check(release.await(8, TimeUnit.SECONDS))
                    Long.MAX_VALUE
                }
            val voice = VoiceController(ctx, core, voiceScope, store)
            until { voiceScope.coroutineContext.job.children.count() == 1 }
            fakeFiles(ctx)
            voice.appShown()
            voice.download(VoiceModel.Compact, true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val before = core.state.value.stopEpoch
            core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            assertTrue(core.state.value.stopEpoch > before)
            assertEquals(false, voice.state.value.download?.startsListening)
            assertTrue(ctx.launches.isEmpty())
            release.countDown()
            until { voice.state.value.installed == VoiceModel.Compact }
            assertTrue(ctx.launches.isEmpty())
        } finally {
            release.countDown()
            voiceScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun settingsRetryPreservesDownloadOnly() = runTest {
        val core = core()
        val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ctx = TestContext(temp.newFolder())
            val folder = VoiceService.modelFolder(ctx)
            val store =
                ModelStore(
                    folder,
                    Fetcher { _, _ -> throw IOException("simulated offline response") },
                )
            val voice = VoiceController(ctx, core, voiceScope, store)
            until { voiceScope.coroutineContext.job.children.count() == 1 }
            voice.appShown()
            voice.download(VoiceModel.Compact, andStart = false)
            until { voice.state.value.download?.phase == DownloadPhase.Failed }
            assertTrue(ctx.launches.isEmpty())
            fakeFiles(ctx)
            voice.retryDownload()
            until { voice.state.value.installed == VoiceModel.Compact }
            assertNull(voice.state.value.listening)
            assertTrue(ctx.launches.isEmpty())
        } finally {
            voiceScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun heldAutopilotKeepsListeningOnlyWhileMotionPlays() = runTest {
        val actual = core()
        val initial = actual.state.value
        val held = initial.copy(autopilot = initial.autopilot.copy(status = AutopilotStatus.Held))
        val states = MutableStateFlow(held.copy(run = held.run.copy(phase = RunPhase.Playing)))
        val projected =
            object : MagicHandyCore by actual {
                override val state = states
            }
        val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ctx = TestContext(temp.newFolder())
            fakeFiles(ctx)
            val voice = VoiceController(ctx, projected, voiceScope)
            until { voice.state.value.installed == VoiceModel.Compact }
            voice.appShown()
            voice.start()
            val id = ctx.launches.single().getIntExtra(VoiceService.EXTRA_SESSION, 0)
            voice.receive(VoiceProtocol.message(VoiceProtocol.LISTENING, id))
            ShadowSystemClock.advanceBy(JavaDuration.ofMinutes(16))
            advanceTimeBy(1001)
            runCurrent()
            assertTrue(voice.state.value.listening?.isReady == true)
            states.value = held.copy(run = held.run.copy(phase = RunPhase.Paused))
            runCurrent()
            ShadowSystemClock.advanceBy(JavaDuration.ofMinutes(16))
            advanceTimeBy(1001)
            runCurrent()
            assertNull(voice.state.value.listening)
            assertEquals(VoiceEnd.Quiet, voice.state.value.notice?.reason)
        } finally {
            voiceScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pausedMotionAllowsQuietListeningToExpire() = runTest {
        val core = core()
        val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ctx = TestContext(temp.newFolder())
            fakeFiles(ctx)
            val voice = VoiceController(ctx, core, voiceScope)
            until { voice.state.value.installed == VoiceModel.Compact }
            voice.appShown()
            voice.start()
            val id = ctx.launches.single().getIntExtra(VoiceService.EXTRA_SESSION, 0)
            voice.receive(VoiceProtocol.message(VoiceProtocol.LISTENING, id))
            core.dispatch(UserIntent.Connect(DeviceId("handy")))
            runCurrent()
            core.dispatch(UserIntent.StartMotion)
            runCurrent()
            core.dispatch(UserIntent.PauseMotion)
            runCurrent()
            val phase = core.state.value.run.phase
            assertEquals(RunPhase.Paused, phase)
            ShadowSystemClock.advanceBy(JavaDuration.ofMinutes(16))
            advanceTimeBy(1001)
            runCurrent()
            assertNull(voice.state.value.listening)
            assertEquals(
                VoiceEnd.Quiet,
                voice.state.value.notice?.reason,
            )
        } finally {
            voiceScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
