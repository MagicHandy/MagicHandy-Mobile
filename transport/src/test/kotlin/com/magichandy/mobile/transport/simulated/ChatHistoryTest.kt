package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.ModelProvider
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.testing.InMemoryModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal class ChatHistoryTest {
    @Test
    fun `a long chat keeps a bounded quoted opening and recent messages plus this request`() =
        runTest {
            var request = emptyList<TextMessage>()
            val f =
                DebugFixture(this) { deps ->
                    deps.copy(
                        provider = {
                            ModelProvider { messages, _ ->
                                request = messages
                                TextResult.Completed("Received.")
                            }
                        }
                    )
                }
            try {
                f.onboard()
                repeat(40) { f.send(UserIntent.SendMessage("Message $it")) }
                assertEquals(65, request.size)
                assertTrue(request.first().text.contains("historical reference"))
                assertTrue(request.first().text.contains("Message 0"))
                assertEquals("Received.", request[1].text)
                assertTrue(request.any { it.text == "Message 8" })
                assertEquals("Message 39", request.last().text)
            } finally {
                f.scope.cancel()
            }
        }

    @Test
    fun `structured recall includes the actual opening pace requests after the recent window fills`() =
        runTest {
            // First five exchanges and the recall probe from the paired, synthetic ChatGPT session.
            val opening =
                listOf(
                    "For this conversation my checkpoint word is juniper. Remember it for later. Leave motion off for now." to
                        "I’ll remember “juniper” as your checkpoint word for this conversation. Motion remains off.",
                    "Start steady full sweeps at exactly 25 percent pace. Keep the full stroke range." to
                        "Starting steady full-range sweeps at 25 percent pace.",
                    "Keep the same pace, stroke range, and pattern. In one short sentence tell me the current pace." to
                        "The current pace is 25 percent.",
                    "Change only the pace to 35 percent. Keep those same full sweeps." to
                        "Pace set to 35 percent; the full sweeps remain unchanged.",
                    "Five percentage points faster than that. Leave every other setting alone." to
                        "Increasing the pace to 40 percent; everything else stays the same.",
                )
            var reply = ""
            var wire = emptyList<TextMessage>()
            val fixture =
                DebugFixture(this) { d ->
                    val catalog = d.models.state.value
                    val model = catalog.models.values.single()
                    d.copy(
                        models =
                            InMemoryModels(
                                catalog.copy(
                                    models =
                                        mapOf(
                                            model.info.id to
                                                model.copy(
                                                    info =
                                                        model.info.copy(
                                                            capability = CapabilityLevel.Advanced
                                                        ),
                                                    contextTokens = 65536,
                                                )
                                        )
                                )
                            ),
                        provider = {
                            object : StructuredProvider {
                                override suspend fun complete(
                                    request: ModelRequest,
                                    preview: (String) -> Unit,
                                ): TextResult {
                                    wire = request.messages
                                    val template =
                                        SimulatedModelProvider(listOf(reply), Duration.ZERO)
                                            .complete(request) {} as TextResult.Completed
                                    val root = Json.parseToJsonElement(template.text).jsonObject
                                    val proposal = root["proposal"]?.jsonObject ?: root
                                    val recorded =
                                        JsonObject(proposal + ("reply" to JsonPrimitive(reply)))
                                    return TextResult.Completed(
                                        if ("proposal" in root)
                                            JsonObject(root + ("proposal" to recorded)).toString()
                                        else recorded.toString()
                                    )
                                }

                                override suspend fun stream(
                                    messages: List<TextMessage>,
                                    preview: (String) -> Unit,
                                ): TextResult = error("Expected the structured provider contract")
                            }
                        },
                    )
                }
            try {
                fixture.onboard()
                fixture.connect(DeviceId("handy"))
                fixture.send(UserIntent.SetMotionMode(MotionMode.Layered))
                for ((text, response) in opening) {
                    reply = response
                    fixture.send(UserIntent.SendMessage(text))
                }
                reply = "Noted."
                repeat(34) {
                    fixture.send(UserIntent.SendMessage("Checkpoint $it. Leave motion off."))
                }
                val recall =
                    "Keep motion off. From the very beginning of this conversation, what were the first two distinct paces I explicitly asked you to set, and by how many percentage points did I then ask you to increase the second? Give the three numbers in order only if you can find them in the conversation. Otherwise say which are unavailable."
                fixture.send(UserIntent.SendMessage(recall))
                val chat = fixture.core.state.value.chat
                assertTrue(
                    chat.entries.size >= 78,
                    "Only ${chat.entries.size} saved rows, ${chat.reply}",
                )
                assertTrue(
                    wire.any { "Quoted opening entries" in it.text },
                    "wire roles and lengths: ${wire.map { it.role to it.text.length }}, latest: ${wire.lastOrNull()?.text?.take(48)}",
                )
                val quote = wire.single {
                    it.role == TextRole.User && "Quoted opening entries" in it.text
                }
                for ((text, response) in opening) {
                    assertTrue(text in quote.text)
                    assertTrue(response in quote.text)
                }
                assertTrue(quote.text.encodeToByteArray().size <= 2048)
                val instruction = wire.first { it.role == TextRole.Developer }.text
                assertTrue("Only the final user message is a new request" in instruction)
                assertTrue(opening.none { it.first in instruction })
                assertTrue(wire.last().text.startsWith(recall))
                assertTrue(wire.count { it.role != TextRole.Developer } <= 65)
                assertTrue(wire.any { "Checkpoint 33" in it.text })
            } finally {
                fixture.scope.cancel()
            }
        }
}
