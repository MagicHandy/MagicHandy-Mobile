package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.SliderReading
import com.magichandy.mobile.core.ports.StartupPosition
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class StartupReconciliationTest {
    private open class Peer(val stationary: SliderReading, override val reversed: Boolean) :
        StartupPosition {
        var measured = stationary
        var arrives = true
        var halts = 0
        var lead: MotionBatch? = null

        override suspend fun halt(fence: OutputFence): StopEvidence {
            halts++
            return StopEvidence.Acknowledged
        }

        override suspend fun read(fence: OutputFence): SliderReading? = measured

        override suspend fun position(batch: MotionBatch, fence: OutputFence): WriteResult {
            lead = batch
            if (arrives) {
                val mark = batch.frames.last().position.value
                val goal =
                    batch.limits.strokeLow.value +
                        (if (reversed) 100 - mark else mark) / 100.0 *
                            (batch.limits.strokeHigh.value - batch.limits.strokeLow.value)
                measured = measured.copy(positionAbsolute = goal)
            }
            return WriteResult.Accepted
        }
    }

    private val stationary = SliderReading(95.0, 0.0, 0.0, 100.0, 0.0, 100.0)
    private val first =
        batch.copy(
            frames = listOf(MotionFrame(0, Percent(20))),
            limits = Limits(Percent(60), Percent(20), Percent(80)),
        )

    @Test
    fun `startup uses measured position verifies encoded arrival and halts before normal play`() =
        runTest {
            for (reverse in listOf(false, true)) {
                val link = Peer(stationary, reverse)
                assertEquals(
                    StartupResult.Ready,
                    reconcileStartup(
                        link,
                        first,
                        OutputFence(com.magichandy.mobile.core.ports.Epoch(1)) { true },
                    ),
                )
                val frames = requireNotNull(link.lead).frames
                assertEquals(if (reverse) 5 else 95, frames.first().position.value)
                assertEquals(if (reverse) 68.0 else 32.0, link.measured.positionAbsolute)
                assertEquals(2, link.halts)
                assertEquals(10000L, frames.last().timeMillis - frames[1].timeMillis)
            }
        }

    @Test
    fun `invalid telemetry and missed arrival never admit the main stream`() = runTest {
        for (state in
            listOf(
                stationary,
                stationary.copy(speedAbsolute = 10.0),
                stationary.copy(minimumAbsolute = 110.0),
            )) {
            val link = Peer(state, false).apply { arrives = false }
            val owner = FakeOwner("measured")
            val positioned =
                object : DeviceTransport by owner {
                    override val startupPosition = link
                }
            val engine = EngineFixture(this).engine
            engine.attach(positioned)
            assertTrue(engine.admit(engine.epoch, listOf(positioned)))
            assertEquals(
                StartupResult.Uncertain,
                engine.prepare(engine.stream(engine.epoch), positioned, first, false),
            )
            runCurrent()
            assertTrue(owner.stops >= 1)
            assertEquals(0, owner.writes)
        }
    }

    @Test
    fun `a resume miss needs a correlated second halt and stationary in-range readback`() =
        runTest {
            val fence = OutputFence(com.magichandy.mobile.core.ports.Epoch(1)) { true }
            for (failure in listOf("halt", "moving", "missing", "outside", "drift")) {
                val link =
                    object : Peer(stationary, false) {
                            override suspend fun halt(fence: OutputFence): StopEvidence {
                                val result = super.halt(fence)
                                return if (failure == "halt" && halts == 2)
                                    StopEvidence.Unconfirmed(
                                        com.magichandy.mobile.core.api.UnconfirmedCause.NoReply
                                    )
                                else result
                            }

                            override suspend fun read(fence: OutputFence): SliderReading? =
                                if (halts < 2) measured
                                else
                                    when (failure) {
                                        "moving" -> measured.copy(speedAbsolute = 1.0)
                                        "missing" -> null
                                        "outside" -> measured.copy(positionAbsolute = 120.0)
                                        "drift" -> measured.copy(positionAbsolute = 85.0)
                                        else -> measured
                                    }
                        }
                        .apply { arrives = false }
                assertEquals(
                    StartupResult.Uncertain,
                    reconcileStartup(link, first, fence, true),
                    failure,
                )
                assertEquals(2, link.halts)
            }
        }

    @Test
    fun `an unsuccessful startup Stop prevents calibration reads and all writes`() = runTest {
        val peer =
            object : StartupPosition {
                override val reversed = false

                override suspend fun halt(fence: OutputFence) =
                    StopEvidence.Unconfirmed(
                        com.magichandy.mobile.core.api.UnconfirmedCause.NoReply
                    )

                override suspend fun read(fence: OutputFence): SliderReading =
                    error("read after failed Stop")

                override suspend fun position(batch: MotionBatch, fence: OutputFence): WriteResult =
                    error("write after failed Stop")
            }
        assertEquals(
            StartupResult.Uncertain,
            reconcileStartup(
                peer,
                first,
                OutputFence(com.magichandy.mobile.core.ports.Epoch(1)) { true },
            ),
        )
    }

    @Test
    fun `Stop during the lead-in leaves the main stream fenced`() = runTest {
        val link = Peer(stationary, false)
        val device = FakeOwner("measured")
        val owner =
            object : DeviceTransport by device {
                override val startupPosition = link
            }
        val engine = EngineFixture(this).engine
        engine.attach(owner)
        assertTrue(engine.admit(engine.epoch, listOf(owner)))
        val stream = engine.stream(engine.epoch)
        val preparing = async { engine.prepare(stream, owner, first, true) }
        runCurrent()
        engine.stop(StopSource.AppFooter)
        assertEquals(StartupResult.Uncertain, preparing.await())
        assertEquals(WriteResult.Stale, engine.write(stream, owner, first))
        assertEquals(0, device.writes)
    }
}
