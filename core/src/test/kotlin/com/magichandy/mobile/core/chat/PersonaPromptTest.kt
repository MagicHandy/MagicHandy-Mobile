package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.api.AppLanguage
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.api.PersonaId
import com.magichandy.mobile.core.api.PersonaRef
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.ReactionStyle
import com.magichandy.mobile.core.api.ReplyLength
import com.magichandy.mobile.core.fixtures.fixtureJson
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.ports.builtInPersona
import com.magichandy.mobile.core.score.LayeredScore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

internal class PersonaPromptTest {
    private val locales =
        mapOf(
            "en" to AppLanguage.English,
            "es" to AppLanguage.Spanish,
            "pt-BR" to AppLanguage.PortugueseBrazil,
            "ja" to AppLanguage.Japanese,
            "zh-Hans" to AppLanguage.ChineseSimplified,
        )
    private val deltas = fixtureJson("chat/persona-voice-deltas.json").jsonObject
    private val user = TextMessage(TextRole.User, "A synthetic turn.")

    @TestFactory
    fun `entire messages and schemas follow independent desktop persona captures`() =
        fixtureJson("manifest.json")
            .jsonObject
            .getValue("cases")
            .jsonArray
            .map { it.jsonObject }
            .filter { it.string("role") == "persona-request" }
            .map { row ->
                DynamicTest.dynamicTest(row.string("case_id")) {
                    compare(fixtureJson(row.string("path")).jsonObject)
                }
            }

    private fun compare(f: JsonObject) {
        val data = f.getValue("persona").jsonObject
        val kind = data.string("kind")
        val level =
            when (kind) {
                "simple" -> CapabilityLevel.Simple
                "intermediate" -> CapabilityLevel.Intermediate
                else -> CapabilityLevel.Advanced
            }
        val speech = kind == "talk" || kind == "speech"
        val name = data.string("name")
        val p =
            Personality(
                Explicitness.entries.single { it.name.equals(data.string("voice"), true) },
                ReactionStyle.entries.single { it.name.equals(data.string("style"), true) },
                data.string("description"),
                if (data.getValue("detailed").jsonPrimitive.boolean) ReplyLength.Detailed
                else ReplyLength.Short,
            )
        val persona =
            if (name.isEmpty()) builtInPersona.copy(personality = p)
            else PersonaRef(PersonaId("fixture"), name, "", p)
        val c =
            TurnContext(
                if (kind == "hosted") MotionMode.Off else MotionMode.Layered,
                level,
                locales.getValue(f.string("locale")),
                true,
                speech,
                false,
                if (speech) LayeredScore(speed = 25) else null,
                10,
                43,
                local = level != CapabilityLevel.Advanced,
                persona = persona,
            )
        val request =
            assertNotNull(
                ChatContract.compose(c, listOf(user), 131072, purpose = kind.takeIf { speech })
            )
        val body = f["body"]?.jsonObject
        val expected =
            (body?.get("input") ?: f.getValue("messages")).jsonArray.map { it.jsonObject }
        assertEquals(expected.size, request.messages.size)
        expected.zip(request.messages).forEachIndexed { index, (reference, message) ->
            val role = reference.string("role")
            assertEquals(
                if (role == "system" || role == "developer") TextRole.Developer else TextRole.User,
                message.role,
            )
            val raw = reference.string("content")
            val text =
                if (index == 0 && p.explicitness != Explicitness.Utility)
                    deltas.getValue(f.string("locale")).jsonObject.entries.fold(raw) {
                        s,
                        (before, after) ->
                        s.replace(before, after.jsonPrimitive.content)
                    }
                else raw
            assertContentEquals(
                text.encodeToByteArray(),
                message.text.encodeToByteArray(),
                "message $index",
            )
        }
        val schema =
            body?.get("text")?.jsonObject?.get("format")?.jsonObject?.get("schema")
                ?: f.getValue("schema")
        assertEquals(schema, request.schema)
        assertEquals(f.getValue("output_tokens").jsonPrimitive.int, request.outputAllowance)
        assertEquals(
            request.schema,
            ChatContract.compose(
                    c.copy(persona = builtInPersona),
                    listOf(user),
                    131072,
                    purpose = kind.takeIf { speech },
                )
                ?.schema,
        )
    }

