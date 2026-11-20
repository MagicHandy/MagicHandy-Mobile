package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
internal class MotionEngineTest {
    @Test
    fun `Stop fences admission immediately and a stalled owner cannot delay another acknowledgement`() =
        runTest {
            val (engine, slow, fast) =
                Triple(EngineFixture(this).engine, FakeOwner("slow"), FakeOwner("fast"))
            slow.onStop = { awaitCancellation() }
            listOf(slow, fast).forEach(engine::attach)
            val earlier = engine.epoch
            assertTrue(engine.admit(earlier, listOf(slow, fast)))
            engine.stop(StopSource.AppFooter)
            assertTrue(engine.epoch.value > earlier.value)
            assertTrue(engine.state.value.members.isEmpty())
            assertFalse(engine.admit(earlier, listOf(fast)))
            assertEquals(WriteResult.Stale, engine.write(earlier, slow, batch))
            assertTrue(
                requireNotNull(engine.state.value.receipt).outcomes.all {
                    it.outcome == StopOutcome.Pending
                }
            )
            runCurrent()
            assertEquals(
                listOf(StopOutcome.Pending, StopOutcome.Confirmed),
                requireNotNull(engine.state.value.receipt).outcomes.map { it.outcome },
            )
            advanceTimeBy(400)
            runCurrent()
            assertEquals(
                UnconfirmedCause.NoReply,
                requireNotNull(engine.state.value.receipt).outcomes.first().cause,
            )
            assertTrue(coroutineContext[Job]?.children?.none() == true)
        }

    @Test
    fun `a second press shares outstanding Stop and keeps uncertainty after disconnect and dismissal`() =
        runTest {
            val engine = EngineFixture(this).engine
            val owner = FakeOwner("bridge")
            owner.onStop = { StopEvidence.Unconfirmed(UnconfirmedCause.BridgeOnly) }
            engine.attach(owner)
            engine.stop(StopSource.Notification)
            val before = engine.epoch
            engine.stop(StopSource.TalkBackAction)
            runCurrent()
            assertEquals(1, owner.stops)
            assertTrue(engine.epoch.value > before.value)
            engine.detach(owner)
            engine.dismissReceipt()
            engine.stop(StopSource.QuickSettingsTile)
            val receipt = requireNotNull(engine.state.value.receipt)
            assertEquals(3, receipt.pressCount)
            assertEquals(UnconfirmedCause.BridgeOnly, receipt.outcomes.single().cause)
            assertFalse(receipt.isNoDevice)
        }

    @Test
    fun `a local check during a pending Stop never admits old motion or revives the row on a late reply`() =
        runTest {
            val engine = EngineFixture(this).engine
            val owner = FakeOwner("bridge")
            val reply = CompletableDeferred<StopEvidence>()
            owner.onStop = { reply.await() }
            engine.attach(owner)
            val running = engine.epoch
            assertTrue(engine.admit(running, listOf(owner)))
            engine.stop(StopSource.AppFooter)
            runCurrent()
            val stopped = engine.epoch
            assertTrue(engine.reconcile(stopped, owner.device.id, interrupted = false))
            assertEquals(stopped, engine.epoch)
            assertEquals(WriteResult.Stale, engine.write(running, owner, batch))
            assertFalse(engine.admit(running, listOf(owner)))
            reply.complete(StopEvidence.Unconfirmed(UnconfirmedCause.BridgeOnly))
            runCurrent()
            assertTrue(engine.state.value.receipt?.outcomes.orEmpty().isEmpty())
            assertTrue(engine.state.value.members.isEmpty())
            assertEquals(0, owner.writes)
            assertEquals(1, owner.stops)
        }

    @Test
    fun `late SSE and queued writes cannot reopen admission after Stop`() = runTest {
        val fixture = EngineFixture(this)
        val engine = fixture.engine
        val owner = FakeOwner("handy")
        engine.attach(owner)
        val old = engine.epoch
        assertTrue(engine.admit(old, listOf(owner)))
        val queued = CompletableDeferred<Unit>()
        owner.beforeWrite = { queued.await() }
        val write = launch { assertEquals(WriteResult.Stale, engine.write(old, owner, batch)) }
        val late = CompletableDeferred<Unit>()
        engine.launchWork(old, WorkKind.Turn, fixture.dispatcher) {
            withContext(NonCancellable) {
                late.await()
                assertFalse(engine.admit(old, listOf(owner)))
                assertEquals(WriteResult.Stale, engine.write(old, owner, batch))
            }
        }
        runCurrent()
        engine.stop(StopSource.AppFooter)
        runCurrent()
        assertTrue(requireNotNull(engine.state.value.receipt).didCancelReply)
        assertEquals(1, owner.stops)
        late.complete(Unit)
        queued.complete(Unit)
        write.join()
        runCurrent()
        assertEquals(0, owner.writes)
        assertTrue(engine.state.value.work.isEmpty())
    }

