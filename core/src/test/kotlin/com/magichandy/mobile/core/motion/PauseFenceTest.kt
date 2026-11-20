package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class PauseFenceTest {
    @Test
    fun `an explicit Stop supersedes the pending Pause halt and its late acknowledgement`() =
        runTest {
            val engine = EngineFixture(this).engine
            val owner = FakeOwner("Handy")
            engine.attach(owner)
            val epoch = engine.epoch
            assertTrue(engine.admit(epoch, listOf(owner)))
            val prior = engine.stream(epoch)
            val pending = CompletableDeferred<Unit>()
            owner.onStop = { fence ->
                if (owner.stops == 1) {
                    pending.await()
                    assertFalse(fence.isCurrent())
                }
                StopEvidence.Acknowledged
            }
            val journal = assertNotNull(engine.pause(epoch, PlaybackPosition(0.25)))
            runCurrent()
            assertFalse(engine.resume(epoch, journal))
            assertEquals(WriteResult.Stale, engine.write(prior, owner, batch))
            engine.stop(StopSource.QuickSettingsTile)
            runCurrent()
            assertEquals(2, owner.stops)
            assertTrue(engine.state.value.receipt?.isAllConfirmed == true)
            pending.complete(Unit)
            runCurrent()
            assertNull(engine.state.value.pausedPosition)
            assertFalse(engine.resume(epoch, journal))
            assertEquals(0, owner.writes)
        }
}
