package com.magichandy.mobile.transport.autoblow

import com.magichandy.mobile.core.api.Banner
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.transport.simulated.DebugFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
internal class AutoblowSessionTest {
    @Test
    fun `an uncertain Ultra go-to triggers the priority Stop and the 750 ms resend before confirmation`() =
        runTest {
            val calls = mutableListOf<Pair<Long, String>>()
            val idle = buildJsonObject { put("operationalMode", "ONLINE_CONNECTED") }
            var stateReply = idle
            var priorityReads = 0
            var lossCount = 0
            val wire =
                object : AutoblowWire {
                    override suspend fun state(priorityRead: Boolean): JsonObject {
                        if (priorityRead) priorityReads++
                        return stateReply
                    }

                    override suspend fun events(
                        opened: () -> Unit,
                        receive: (String) -> Unit,
                    ): Unit {
                        opened()
                        awaitCancellation()
                    }

                    override suspend fun put(
                        path: String,
                        body: JsonObject?,
                        stop: Boolean,
                        allowed: () -> Boolean,
                    ): JsonObject {
                        if (!allowed()) throw java.io.IOException("Fenced")
                        calls += testScheduler.currentTime to path
                        if (path == "goto") awaitCancellation()
                        return JsonObject(emptyMap())
                    }
                }
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        devices =
                            object : DeviceDirectory by d.devices {
                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: CoroutineScope,
                                ): DeviceTransport {
                                    val row = saved.single { it.ref.id == device }
                                    return AutoblowSession(
                                            row.ref,
                                            wire,
                                            d.clock,
                                            ownerScope,
                                            { row.settings },
                                            { lossCount++ },
                                            {},
                                        )
                                        .also { it.initialize() }
                                }
                            }
                    )
                }
            f.onboard()
            f.connect(DeviceId("ultra"))
            calls.clear()
            f.send(UserIntent.StartMotion)
            assertEquals("goto", calls.first().second)
            f.core.emergencyStop(StopSource.Notification)
            runCurrent()
            assertTrue(calls.any { it.second == "oscillate/stop" })
            advanceTimeBy(751)
            runCurrent()
            assertTrue(calls.any { it.first >= 750 && it.second == "oscillate/stop" })
            assertTrue(requireNotNull(f.core.state.value.stopReceipt).isAllConfirmed)
            assertTrue(priorityReads >= 3)
            stateReply = buildJsonObject { put("operationalMode", "UNKNOWN") }
            val at = calls.size
            advanceTimeBy(5000)
            runCurrent()
            assertEquals(at, calls.size)
            assertEquals(1, lossCount)
            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            assertEquals(
                StopOutcome.Unconfirmed,
                requireNotNull(f.core.state.value.stopReceipt).outcomes.single().outcome,
            )
            f.scope.cancel()
        }

    @Test
    fun `loss of the Ultra button feed stops a run despite healthy motor reads`() = runTest {
        var events = 0
        var moves = 0
        val loss = MutableSharedFlow<DeviceId>(extraBufferCapacity = 1)
        var buttonUp = true
        val wire =
            object : AutoblowWire {
                override suspend fun state(priorityRead: Boolean) = buildJsonObject {
                    put("operationalMode", "ONLINE_CONNECTED")
                }

                override suspend fun events(opened: () -> Unit, receive: (String) -> Unit) {
                    opened()
                    events++
                    if (buttonUp) {
                        delay(150)
                        buttonUp = false
                    }
                    throw java.io.IOException("Synthetic button path closed")
                }

                override suspend fun put(
                    path: String,
                    body: JsonObject?,
                    stop: Boolean,
                    allowed: () -> Boolean,
                ): JsonObject {
                    if (path == "goto" && allowed()) moves++
                    return state()
                }
            }
        val id = DeviceId("ultra")
        val f =
            DebugFixture(this) { d ->
                d.copy(
                    devices =
                        object : DeviceDirectory by d.devices {
                            override val losses = loss

                            override suspend fun connect(
                                device: DeviceId,
                                ownerScope: CoroutineScope,
                            ): DeviceTransport {
                                val row = saved.single { it.ref.id == device }
                                return AutoblowSession(
                                        row.ref,
                                        wire,
                                        d.clock,
                                        ownerScope,
                                        { row.settings },
                                        { loss.tryEmit(id) },
                                        {},
                                    )
                                    .also { it.initialize() }
                            }
                        }
                )
            }
        f.onboard()
        f.connect(id)
        f.send(UserIntent.StartMotion)
        advanceTimeBy(7000)
        runCurrent()
        assertEquals(1, events)
        assertTrue(moves > 0)
        assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
        assertTrue(f.core.state.value.banners.any { it is Banner.DeviceLost })
        f.scope.cancel()
    }
}
