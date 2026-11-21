package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.transport.simulated.DebugFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class LinkedStartTest {
    @Test
    fun `no linked Handy plays while a peer is still filling its first buffer and Stop cancels the barrier`() =
        runTest {
            for (cancel in listOf(false, true)) {
                val slow = CompletableDeferred<Unit>()
                val started = mutableListOf<DeviceId>()
                val f =
                    DebugFixture(this) { d ->
                        d.copy(
                            devices =
                                object : DeviceDirectory by d.devices {
                                    override suspend fun connect(
                                        device: DeviceId,
                                        ownerScope: CoroutineScope,
                                    ): DeviceTransport {
                                        val ref = saved.single { it.ref.id == device }.ref
                                        val wire =
                                            object : HandyWire {
                                                override val maxPoints = 20
                                                override val stopTimeout = 2.seconds

                                                override suspend fun command(
                                                    command: HandyCommand,
                                                    allowed: () -> Boolean,
                                                ): WriteResult {
                                                    if (
                                                        device.value != "handy" &&
                                                            command is HandyCommand.Add
                                                    )
                                                        slow.await()
                                                    if (!allowed()) return WriteResult.Stale
                                                    if (command is HandyCommand.Play)
                                                        started += device
                                                    return WriteResult.Accepted
                                                }

                                                override suspend fun stop(allowed: () -> Boolean) =
                                                    StopEvidence.Acknowledged
                                            }
                                        return HandyTransport(ref, wire)
                                    }
                                }
                        )
                    }
                f.onboard()
                f.send(UserIntent.AddCloudHandy("Second", Secret("synthetic")))
                val first = DeviceId("handy")
                val second = f.core.state.value.devices.devices.single { it.name == "Second" }.id
                f.connect(first)
                f.connect(second)
                f.send(UserIntent.StartMotion)
                runCurrent()
                assertTrue(started.isEmpty())
                assertEquals(2, f.d.journal.read()?.devices?.size)
                if (cancel) f.core.emergencyStop(StopSource.AppFooter)
                slow.complete(Unit)
                runCurrent()
                assertEquals(if (cancel) emptySet() else setOf(first, second), started.toSet())
                f.scope.cancel()
            }
        }
}