    @Test
    fun `new wording cannot reference the motor or protocol and all six styles are prose only`() {
        val bank =
            requireNotNull(javaClass.getResourceAsStream("/chat/personas.json"))
                .bufferedReader()
                .use {
                    kotlinx.serialization.json.Json.parseToJsonElement(it.readText()).jsonObject
                }
        val controls =
            listOf(
                "motion",
                "speed",
                "device",
                "json",
                "actuator",
                "movimiento",
                "movimento",
                "dispositivo",
                "velocidad",
                "velocidade",
                "运动",
                "设备",
                "速度",
                "デバイス",
                "モーション",
                "速さ",
            )
        val styleTerms =
            controls +
                listOf(
                    "pattern",
                    "stroke",
                    "tip",
                    "shaft",
                    "base",
                    "start",
                    "stop",
                    "target",
                    "intensity",
                    "area",
                    "faster",
                    "slower",
                    "深",
                )
        for ((_, locale) in bank) {
            // Utility is the pre-existing immutable capture, including its stock assistant
            // identity.
            locale.jsonObject.getValue("voices").jsonObject.values.forEach { voice ->
                controls.forEach {
                    assertFalse(voice.toString().contains(it, ignoreCase = true), it)
                }
            }
            locale.jsonObject.getValue("styles").jsonObject.values.forEach { style ->
                styleTerms.forEach {
                    assertFalse(style.jsonPrimitive.content.contains(it, ignoreCase = true), it)
                }
            }
        }
    }

    @Test
    fun `utility omits profile and style and a pure planning request ignores every personality field`() {
        val c =
            TurnContext(
                MotionMode.Layered,
                CapabilityLevel.Advanced,
                AppLanguage.English,
                true,
                true,
                false,
                LayeredScore(speed = 25),
                10,
                43,
            )
        fun request(context: TurnContext, purpose: String?) =
            ChatContract.compose(context, listOf(user), 131072, purpose = purpose)
        val custom =
            PersonaRef(
                PersonaId("id"),
                "Unique name",
                "",
                Personality(style = ReactionStyle.Dominant, description = "Unique description"),
            )
        assertEquals(request(c, null), request(c.copy(persona = custom), null))
        val detailed =
            custom.copy(
                personality =
                    custom.personality.copy(
                        explicitness = Explicitness.Explicit,
                        replyLength = ReplyLength.Detailed,
                    )
            )
        assertEquals(request(c, "plan"), request(c.copy(persona = detailed), "plan"))
        val off = c.copy(mode = MotionMode.Off, persona = detailed)
        val req = assertNotNull(request(off, null))
        assertFalse(req.schema.toString().contains("speed_percent"))
        assertTrue(req.messages.first().text.contains("Unique description"))
        assertNull(
            runCatching {
                validateProposal("""{"action":"start","reply":"Yes.","edits":{}}""", off, req)
            }
                .getOrNull()
        )
    }

    @Test
    fun `quoted fields keep all 500 graphemes and cannot expand each other or insert sections`() {
        val value = "e\u0301".repeat(500)
        val persona =
            PersonaRef(
                PersonaId("id"),
                "MobilePersonaDescription",
                "",
                Personality(Explicitness.Intimate, description = value),
            )
        val c =
            TurnContext(
                MotionMode.Off,
                CapabilityLevel.Advanced,
                AppLanguage.English,
                true,
                false,
                false,
                null,
                10,
                43,
                persona = persona,
            )
        val text =
            requireNotNull(ChatContract.compose(c, listOf(user), 131072)).messages.first().text
        assertTrue(text.contains("\"MobilePersonaDescription\""))
        assertTrue(text.contains("\"" + value + "\""))
        val escaped =
            persona.copy(
                name = "Kit\u00a0\t\n\"Example\"",
                personality = persona.personality.copy(description = ""),
            )
        val body =
            requireNotNull(ChatContract.compose(c.copy(persona = escaped), listOf(user), 131072))
                .messages
                .first()
                .text
        assertTrue(body.contains("""Kit \"Example\""""))
        assertFalse(body.contains("Persona description (quoted"))
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
