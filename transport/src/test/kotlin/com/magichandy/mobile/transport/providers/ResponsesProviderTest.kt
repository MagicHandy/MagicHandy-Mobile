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
import kotlinx.serialization.json.jsonObject

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