    @Test
    fun `unknown delivery stops every owner and never resubmits that motion`() = runTest {
        val engine = EngineFixture(this).engine
        val first = FakeOwner("one")
        val second = FakeOwner("two")
        first.result = WriteResult.Uncertain
        listOf(first, second).forEach(engine::attach)
        val epoch = engine.epoch
        engine.admit(epoch, listOf(first, second))
        assertEquals(WriteResult.Uncertain, engine.write(epoch, first, batch))
        runCurrent()
        assertEquals(listOf(1, 1), listOf(first.stops, second.stops))
        assertEquals(WriteResult.Stale, engine.write(epoch, first, batch))
        assertEquals(1, first.writes)
        assertFalse(engine.admit(engine.epoch, listOf(first, second)))
        engine.detach(first)
        val fresh = FakeOwner("one")
        engine.attach(fresh)
        assertTrue(engine.admit(engine.epoch, listOf(fresh, second)))
    }

    @Test
    fun `revoking permission during a write recovers all owners and retires the failed owner`() =
        runTest {
            val engine = EngineFixture(this).engine
            val denied = FakeOwner("denied").apply { beforeWrite = { throw SecurityException() } }
            val healthy = FakeOwner("healthy")
            val owners = listOf(denied, healthy)
            owners.forEach(engine::attach)
            val epoch = engine.epoch
            assertTrue(engine.admit(epoch, owners))
            assertEquals(WriteResult.Uncertain, engine.write(epoch, denied, batch))
            runCurrent()
            assertEquals(listOf(1, 1), owners.map { it.stops })
            assertFalse(engine.admit(engine.epoch, owners))
        }

    @Test
    fun `a revoked Stop permission leaves siblings confirmed and a later press can succeed`() =
        runTest {
            val engine = EngineFixture(this).engine
            val denied = FakeOwner("denied").apply { onStop = { throw SecurityException() } }
            val healthy = FakeOwner("healthy")
            listOf(denied, healthy).forEach(engine::attach)
            engine.stop(StopSource.Notification)
            runCurrent()
            val outcomes = requireNotNull(engine.state.value.receipt).outcomes
            assertEquals(
                UnconfirmedCause.WriteFailed,
                outcomes.single { it.device == denied.device.id }.cause,
            )
            assertEquals(
                StopOutcome.Confirmed,
                outcomes.single { it.device == healthy.device.id }.outcome,
            )
            denied.onStop = { StopEvidence.Acknowledged }
            engine.stop(StopSource.AppFooter)
            runCurrent()
            assertTrue(requireNotNull(engine.state.value.receipt).isAllConfirmed)
        }

    @Test
    fun `a late Stop acknowledgement cannot erase a deadline or authorize output`() = runTest {
        val engine = EngineFixture(this).engine
        val owner = FakeOwner("late")
        val late = CompletableDeferred<Unit>()
        owner.onStop = {
            withContext(NonCancellable) { late.await() }
            StopEvidence.Acknowledged
        }
        engine.attach(owner)
        engine.stop(StopSource.Notification)
        runCurrent()
        advanceTimeBy(402)
        runCurrent()
        late.complete(Unit)
        runCurrent()
        assertFalse(engine.admit(engine.epoch, listOf(owner)))
        assertEquals(
            UnconfirmedCause.NoReply,
            requireNotNull(engine.state.value.receipt).outcomes.single().cause,
        )
    }

    @Test
    fun `Stop without owners reports no device and a dismissed receipt resets the press count`() =
        runTest {
            val engine = EngineFixture(this).engine
            engine.stop(StopSource.TaskRemoved)
            assertTrue(requireNotNull(engine.state.value.receipt).isNoDevice)
            engine.dismissReceipt()
            engine.stop(StopSource.TypedStop)
            assertEquals(1, requireNotNull(engine.state.value.receipt).pressCount)
        }
}
