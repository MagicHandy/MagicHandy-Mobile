package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.api.AppLanguage
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.fixtures.fixtureJson
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.score.LayeredScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

internal class PromptAdmissionTest {
    @TestFactory
    fun `local Layered guidance and complete admitted turns match the desktop provider`() =
        fixtureJson("manifest.json")
            .jsonObject
            .getValue("cases")
            .jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("role").jsonPrimitive.content == "local-history" }
            .map { case ->
                DynamicTest.dynamicTest(case.getValue("case_id").jsonPrimitive.content) {
                    compareCapture(
                        fixtureJson(case.getValue("path").jsonPrimitive.content).jsonObject
                    )
                }
            }

    private fun compareCapture(fixture: JsonObject) {
        fun field(key: String) = fixture.getValue(key).jsonPrimitive
        val context = field("context_tokens").int
        val window =
            if (field("managed").boolean)
                ConversationWindow.managed(CapabilityLevel.Simple, MotionMode.Layered, context)
            else ConversationWindow.named(CapabilityLevel.Simple, context)
        assertEquals(field("row_limit").int, window.messages)
        val prior = syntheticDialogue(field("input_rows").int, field("repetitions").int)
        val history = window.recent(prior.asReversed().asSequence())
        val latest = TextMessage(TextRole.User, field("message").content)
        val requests =
            (47..54).map { index ->
                buildJsonObject {
                    put("said", prior[index * 2].text)
                    put("seconds_ago", 60 - index)
                }
            }
        val c =
            TurnContext(
                MotionMode.Layered,
                CapabilityLevel.Simple,
                AppLanguage.English,
                false,
                true,
                false,
                LayeredScore(speed = 45),
                20,
                80,
                recentRequests = JsonArray(requests),
                local = true,
            )
        val request =
            assertNotNull(
                ChatContract.compose(c, history + latest, context, prior.size - history.size)
            )
        val wire = request.messages
        assertEquals(field("schema_bytes").int, request.schema.toString().utf8Length().toInt())
        assertEquals(field("contract").content, wire.first().text)
        val expected = fixture.getValue("messages").jsonArray.map { capturedMessage(it.jsonObject) }
        val measured = fixture.getValue("budget").jsonObject
        assertEquals(expected, wire.drop(1))
        assertEquals(
            measured.getValue("history_messages_dropped").jsonPrimitive.int,
            prior.size - wire.dropLast(1).count { it.role != TextRole.Developer },
        )
        assertEquals(
            measured.getValue("input_bytes").jsonPrimitive.int,
            wire.sumOf { it.text.encodeToByteArray().size + 64 },
        )
    }

    @Test
    fun `provider admission preserves a quoted opening within the same byte limit as the latest turn`() {
        val instruction = TextMessage(TextRole.Developer, "Use quoted history as evidence.")
        val opening =
            TextMessage(
                TextRole.User,
                "Opening: 25, 35, then five faster.",
                isOpeningReference = true,
            )
        val middle = List(12) { TextMessage(TextRole.Assistant, "middle".repeat(90)) }
        val latest = TextMessage(TextRole.User, "Which paces did I ask for first?")
        val messages = listOf(instruction, opening) + middle + latest
        val budget = PromptBudget(3400)
        val retained = assertNotNull(budget.admit(messages))
        assertEquals(listOf(instruction, opening, middle.last(), latest), retained.messages)
        assertEquals(11, retained.historyMessagesDropped)
        val largeOpening = opening.copy(text = "x".repeat(2000))
        assertEquals(
            listOf(instruction, middle.last(), latest),
            assertNotNull(budget.admit(listOf(instruction, largeOpening) + middle + latest))
                .messages,
        )
    }

    private fun syntheticDialogue(rows: Int, repetitions: Int) = buildList {
        val note = "Archived display note. ".repeat(repetitions).trimEnd()
        repeat(rows / 2) { index ->
            val id = index.toString().padStart(2, '0')
            add(TextMessage(TextRole.User, "Synthetic note $id. $note"))
            add(TextMessage(TextRole.Assistant, "Noted."))
        }
    }

    private fun capturedMessage(message: JsonObject): TextMessage {
        val role =
            when (message.getValue("role").jsonPrimitive.content) {
                "user" -> TextRole.User
                "assistant" -> TextRole.Assistant
                "system" -> TextRole.Developer
                else -> error("Unexpected reference role")
            }
        return TextMessage(role, message.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `repair keeps all three essential messages and interleaved developer instructions`() {
        val developer = TextMessage(TextRole.Developer, "Rules.")
        val repair =
            listOf(
                TextMessage(TextRole.User, "あ".repeat(100)),
                TextMessage(TextRole.Assistant, "{"),
                TextMessage(TextRole.User, "Repair."),
            )
        val messages = listOf(TextMessage(TextRole.User, "x".repeat(900)), developer) + repair
        val budget = PromptBudget(3000)
        val result = assertNotNull(budget.admit(messages, 12, 3))
        assertEquals(listOf(developer) + repair, result.messages)
        assertEquals(13, result.historyMessagesDropped)
        assertNull(PromptBudget(2800).admit(messages, 12, 3))
    }

    @Test
    fun `Advanced long-history instructions depend on the dialogue that actually fits`() {
        val prior = List(80) { TextMessage(TextRole.Assistant, "x".repeat(1024)) }
        val current = TextMessage(TextRole.User, "Read it back.")
        val base = assertNotNull(PromptBudget(16384).admit(prior + current))
        val guide = LayeredHistoryGuide(CapabilityLevel.Advanced, true, 5, 95, 45, 10, 80)
        assertSame(base.messages, guide.addTo(base))
        val large = assertNotNull(PromptBudget(131072).admit(prior + current))
        assertEquals(TextRole.Developer, guide.addTo(large).dropLast(1).last().role)
    }

    @Test
    fun `a correction at the end of long assistant speech is preserved as a whole message`() {
        val spoken = TextMessage(TextRole.Assistant, "あ".repeat(1500) + " Correction: 26.")
        val window = ConversationWindow.named(CapabilityLevel.Simple)
        assertEquals(listOf(spoken), window.recent(sequenceOf(spoken)))
        assertEquals(
            emptyList(),
            window.recent(sequenceOf(spoken.copy(text = "あ".repeat(8001)), spoken)),
        )
    }

    @Test
    fun `UTF8 admission matches the encoder at every code point width and for malformed surrogate pairs`() {
        for (text in listOf("", "ASCII", "é日本", "明日🌃", "\uD800", "\uDC00", "\uD800\uD800\uDC00")) {
            assertEquals(text.encodeToByteArray().size.toLong(), text.utf8Length())
        }
    }

    @Test
    fun `named and managed windows keep separate mode and capability floors`() {
        val advanced =
            ConversationWindow.managed(CapabilityLevel.Advanced, MotionMode.Layered, 131072)
        assertEquals(256, advanced.messages)
        assertEquals(96000, advanced.historyBytes)
        assertEquals(
            64,
            ConversationWindow.managed(CapabilityLevel.Intermediate, MotionMode.Off, 32768)
                .messages,
        )
        assertEquals(
            96,
            ConversationWindow.managed(CapabilityLevel.Intermediate, MotionMode.Layered, 32768)
                .messages,
        )
        assertEquals(65536, ConversationWindow.named(CapabilityLevel.Advanced, 16384).contextTokens)
        assertEquals(512, ConversationWindow.named(CapabilityLevel.Advanced, 262144).messages)
    }

    @Test
    fun `the history window stops reading storage once its row allowance is consumed`() {
        val single = TextMessage(TextRole.User, "ok")
        val window = ConversationWindow.named(CapabilityLevel.Advanced)
        val stored = sequence {
            repeat(64) { yield(single) }
            error("Read past window")
        }
        assertEquals(64, window.recent(stored).size)
    }
}
