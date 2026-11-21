package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.api.AppLanguage
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.MotionMode
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.ports.current
import com.magichandy.mobile.core.score.LayeredScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

internal class StructuredTurnTest {
    private fun context(
        mode: MotionMode = MotionMode.Layered,
        language: AppLanguage = AppLanguage.English,
    ) = TurnContext(mode, CapabilityLevel.Advanced, language, true, false, false, null, 10, 43)

    private fun request(c: TurnContext, text: String = "Synthetic request.") =
        requireNotNull(ChatContract.compose(c, listOf(TextMessage(TextRole.User, text)), 131072))

    @TestFactory
    fun `the native composer reproduces the developer prompt and strict response schema`() =
        listOf(
                Triple("terra-layered-en", MotionMode.Layered, AppLanguage.English),
                Triple("terra-creative_v2-es", MotionMode.Simple, AppLanguage.Spanish),
                Triple("terra-off-ja", MotionMode.Off, AppLanguage.Japanese),
            )
            .map { (file, mode, locale) ->
                DynamicTest.dynamicTest(file) {
                    val source =
                        requireNotNull(javaClass.getResourceAsStream("/chat/$file.json"))
                            .bufferedReader()
                            .use { Json.parseToJsonElement(it.readText()).jsonObject }
                    val messages = source.getValue("input").jsonArray
                    val req =
                        request(
                            context(mode, locale),
                            messages.last().jsonObject.getValue("content").jsonPrimitive.content,
                        )
                    assertEquals(
                        messages.first().jsonObject.getValue("content").jsonPrimitive.content,
                        req.messages.first().text,
                    )
                    assertEquals(
                        source
                            .getValue("text")
                            .jsonObject
                            .getValue("format")
                            .jsonObject
                            .getValue("schema"),
                        req.schema,
                    )
                    assertEquals(2, req.messages.size)
                }
            }

    private class Peer(private val answers: List<TextResult>) : StructuredProvider {
        val requests = mutableListOf<ModelRequest>()

        override suspend fun complete(
            request: ModelRequest,
            preview: (String) -> Unit,
        ): TextResult {
            requests += request
            return answers[requests.lastIndex].also {
                if (it is TextResult.Completed) it.text.chunked(3).forEach(preview)
            }
        }

        override suspend fun stream(
            messages: List<TextMessage>,
            preview: (String) -> Unit,
        ): TextResult = error("Wrong API")
    }

    @Test
    fun `only an actual playing Layered score authorizes a compact speed edit`() {
        val raw =
            """{"proposal":{"action":"update","edits":{"composition":"speed_only","speed_percent":{"op":"adjust","value":4}},"reply":"Adjusted."}}"""
        val current = LayeredScore()
        val c = context().copy(running = true, current = current)
        val reply = validateProposal(raw, c, request(c))
        assertEquals(current.copy(speed = 29), reply.score)
        for (other in
            listOf(
                c.copy(current = null),
                c.copy(paused = true),
                c.copy(running = false),
                c.copy(maximum = 26),
            )) assertFails { validateProposal(raw, other, request(other)) }
    }

    @Test
    fun `repair keeps the same schema original user and exactly one complete replacement`() =
        runTest {
            val c = context(MotionMode.Off)
            val req = request(c)
            val bad = TextResult.Completed("""{"reply":"Bad","reply":"Duplicate"}""")
            val peer = Peer(listOf(bad, TextResult.Completed("""{"reply":"Repaired."}""")))
            val answer = structuredTurn(peer, req, c, { true })
            assertEquals("Repaired.", assertIs<TurnAnswer.Valid>(answer).proposal.reply)
            assertSame(req.schema, peer.requests.last().schema)
            assertEquals(req.messages, peer.requests.last().messages.dropLast(2))
            assertEquals(3, peer.requests.last().preserveTail)
            val twice = Peer(listOf(bad, bad))
            assertEquals(TurnAnswer.Failed, structuredTurn(twice, req, c, { true }))
            assertEquals(2, twice.requests.size)
        }

    @Test
    fun `an obsolete generation refusal or prose cannot take the repair path`() = runTest {
        val c = context()
        val req = request(c)
        for (outcome in
            listOf(
                TextResult.Refused,
                TextResult.Interrupted,
                TextResult.Completed("plain prose"),
            )) {
            val peer = Peer(listOf(outcome))
            assertFalse(structuredTurn(peer, req, c, { true }) is TurnAnswer.Valid)
            assertEquals(1, peer.requests.size)
        }
        var checks = 0
        val peer =
            Peer(
                listOf(
                    TextResult.Completed(
                        """{"proposal":{"action":"stop","edits":{},"reply":"Stopped."}}"""
                    )
                )
            )
        assertEquals(TurnAnswer.Failed, structuredTurn(peer, req, c, { ++checks == 1 }))
    }

    @Test
    fun `only explicit memories are extracted and preview never exposes private planning fields`() {
        assertEquals(listOf("short sessions"), requestedMemory("Remember that short sessions"))
        assertTrue(requestedMemory("hello").isEmpty())
        val reader = ReplyPreview()
        val preview =
            """{"basis":"private","reply":"Three words here."}""".chunked(2).joinToString("") {
                reader.consume(it)
            }
        assertEquals("Three words here.", preview)
    }
}
