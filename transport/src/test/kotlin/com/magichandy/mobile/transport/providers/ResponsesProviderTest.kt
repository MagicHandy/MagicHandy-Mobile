package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.api.ProviderReplyFailure.Reason
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.ModelRequest
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
import okhttp3.ResponseBody.Companion.toResponseBody

internal class ResponsesProviderTest {
    private val messages = listOf(TextMessage(TextRole.User, "Hello"))
    private val delta = """data: {"type":"response.output_text.delta","delta":"Hello"}""" + "\n\n"

    @Test
    fun `a complete event-framed structured reply is accepted without usable media metadata`() =
        runTest {
            val raw = """{"reply":"Hello"}"""
            val events =
                "event: response.created\r\ndata: {\"type\":\"response.created\"}\r\n\r\n" +
                    "event: response.output_text.delta\r\n" +
                    """data: {"type":"response.output_text.delta","delta":"{\"reply\":\"Hello\"}"}""" +
                    "\r\n\r\nevent: response.completed\r\n" +
                    """data: {"type":"response.completed","response":{"status":"completed"}}""" +
                    "\r\n\r\n"
            val schema =
                Json.parseToJsonElement(
                        """{"type":"object","properties":{"reply":{"type":"string"}}}"""
                    )
                    .jsonObject
            for (header in listOf(null, "text/plain", "application/octet-stream")) {
                val http = MemoryHttp { request ->
                    if (header == null)
                        request.reply("").newBuilder().body(events.toResponseBody()).build()
                    else request.reply(events, header)
                }
                val pieces = mutableListOf<String>()
                val result =
                    ResponsesProvider(http, { Secret("synthetic") })
                        .complete(ModelRequest(messages, schema), pieces::add)
                assertEquals(TextResult.Completed(raw), result)
                assertEquals(listOf(raw), pieces)
            }
        }

    @Test
    fun `a text-only turn streams previews and commits only after terminal completion`() = runTest {
        val end =
            """data: {"type":"response.completed","response":{"status":"completed"}}""" + "\n\n"
        val http = MemoryHttp { req ->
            assertEquals("/v1/responses", req.url.encodedPath)
            assertEquals("Bearer synthetic", req.header("Authorization"))
            assertTrue(requireNotNull(req.body).isOneShot())
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
    fun `unlabelled data must be a complete Responses stream before it can commit`() = runTest {
        val terminal =
            """data: {"type":"response.completed","response":{"status":"completed"}}""" + "\n\n"
        for ((body, expected) in
            listOf(
                "\uFEFF\n: connected\n\n" + delta + terminal to TextResult.Completed("Hello"),
                delta to TextResult.Interrupted,
                "<html>\n\n" + delta + terminal to
                    TextResult.Failed(ProviderReplyFailure(Reason.UnexpectedResponse)),
                """{"reply":"Hello"}""" to
                    TextResult.Failed(ProviderReplyFailure(Reason.UnexpectedResponse)),
            )) {
            val http = MemoryHttp { req ->
                req.reply("").newBuilder().body(body.toResponseBody()).build()
            }
            assertEquals(expected, ResponsesProvider(http, { Secret("test") }).stream(messages) {})
        }
    }

    @Test
    fun `HTTP and terminal provider failures retain a safe recovery category`() = runTest {
        for ((status, code, reason) in
            listOf(
                Triple(401, "invalid_api_key", Reason.SignInRequired),
                Triple(403, "subscription_sharing_user_not_eligible", Reason.PermissionDenied),
                Triple(404, "model_not_found", Reason.ModelUnavailable),
                Triple(429, "subscription_sharing_usage_limit_exceeded", Reason.UsageLimit),
                Triple(429, "rate_limit_exceeded", Reason.RateLimited),
                Triple(503, "unavailable", Reason.ServiceUnavailable),
            )) {
            val http = MemoryHttp { req ->
                req.reply("""{"error":{"code":"$code","message":"private upstream text"}}""")
                    .newBuilder()
                    .code(status)
                    .build()
            }
            var invalidations = 0
            val tokens =
                object : AccessTokenSource {
                    override suspend fun token() = Secret("fixture")

                    override suspend fun rejected(token: Secret) {
                        invalidations++
                    }
                }
            assertEquals(
                TextResult.Failed(ProviderReplyFailure(reason, status)),
                ResponsesProvider(http, tokens).stream(messages) {},
            )
            assertEquals(if (status == 401) 1 else 0, invalidations)
        }
        val failure =
            """data: {"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}""" +
                "\n\n"
        val http = MemoryHttp { req -> req.reply(delta + failure, "text/plain") }
        assertEquals(
            TextResult.Failed(ProviderReplyFailure(Reason.UsageLimit)),
            ResponsesProvider(http, { Secret("fixture") }).stream(messages) {},
        )
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
        assertEquals(
            TextResult.Failed(ProviderReplyFailure(ProviderReplyFailure.Reason.ContextLimit)),
            provider.stream(listOf(contract, old)) {},
        )
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
