package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.LimitKind
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.StepDirection
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.motion.MotionFrame
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.transport.simulated.DebugFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class HandyMountingTest {
    private class Peer : HandyWire {
        override val maxPoints = 20
        override val stopTimeout = 2.seconds
        val commands = mutableListOf<HandyCommand>()

        override suspend fun command(command: HandyCommand, allowed: () -> Boolean): WriteResult {
            if (!allowed()) return WriteResult.Stale
            commands.add(command)
            return WriteResult.Accepted
        }

        override suspend fun stop(allowed: () -> Boolean) = StopEvidence.Acknowledged
    }

    @Test
    fun `a stream outside the wire time range cannot change the stroke window or initialize HSP`() =
        runTest {
            val id = DeviceId("handy")
            val peer = Peer()
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        devices =
                            object : DeviceDirectory by d.devices {
                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: CoroutineScope,
                                ): DeviceTransport {
                                    val handy =
                                        HandyTransport(
                                            saved.first { it.ref.id == device }.ref,
                                            peer,
                                        )
                                    return object : DeviceTransport by handy {
                                        override suspend fun write(
                                            batch: MotionBatch,
                                            fence: OutputFence,
                                        ) =
                                            handy.write(
                                                batch.copy(
                                                    frames =
                                                        listOf(MotionFrame(1L shl 32, Percent(50)))
                                                ),
                                                fence,
                                            )
                                    }
                                }
                            }
                    )
                }
            f.onboard()
            f.connect(id)
            f.send(UserIntent.StartMotion)
            assertTrue(peer.commands.isEmpty())
            f.scope.cancel()
        }

    @Test
    fun `a saved mounting mirrors every quantized point within the same physical window on a fresh stream`() =
        runTest {
            val id = DeviceId("handy")
            val peer = Peer()
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        devices =
                            object : DeviceDirectory by d.devices {
                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: CoroutineScope,
                                ): DeviceTransport {
                                    return HandyTransport(
                                        saved.first { it.ref.id == device }.ref,
                                        peer,
                                    ) {
                                        (saved.first { it.ref.id == device }.settings
                                                as DeviceSettings.Handy)
                                            .isReversed
                                    }
                                }
                            }
                    )
                }
            fun firstPoints() =
                peer.commands.filterIsInstance<HandyCommand.Add>().flatMap { it.frames }
            f.onboard()
            f.connect(id)
            f.send(UserIntent.StepLimit(LimitKind.StrokeLow, StepDirection.Up))
            f.send(UserIntent.StartMotion)
            val forward = firstPoints()
            f.send(UserIntent.SetReversed(id, true))
            assertTrue(
                (f.d.devices.saved.first { it.ref.id == id }.settings as DeviceSettings.Handy)
                    .isReversed
            )
            f.send(UserIntent.PauseMotion)
            peer.commands.clear()
            f.send(UserIntent.ResumeMotion)
            val reverse = firstPoints()
            assertEquals(forward.map { it.timeMillis }, reverse.map { it.timeMillis })
            assertEquals(forward.map { 100 - it.position.value }, reverse.map { it.position.value })
            assertEquals(
                listOf(HandyCommand.Stroke(5.0, 100.0)),
                peer.commands.filterIsInstance<HandyCommand.Stroke>(),
            )
            f.send(UserIntent.StepLimit(LimitKind.StrokeHigh, StepDirection.Down))
            advanceTimeBy(4000)
            runCurrent()
            assertEquals(
                listOf(HandyCommand.Stroke(5.0, 100.0), HandyCommand.Stroke(5.0, 95.0)),
                peer.commands.filterIsInstance<HandyCommand.Stroke>(),
            )
            assertEquals(1, peer.commands.count { it is HandyCommand.Setup })
            assertEquals(1, peer.commands.count { it is HandyCommand.Play })
            f.scope.cancel()
        }
}
