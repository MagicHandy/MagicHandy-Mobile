package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.core.testing.*
import com.magichandy.mobile.transport.simulated.DebugFixture
import kotlin.test.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

internal class HostedParityTest {
    @TestFactory
    fun `public hosted turns reproduce every field of the alpha89 Terra bodies`() =
        listOf(
                Triple("terra-layered-en", MotionMode.Layered, AppLanguage.English),
                Triple("terra-creative_v2-es", MotionMode.Simple, AppLanguage.Spanish),
                Triple("terra-off-ja", MotionMode.Off, AppLanguage.Japanese),
            )
            .map { (name, mode, language) ->
                DynamicTest.dynamicTest(name) {
                    runTest {
                        val fixture =
                            requireNotNull(javaClass.getResourceAsStream("/" + name + ".json"))
                                .bufferedReader()
                                .use { Json.parseToJsonElement(it.readText()).jsonObject }
                        var requests = 0
                        val wire = MemoryHttp { req ->
                            assertEquals(fixture, Json.parseToJsonElement(req.payload()))
                            val reply =
                                when (mode) {
                                    MotionMode.Layered ->
                                        """{"proposal":{"action":"none","edits":{},"reply":"Ready."}}"""
                                    MotionMode.Simple ->
                                        """{"proposal":{"action":"none","edits":[],"reply":"Ready."}}"""
                                    else -> """{"reply":"Ready."}"""
                                }
                            requests++
                            val delta = buildJsonObject {
                                put("type", "response.output_text.delta")
                                put("delta", reply)
                            }
                            req.reply(
                                "data: " +
                                    delta +
                                    "\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
                                "text/event-stream",
                            )
                        }
                        val f =
                            DebugFixture(this) { d ->
                                val id = ConnectionId("terra")
                                val model =
                                    ModelConnection(
                                        id,
                                        "ChatGPT",
                                        ProviderKind.ChatGpt,
                                        "gpt-6.1-terra",
                                        "Terra",
                                        CapabilityLevel.Advanced,
                                        true,
                                        true,
                                        ConnectionStatus.Ready,
                                    )
                                d.copy(
                                    models =
                                        InMemoryModels(
                                            ModelCatalog(
                                                mapOf(
                                                    id to SavedModel(model, contextTokens = 131072)
                                                ),
                                                id,
                                            )
                                        ),
                                    settings =
                                        InMemorySettings(
                                            SettingsDocument(
                                                values =
                                                    Preferences(
                                                        adultsOnly = true,
                                                        language = language,
                                                        mode = mode,
                                                        limits =
                                                            Limits(
                                                                Percent(43),
                                                                Percent(0),
                                                                Percent(100),
                                                            ),
                                                    )
                                            )
                                        ),
                                    provider = {
                                        ResponsesProvider(
                                            wire,
                                            { Secret("synthetic") },
                                            "gpt-6.1-terra",
                                            131072,
                                        )
                                    },
                                )
                            }
                        if (mode != MotionMode.Off) f.connect(DeviceId("handy"))
                        f.send(
                            UserIntent.SendMessage(
                                fixture
                                    .getValue("input")
                                    .jsonArray
                                    .last()
                                    .jsonObject
                                    .getValue("content")
                                    .jsonPrimitive
                                    .content
                            )
                        )
                        assertEquals(1, requests)
                        assertEquals(
                            "Ready.",
                            (f.core.state.value.chat.entries.last() as ChatEntry.Message).text,
                        )
                        f.scope.cancel()
                    }
                }
            }
}
