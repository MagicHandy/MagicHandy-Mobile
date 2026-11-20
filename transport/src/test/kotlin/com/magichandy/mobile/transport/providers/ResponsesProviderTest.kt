package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class ResponsesProviderTest {
    private val messages = listOf(TextMessage(TextRole.User, "Hello"))
    private val delta = """data: {"type":"response.output_text.delta","delta":"Hello"}""" + "\n\n"

    @Test
    fun `a text-only turn streams previews and commits only after terminal completion`() = runTest {
        val end =
            """data: {"type":"response.completed","response":{"status":"completed"}}""" + "\n\n"
        val http = MemoryHttp { req ->
            assertEquals("/v1/responses", req.url.encodedPath)
            assertEquals("Bearer synthetic", req.header("Authorization"))
            val json = Json.parseToJsonElement(req.payload()).jsonObject
            assertEquals(setOf("model", "input", "stream", "store", "reasoning"), json.keys)
            req.reply(delta + end, "text/event-stream")
        }
        val pieces = mutableListOf<String>()
        val result = ResponsesProvider(http, { Secret("synthetic") }).stream(messages, pieces::add)
        assertEquals(listOf("Hello"), pieces)
        assertEquals(TextResult.Completed("Hello"), result)
    }

    @Test
    fun `a long conversation sheds only older dialogue before asking for a token`() = runTest {
        val old = TextMessage(TextRole.User, "x".repeat(65536))
        val latest = TextMessage(TextRole.User, "Read the correction back.")
        val contract = TextMessage(TextRole.Developer, "Speak only.")
        val correction = TextMessage(TextRole.Assistant, "The correction is 26.")
        val http = MemoryHttp { req ->
            val input =
                Json.parseToJsonElement(req.payload()).jsonObject.getValue("input").jsonArray
            assertEquals(
                listOf(contract, correction, latest).map { it.text },
                input.map { it.jsonObject.getValue("content").jsonPrimitive.content },
            )
            req.reply(
                delta +
                    """data: {"type":"response.completed","response":{"status":"completed"}}""" +
                    "\n\n",
                "text/event-stream",
            )
        }
        var tokens = 0
        val provider =
            ResponsesProvider(
                http,
                {
                    tokens++
                    Secret("synthetic")
                },
            )
        assertEquals(
            TextResult.Completed("Hello"),
            provider.stream(listOf(contract, old, correction, latest)) {},
        )
        assertEquals(TextResult.Interrupted, provider.stream(listOf(contract, old)) {})
        assertEquals(1, tokens)
    }

    @Test
    fun `EOF refusal and incomplete events discard preceding text`() = runTest {
        for ((tail, expected) in
            listOf(
                "" to TextResult.Interrupted,
                """{"type":"response.incomplete"}""" to TextResult.Interrupted,
                """{"type":"response.refusal.done"}""" to TextResult.Refused,
                """{"type":"response.completed","response":{"status":"completed","output":[{"content":[{"type":"refusal"}]}]}}""" to
                    TextResult.Refused,
            )) {
            val stream = delta + if (tail.isEmpty()) "" else "data: " + tail + "\n\n"
            val http = MemoryHttp { it.reply(stream, "text/event-stream") }
            assertEquals(
                expected,
                ResponsesProvider(http, { Secret("synthetic") }).stream(messages) {},
            )
        }
    }

    @Test
    fun `cancelling a stalled Responses call cancels its HTTP exchange`() = runTest {
        val http = MemoryHttp { null }
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                ResponsesProvider(http, { Secret("synthetic") }).stream(messages) {}
            }
        job.cancelAndJoin()
        assertTrue(http.cancelled)
    }
}
