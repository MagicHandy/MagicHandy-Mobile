package com.magichandy.mobile.transport.autoblow

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.LimitKind
import com.magichandy.mobile.core.api.StepDirection
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.transport.simulated.DebugFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
internal class VacuGlideSessionTest {
    @Test
    fun `native tempo starts from chat and Stop during a valve pulse shuts the motor and both valves`() =
        runTest {
            var wireCeiling = 100
            var refusedBeforeSend = false
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
                        if (path == "target-speed" && !refusedBeforeSend) {
                            wireCeiling = 0
                            assertFalse(allowed())
                            wireCeiling = 100
                            refusedBeforeSend = true
                            throw FencedAutoblowRequest()
                        }
                        if (!allowed()) throw FencedAutoblowRequest()
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
                                            topSpeed = { wireCeiling },
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
            assertTrue(refusedBeforeSend)
            assertEquals(80, motor)
            repeat(12) { f.send(UserIntent.StepLimit(LimitKind.TopSpeed, StepDirection.Down)) }
            advanceTimeBy(110)
            runCurrent()
            assertEquals(20, motor)
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
