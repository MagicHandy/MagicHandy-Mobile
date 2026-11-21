package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.AppLanguage
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ReactionStyle
import com.magichandy.mobile.core.api.ReplyLength
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.ModelCatalog
import com.magichandy.mobile.core.ports.Preferences
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.SettingsDocument
import com.magichandy.mobile.core.testing.InMemoryModels
import com.magichandy.mobile.core.testing.InMemorySettings
import com.magichandy.mobile.transport.simulated.DebugFixture
import kotlin.test.assertEquals
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
                DynamicTest.dynamicTest(name) { runTest { verifyTurn(load(name), mode, language) } }
            }

    @TestFactory
    fun `public persona turns carry every captured Responses field in five languages`() =
        mapOf(
                "en" to AppLanguage.English,
                "es" to AppLanguage.Spanish,
                "pt-BR" to AppLanguage.PortugueseBrazil,
                "ja" to AppLanguage.Japanese,
                "zh-Hans" to AppLanguage.ChineseSimplified,
            )
            .flatMap { (tag, language) ->
                listOf(
                        "utility-neutral",
                        "warm-playful",
                        "intimate-tender-detailed",
                        "explicit-neutral",
                    )
                    .map { axes ->
                        val name = "persona-$tag-hosted-$axes"
                        DynamicTest.dynamicTest(name) {
                            runTest {
                                val capture = load("personas/$name")
                                val data = capture.getValue("persona").jsonObject
                                fun value(key: String) = data.getValue(key).jsonPrimitive.content
                                val p =
                                    Personality(
                                        Explicitness.entries.single {
                                            it.name.equals(value("voice"), true)
                                        },
                                        ReactionStyle.entries.single {
                                            it.name.equals(value("style"), true)
                                        },
                                        value("description"),
                                        if (value("detailed") == "true") ReplyLength.Detailed
                                        else ReplyLength.Short,
                                    )
                                val builtin = value("name").isEmpty()
                                val persona =
                                    PersonaRef(
                                        PersonaId("fixture"),
                                        value("name").ifEmpty { "MagicHandy" },
                                        "",
                                        p,
                                        builtin,
                                    )
                                val body = capture.getValue("body").jsonObject
                                val raw = body.getValue("input").jsonArray
                                val expected =
                                    if (p.explicitness == Explicitness.Utility) body
                                    else {
                                        val first = raw.first().jsonObject
                                        val text =
                                            load("persona-voice-deltas")
                                                .getValue(tag)
                                                .jsonObject
                                                .entries
                                                .fold(
                                                    first.getValue("content").jsonPrimitive.content
                                                ) { t, (before, after) ->
                                                    t.replace(before, after.jsonPrimitive.content)
                                                }
                                        JsonObject(
                                            body +
                                                ("input" to
                                                    JsonArray(
                                                        listOf(
                                                            JsonObject(
                                                                first +
                                                                    ("content" to
                                                                        JsonPrimitive(text))
                                                            )
                                                        ) + raw.drop(1)
                                                    ))
                                        )
                                    }
                                verifyTurn(expected, MotionMode.Off, language, persona)
                            }
                        }
                    }
            }

    private fun load(path: String): JsonObject =
        requireNotNull(javaClass.getResourceAsStream("/$path.json")).bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }

    private suspend fun TestScope.verifyTurn(
        fixture: JsonObject,
        mode: MotionMode,
        language: AppLanguage,
        persona: PersonaRef? = null,
    ) {
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
                                mapOf(id to SavedModel(model, contextTokens = 131072)),
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
        if (persona != null) {
            val result =
                f.core.submit(
                    UserIntent.SavePersona(
                        if (persona.isBuiltIn) f.core.state.value.settings.persona.id else null,
                        persona.name,
                        persona.personality,
                        use = true,
                    )
                )
            assertEquals(FormResult.Accepted, result)
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
