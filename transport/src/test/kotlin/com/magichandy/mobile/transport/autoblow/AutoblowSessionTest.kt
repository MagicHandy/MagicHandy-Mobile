package com.magichandy.mobile.transport.autoblow

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.transport.simulated.DebugFixture
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

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

                    override suspend fun events(receive: (String) -> Unit) = awaitCancellation()

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
}
