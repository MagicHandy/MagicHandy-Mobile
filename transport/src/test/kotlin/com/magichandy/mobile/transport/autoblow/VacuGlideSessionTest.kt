package com.magichandy.mobile.transport.autoblow

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.transport.simulated.DebugFixture
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
internal class VacuGlideSessionTest {
    @Test
    fun `native tempo starts from chat and Stop during a valve pulse shuts the motor and both valves`() =
        runTest {
            var motor = 0
            var plus = false
            var minus = false
            var openings = 0
            val calls = mutableListOf<String>()
            val wire =
                object : AutoblowWire {
                    override suspend fun state(priorityRead: Boolean) = buildJsonObject {
                        put(
                            "operationalMode",
                            if (motor == 0) "ONLINE_CONNECTED" else "TARGET_SPEED_PLAYING",
                        )
                        put("strokePlusValve", plus)
                        put("strokeMinusValve", minus)
                    }

                    override suspend fun events(receive: (String) -> Unit) = awaitCancellation()

                    override suspend fun put(
                        path: String,
                        body: JsonObject?,
                        stop: Boolean,
                        allowed: () -> Boolean,
                    ): JsonObject {
                        if (!allowed()) throw java.io.IOException("Fenced")
                        calls += path
                        when (path) {
                            "target-speed" ->
                                motor =
                                    requireNotNull(body).getValue("targetSpeed").jsonPrimitive.int
                            "target-speed/stop" -> motor = 0
                            "valve/stroke-plus" -> {
                                plus =
                                    requireNotNull(body)
                                        .getValue("valveState")
                                        .jsonPrimitive
                                        .boolean
                                if (plus) openings++
                            }
                            "valve/stroke-minus" ->
                                minus =
                                    requireNotNull(body)
                                        .getValue("valveState")
                                        .jsonPrimitive
                                        .boolean
                            else -> error("Unexpected wire operation")
                        }
                        return state()
                    }
                }
            var response =
                """{"reply":"Starting.","tempo":{"mode":"steady","level_percent":100},"air_steps":null,"stop":false}"""
            val provider =
                object : StructuredProvider {
                    override suspend fun complete(
                        request: ModelRequest,
                        preview: (String) -> Unit,
                    ) = TextResult.Completed(response)

                    override suspend fun stream(
                        messages: List<TextMessage>,
                        preview: (String) -> Unit,
                    ): TextResult = error("Unexpected text call")
                }
            val id = DeviceId("vacuglide")
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        provider = { provider },
                        devices =
                            object : DeviceDirectory by d.devices {
                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: CoroutineScope,
                                ): DeviceTransport {
                                    val ref = saved.single { it.ref.id == device }.ref
                                    return AutoblowSession(
                                            ref,
                                            wire,
                                            d.clock,
                                            ownerScope,
                                            { saved.single { it.ref.id == device }.settings },
                                            {},
                                            {},
                                        )
                                        .also { it.initialize() }
                                }
                            },
                    )
                }
            f.onboard()
            f.connect(id)
            f.send(UserIntent.SendMessage("Choose a tempo."))
            assertNotNull(f.d.journal.read())
            advanceTimeBy(1500)
            runCurrent()
            assertEquals(100, motor)
            f.send(UserIntent.SetValveSteps(id, true))
            response = """{"reply":"Adjusting.","tempo":null,"air_steps":1,"stop":false}"""
            f.send(UserIntent.SendMessage("One permitted step."))
            advanceTimeBy(110)
            runCurrent()
            assertTrue(plus)
            assertEquals(1, openings)
            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            advanceTimeBy(1800)
            runCurrent()
            assertEquals(0, motor)
            assertFalse(plus)
            assertFalse(minus)
            assertEquals(1, openings)
            assertTrue(requireNotNull(f.core.state.value.stopReceipt).isAllConfirmed)
            assertNull(f.d.journal.read())
            assertFalse("goto" in calls)
            f.scope.cancel()
        }
}
