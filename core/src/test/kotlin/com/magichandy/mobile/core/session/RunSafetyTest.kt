package com.magichandy.mobile.core.session

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.StartRefusal
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.motion.EngineFixture
import com.magichandy.mobile.core.motion.FakeOwner
import com.magichandy.mobile.core.motion.PlaybackPosition
import com.magichandy.mobile.core.motion.WorkKind
import com.magichandy.mobile.core.motion.batch
import com.magichandy.mobile.core.motion.write
import com.magichandy.mobile.core.ports.RecoveryStops
import com.magichandy.mobile.core.ports.RunHost
import com.magichandy.mobile.core.ports.RunId
import com.magichandy.mobile.core.ports.RunIntent
import com.magichandy.mobile.core.ports.RunJournal
import com.magichandy.mobile.core.ports.RunJournalStore
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.core.testing.InMemoryRunJournal
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class RunSafetyTest {
    private class Scenario(val test: TestScope) {
        val fixture = EngineFixture(test)
        val engine = fixture.engine
        val owner = FakeOwner("handy", ConnectionKind.HandyCloud)
        val store = InMemoryRunJournal()
        val sent = mutableListOf<String>()
        var recoveryEvidence: StopEvidence = StopEvidence.Acknowledged

        fun safety(journal: RunJournalStore = store, host: RunHost = RunHost { true }): RunSafety =
            RunSafety(
                engine,
                journal,
                host,
                RecoveryStops { ref, fence ->
                    assertTrue(fence.isCurrent())
                    sent += ref.name
                    recoveryEvidence
                },
                fixture.clock,
                test.backgroundScope,
                fixture.dispatcher,
            )

        suspend fun arm(safety: RunSafety, intended: RunIntent = RunIntent.Start): StartDecision {
            engine.attach(owner)
            return safety.start(engine.epoch, listOf(owner), intended)
        }
    }

    @Test
    fun `a live VacuGlide satisfies its return Stop through the footer or journal-less recovery`() =
        runTest {
            for (footer in listOf(false, true)) {
                val s = Scenario(this)
                val safety = s.safety()
                runCurrent()
                val vacu = FakeOwner("vacu", ConnectionKind.Bluetooth, DeviceFamily.VacuGlide)
                safety.lost(vacu.device)
                s.engine.attach(vacu)
                if (footer) safety.emergencyStop(StopSource.AppFooter)
                else safety.retryRecovery(vacu.device.id)
                runCurrent()
                assertEquals(1, vacu.stops)
                assertFalse(safety.awaitsReturn(vacu.device.id))
            }
        }

    @Test
    fun `Stop crosses a suspended Resume journal transition and a late commit cannot reopen output`() =
        runTest {
            val s = Scenario(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val store =
                object : RunJournalStore by s.store {
                    override suspend fun transition(record: RunJournal): Boolean {
                        if (record.intended == RunIntent.Start) {
                            entered.complete(Unit)
                            release.await()
                        }
                        return s.store.transition(record)
                    }
                }
            val safety = s.safety(store)
            assertEquals(StartDecision.Admitted, s.arm(safety))
            val epoch = s.engine.epoch
            val old = s.engine.stream(epoch)
            safety.pause(epoch, PlaybackPosition(0.375))
            runCurrent()
            assertEquals(WriteResult.Stale, s.engine.write(old, s.owner, batch))
            val resuming = async { safety.resume(epoch) }
            entered.await()
            safety.emergencyStop(StopSource.Notification)
            runCurrent()
            assertTrue(s.engine.state.value.receipt?.isAllConfirmed == true)
            assertEquals(2, s.owner.stops)
            release.complete(Unit)
            assertEquals(StartDecision.Superseded, resuming.await())
            runCurrent()
            assertEquals(WriteResult.Stale, s.engine.write(old, s.owner, batch))
            assertEquals(0, s.owner.writes)
            assertNull(s.store.read())
        }

    @Test
    fun `Resume requires a fresh durable transition and the service even after a confirmed Pause`() =
        runTest {
            for (serviceFails in listOf(false, true)) {
                val s = Scenario(this)
                var serviceReady = true
                val store =
                    object : RunJournalStore by s.store {
                        override suspend fun transition(record: RunJournal): Boolean =
                            record.intended != RunIntent.Start && s.store.transition(record)
                    }
                val safety = s.safety(store, RunHost { serviceReady })
                assertEquals(StartDecision.Admitted, s.arm(safety))
                val epoch = s.engine.epoch
                safety.pause(epoch, PlaybackPosition(0.5))
                runCurrent()
                serviceReady = !serviceFails
                assertEquals(
                    StartDecision.Refused(
                        if (serviceFails) StartRefusal.ServiceUnavailable
                        else StartRefusal.JournalUnavailable
                    ),
                    safety.resume(epoch),
                )
                assertEquals(RunIntent.Paused, s.store.read()?.intended)
                assertEquals(WriteResult.Stale, s.engine.write(epoch, s.owner, batch))
                assertEquals(0, s.owner.writes)
                safety.emergencyStop(StopSource.QuickSettingsTile)
                runCurrent()
            }
        }

    @Test
    fun `a paused output job cannot write into a resumed stream and Pause leaves unrelated turns admitted`() =
        runTest {
            val s = Scenario(this)
            val safety = s.safety()
            assertEquals(StartDecision.Admitted, s.arm(safety))
            val epoch = s.engine.epoch
            val old = s.engine.stream(epoch)
            val turn =
                s.engine.launchWork(
                    epoch,
                    WorkKind.Turn,
                    s.fixture.dispatcher,
                ) {
                    awaitCancellation()
                }
            safety.pause(epoch, PlaybackPosition(0.31))
            runCurrent()
            assertTrue(turn.isActive)
            assertEquals(StartDecision.Admitted, safety.resume(epoch))
            assertEquals(WriteResult.Stale, s.engine.write(old, s.owner, batch))
            assertEquals(
                WriteResult.Accepted,
                s.engine.write(s.engine.stream(epoch), s.owner, batch),
            )
            safety.emergencyStop(StopSource.AppFooter)
            runCurrent()
            assertTrue(turn.isCancelled)
        }

    @Test
    fun `a boundary update cannot restore removed devices or recreate a cleared journal`() =
        runTest {
            val s = Scenario(this)
            val other = FakeOwner("second")
            val record =
                RunJournal(
                    RunId("original"),
                    listOf(s.owner.device, other.device),
                    RunIntent.Start,
                    false,
                    s.fixture.clock.now(),
                )
            assertTrue(s.store.armIfEmpty(record))
            s.store.reconcile(record.runId, setOf(other.device.id))
            assertFalse(s.store.transition(record.copy(intended = RunIntent.Paused)))
            assertEquals(listOf(s.owner.device), s.store.read()?.devices)
            s.store.reconcile(record.runId, setOf(s.owner.device.id))
            assertFalse(s.store.transition(record))
            assertNull(s.store.read())
        }

    @Test
    fun `the service and durable journal precede every motion capable write including test motor`() =
        runTest {
            for (intent in listOf(RunIntent.Start, RunIntent.TestMotor)) {
                val s = Scenario(this)
                var isUp = false
                val safety =
                    s.safety(
                        host =
                            RunHost {
                                isUp = true
                                true
                            }
                    )
                assertEquals(StartDecision.Admitted, s.arm(safety, intent))
                s.owner.beforeWrite = {
                    assertTrue(isUp)
                    assertEquals(intent, s.store.read()?.intended)
                }
                assertEquals(WriteResult.Accepted, s.engine.write(s.engine.epoch, s.owner, batch))
                assertEquals(1, s.owner.writes)
                s.engine.stop(StopSource.AppFooter)
                runCurrent()
                assertNull(s.store.read())
            }
        }

    @Test
    fun `failed journal arming or service startup refuses the start without a write`() = runTest {
        for (failedService in listOf(true, false)) {
            val s = Scenario(this)
            val store =
                object : RunJournalStore by s.store {
                    override suspend fun armIfEmpty(record: RunJournal): Boolean {
                        throw IOException("synthetic")
                    }
                }
            val safety = s.safety(store, RunHost { !failedService })
            assertEquals(
                StartDecision.Refused(
                    if (failedService) StartRefusal.ServiceUnavailable
                    else StartRefusal.JournalUnavailable
                ),
                s.arm(safety),
            )
            assertEquals(WriteResult.Stale, s.engine.write(s.engine.epoch, s.owner, batch))
            assertEquals(0, s.owner.writes)
        }
    }

    @Test
    fun `Stop overtakes an arm stalled in storage and a late successful arm cannot start motion`() =
        runTest {
            val s = Scenario(this)
            val blocked = CompletableDeferred<Unit>()
            val store =
                object : RunJournalStore by s.store {
                    override suspend fun armIfEmpty(record: RunJournal): Boolean {
                        blocked.await()
                        return s.store.armIfEmpty(record)
                    }
                }
            val safety = s.safety(store)
            val starting = async { s.arm(safety) }
            runCurrent()
            s.engine.stop(StopSource.Notification)
            runCurrent()
            assertEquals(1, s.owner.stops)
            assertTrue(requireNotNull(s.engine.state.value.receipt).isAllConfirmed)
            blocked.complete(Unit)
            assertEquals(StartDecision.Superseded, starting.await())
            runCurrent()
            assertEquals(0, s.owner.writes)
            assertNull(s.store.read())
        }

    @Test
    fun `restart between arming and first write sends only Stop to saved cloud devices and retains unresolved evidence`() =
        runTest {
            val s = Scenario(this)
            val bluetooth = FakeOwner("local")
            val vacu = FakeOwner("vacuglide", ConnectionKind.AutoblowCloud, DeviceFamily.VacuGlide)
            val record =
                RunJournal(
                    RunId("previous"),
                    listOf(vacu.device, bluetooth.device),
                    RunIntent.TestMotor,
                    true,
                    s.fixture.clock.now(),
                )
            s.store.armIfEmpty(record)
            val safety = s.safety()
            runCurrent()
            assertEquals(listOf("vacuglide"), s.sent)
            assertEquals(record, safety.interrupted.value)
            assertTrue(s.engine.state.value.members.isEmpty())
            assertEquals(listOf(bluetooth.device), s.store.read()?.devices)
            safety.dismissInterrupted()
            assertFalse(s.engine.state.value.canStart)
            assertEquals(
                UnconfirmedCause.AppStopped,
                s.engine.state.value.receipt?.outcomes?.first()?.cause,
            )
            s.engine.attach(bluetooth)
            safety.retryRecovery()
            runCurrent()
            assertEquals(1, bluetooth.stops)
            assertEquals(0, bluetooth.writes)
            assertNull(s.store.read())
        }

    @Test
    fun `automatic retry reaches only unresolved journal members after a local check`() = runTest {
        val s = Scenario(this)
        val other = FakeOwner("other")
        val owners = listOf(s.owner, other)
        owners.forEach { owner ->
            s.engine.attach(owner)
            owner.onStop = { StopEvidence.Unconfirmed(UnconfirmedCause.NoReply) }
        }
        val safety = s.safety()
        assertEquals(StartDecision.Admitted, safety.start(s.engine.epoch, owners, RunIntent.Start))
        s.engine.stop(StopSource.AppFooter)
        runCurrent()
        assertTrue(s.engine.reconcile(s.engine.epoch, s.owner.device.id, interrupted = false))
        runCurrent()
        other.onStop = { StopEvidence.Acknowledged }
        safety.retryRecovery()
        runCurrent()
        assertEquals(1, s.owner.stops)
        assertEquals(2, other.stops)
        assertNull(s.store.read())
    }

    @Test
    fun `an offline persistent device remains journaled until the recovery Stop on return is acknowledged`() =
        runTest {
            val s = Scenario(this)
            s.store.armIfEmpty(
                RunJournal(
                    RunId("lost"),
                    listOf(s.owner.device),
                    RunIntent.Start,
                    false,
                    s.fixture.clock.now(),
                )
            )
            s.recoveryEvidence = StopEvidence.Unconfirmed(UnconfirmedCause.Offline)
            val safety = s.safety()
            runCurrent()
            assertNotNull(s.store.read())
            assertEquals(
                StopOutcome.Unconfirmed,
                s.engine.state.value.receipt?.outcomes?.single()?.outcome,
            )
            s.recoveryEvidence = StopEvidence.Acknowledged
            safety.retryRecovery()
            runCurrent()
            assertEquals(listOf("handy", "handy"), s.sent)
            assertNull(s.store.read())
            assertTrue(s.engine.state.value.members.isEmpty())
        }

    @Test
    fun `Stop evidence cannot clear a journal while an older motion write may still land`() =
        runTest {
            val s = Scenario(this)
            val safety = s.safety()
            assertEquals(StartDecision.Admitted, s.arm(safety))
            val blocked = CompletableDeferred<Unit>()
            s.owner.beforeWrite = { blocked.await() }
            val writing = async { s.engine.write(s.engine.epoch, s.owner, batch) }
            runCurrent()
            s.engine.stop(StopSource.Notification)
            runCurrent()
            assertNotNull(s.store.read())
            assertEquals(
                StopOutcome.Pending,
                s.engine.state.value.receipt?.outcomes?.single()?.outcome,
            )
            blocked.complete(Unit)
            assertEquals(WriteResult.Stale, writing.await())
            runCurrent()
            assertNull(s.store.read())
        }

    @Test
    fun `a stale journal confirmation cannot clear a different run and an unreadable journal blocks all output`() =
        runTest {
            val s = Scenario(this)
            val record =
                RunJournal(
                    RunId("new"),
                    listOf(s.owner.device),
                    RunIntent.Start,
                    false,
                    s.fixture.clock.now(),
                )
            s.store.armIfEmpty(record)
            s.store.reconcile(RunId("old"), setOf(s.owner.device.id))
            assertEquals(record, s.store.read())
            val broken =
                object : RunJournalStore by s.store {
                    override suspend fun read(): RunJournal? {
                        throw IOException("synthetic")
                    }
                }
            val safety = s.safety(broken)
            assertEquals(StartDecision.Refused(StartRefusal.JournalUnavailable), s.arm(safety))
            assertTrue(safety.journalUnavailable.value)
            assertEquals(0, s.owner.writes)
        }
}
