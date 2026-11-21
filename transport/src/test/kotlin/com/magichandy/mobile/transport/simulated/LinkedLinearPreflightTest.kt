package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.LinearSampling
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.StartupPosition
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
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
internal class LinkedLinearPreflightTest {
    @Test
    fun `an infeasible bridge in a linked run fences every owner before startup positioning`() =
        runTest {
            val writes = mutableListOf<DeviceId>()
            val stops = mutableListOf<DeviceId>()
            var preparations = 0
            val fixture =
                DebugFixture(this) { d ->
                    d.copy(
                        devices =
                            object : DeviceDirectory by d.devices {
                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: CoroutineScope,
                                ): DeviceTransport {
                                    val ref = saved.single { it.ref.id == device }.ref
                                    return object : DeviceTransport {
                                        override val device = ref
                                        override val stopTimeout = 1.seconds
                                        override val linearSampling =
                                            if (ref.family == DeviceFamily.LinearActuator)
                                                LinearSampling(2000, 100)
                                            else null
                                        override val startupPosition =
                                            if (ref.family == DeviceFamily.Handy)
                                                object : StartupPosition {
                                                    override val reversed = false

                                                    override suspend fun halt(
                                                        fence: OutputFence
                                                    ): StopEvidence {
                                                        preparations++
                                                        return StopEvidence.Acknowledged
                                                    }

                                                    override suspend fun read(fence: OutputFence) =
                                                        null

                                                    override suspend fun position(
                                                        batch: MotionBatch,
                                                        fence: OutputFence,
                                                    ): WriteResult {
                                                        writes += ref.id
                                                        return WriteResult.Accepted
                                                    }
                                                }
                                            else null

                                        override suspend fun write(
                                            batch: MotionBatch,
                                            fence: OutputFence,
                                        ): WriteResult {
                                            writes += ref.id
                                            return WriteResult.Accepted
                                        }

                                        override suspend fun stop(
                                            fence: OutputFence
                                        ): StopEvidence {
                                            stops += ref.id
                                            return StopEvidence.Acknowledged
                                        }
                                    }
                                }
                            }
                    )
                }
            fixture.onboard()
            val address = "ws://127.0.0.1:12345"
            val choice = fixture.core.intifaceLinearActuators(address).single()
            assertEquals(
                FormResult.Accepted,
                fixture.core.submit(
                    UserIntent.AddIntifaceLinear(
                        address,
                        choice.deviceIndex,
                        choice.actuatorIndex,
                        choice.selection,
                    )
                ),
            )
            runCurrent()
            val handy = DeviceId("handy")
            val bridge =
                fixture.devices.saved.single { it.ref.family == DeviceFamily.LinearActuator }.ref.id
            fixture.connect(handy)
            fixture.connect(bridge)
            stops.clear()
            fixture.send(UserIntent.StartMotion)
            advanceTimeBy(4000)
            runCurrent()
            assertEquals(0, preparations)
            assertTrue(writes.isEmpty())
            assertEquals(setOf(handy, bridge), stops.toSet())
            assertEquals(RunPhase.Idle, fixture.core.state.value.run.phase)
            fixture.scope.cancel()
        }
}
