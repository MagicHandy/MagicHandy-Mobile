package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.api.ReasoningEffort
import com.magichandy.mobile.core.api.ReasoningEffort.High
import com.magichandy.mobile.core.api.ReasoningEffort.Low
import com.magichandy.mobile.core.api.ReasoningEffort.Medium
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer

/** What OpenRouter is asked for, by model and saved reasoning (docs/openrouter-design.md §4.3). */
internal class OpenRouterReasoningTest {
    private val origin = "https://fixture.invalid/".toHttpUrl()
    private val messages = listOf(TextMessage(TextRole.User, "Fixture"))

    @Test
    fun `each model gets the reasoning it can take, with room for it`() = runTest {
        var body = send(LEVELS, isOn = false, level = null)
        assertEquals("""{"effort":"none"}""", body.reasoning)
        assertEquals(REPLY, body.tokens)
        body = send(LEVELS, isOn = true, level = Low)
        assertEquals("""{"effort":"low","exclude":true}""", body.reasoning)
        assertEquals(REPLY + 6144, body.tokens)
        // Medium isn't listed: the lower of the two nearest levels replies sooner.
        assertEquals(
            """{"effort":"low","exclude":true}""",
            send(LEVELS, isOn = true, level = Medium).reasoning,
        )
        // Saved before levels existed, the model chooses.
        body = send(LEVELS, isOn = true, level = null)
        assertEquals("""{"enabled":true,"exclude":true}""", body.reasoning)
        assertEquals(REPLY + 8192, body.tokens)
        // A model that always reasons can't be turned off, so it gets its lowest level.
        body = send(REQUIRED, isOn = false, level = null)
        assertEquals("""{"effort":"low","exclude":true}""", body.reasoning)
        assertEquals(REPLY + 6144, body.tokens)
        assertEquals("""{"enabled":false}""", send(ON_OFF, isOn = false, level = High).reasoning)
        body = send(NO_REASONING, isOn = true, level = High)
        assertNull(body.reasoning)
        assertEquals(REPLY, body.tokens)
    }

    @Test
    fun `every request asks for the fastest private host and may fall back to another`() = runTest {
        val provider = send(NO_REASONING, isOn = false, level = null)["provider"]?.jsonObject
        assertEquals("throughput", provider?.get("sort")?.jsonPrimitive?.content)
        assertEquals("true", provider?.get("zdr")?.jsonPrimitive?.content)
        assertEquals("deny", provider?.get("data_collection")?.jsonPrimitive?.content)
        assertEquals("true", provider?.get("require_parameters")?.jsonPrimitive?.content)
        assertEquals("true", provider?.get("allow_fallbacks")?.jsonPrimitive?.content)
    }

    @Test
    fun `the request stays within the model's own limits`() = runTest {
        val small =
            """{"id":"sample","supported_parameters":["max_tokens","reasoning"],""" +
                """"context_length":16384,"top_provider":{"max_completion_tokens":4000},""" +
                """"reasoning":{"mandatory":false,"supported_efforts":["high","low"]}}"""
        // A quarter of the model's 16K context is 4,096 tokens, and its replies stop at 4,000.
        assertEquals(4000, send(small, isOn = true, level = High).tokens)
        assertEquals(REPLY, send(small, isOn = false, level = null).tokens)
    }

    @Test
    fun `an error sent partway through a reply names the service, not a broken stream`() {
        val text = """data: {"choices":[{"index":0,"delta":{"content":"hel"}}]}""" + "\n\n"
        val failures =
            listOf(
                """data: {"error":{"code":502,"message":"upstream"},"choices":[]}""",
                """data: {"choices":[{"index":0,"error":{"code":502},"delta":{}}]}""",
                """data: {"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}""",
            )
        for (failure in failures) {
            val result = compatibleStream(Buffer().writeUtf8(text + failure + "\n\n")) {}
            assertEquals(
                ProviderReplyFailure.Reason.ServiceUnavailable,
                (result as TextResult.Failed).error.reason,
            )
        }
    }

    @Test
    fun `room for reasoning follows the level and never takes more than a quarter of the context`() {
        assertEquals(2048, reasoningReserve(ReasoningEffort.Minimal, 65536))
        assertEquals(6144, reasoningReserve(Low, 65536))
        for (level in listOf(Medium, High, ReasoningEffort.ExtraHigh, ReasoningEffort.Max, null)) {
            assertEquals(8192, reasoningReserve(level, 65536))
        }
        assertEquals(4096, reasoningReserve(High, 16384))
    }

    @Test
    fun `a model with a small completion cap retains input that fits beside its actual output`() =
        runTest {
            val small =
                """{"id":"sample","supported_parameters":["max_tokens","reasoning"],
            "context_length":8192,"top_provider":{"max_completion_tokens":512},
            "reasoning":{"mandatory":true,"supported_efforts":["high"]}}"""
            val prompt = TextMessage(TextRole.User, "x".repeat(5000))
            val body = send(small, true, High, listOf(prompt))
            assertEquals(512, body.tokens)
        }

    @Test
    fun `a failure carried by a successful event stream preserves the recovery action without an HTTP status`() {
        for ((code, reason) in
            listOf(
                401 to ProviderReplyFailure.Reason.CredentialsRejected,
                402 to ProviderReplyFailure.Reason.BillingRequired,
                429 to ProviderReplyFailure.Reason.RateLimited,
            )) {
            val error = """{"code":$code,"message":"private upstream detail"}"""
            for (event in
                listOf("""{"error":$error}""", """{"choices":[{"index":0,"error":$error}]}""")) {
                val result = compatibleStream(Buffer().writeUtf8("data: $event\n\n")) {}
                assertEquals(TextResult.Failed(ProviderReplyFailure(reason)), result)
            }
        }
    }

    @Test
    fun `a saved catalog model is looked up by slug and metadata cannot add unsupported reasoning controls`() =
        runTest {
            val paths = mutableListOf<String>()
            val http = MemoryHttp { req ->
                paths += req.url.encodedPath
                if (req.url.encodedPath == "/model/vendor/rare")
                    req.reply(
                        """{"data":{"id":"vendor/rare","supported_parameters":["structured_outputs","max_tokens"],"reasoning":{"mandatory":false}}}"""
                    )
                else if (req.url.encodedPath == "/chat/completions") {
                    val body = Json.parseToJsonElement(req.payload()).jsonObject
                    assertNull(body["reasoning"])
                    req.reply(
                        "data: " +
                            """{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""" +
                            "\n\ndata: [DONE]\n\n"
                    )
                } else req.reply("""{"data":[]}""")
            }
            val provider =
                CompatibleProvider(
                    http,
                    origin,
                    "vendor/rare",
                    AccessTokenSource { Secret("synthetic") },
                    true,
                    65536,
                )
            assertEquals(TextResult.Completed("ok"), provider.stream(messages) {})
            assertEquals(listOf("/model/vendor/rare", "/chat/completions"), paths)
        }

    /** Sends one message to a model with catalog [row] and returns the chat request's body. */
    private suspend fun send(
        row: String,
        isOn: Boolean,
        level: ReasoningEffort?,
        input: List<TextMessage> = messages,
    ): JsonObject {
        var sent: JsonObject? = null
        val http = MemoryHttp { request ->
            if (request.url.encodedPath.endsWith("models")) {
                request.reply("""{"data":[$row]}""")
            } else {
                sent = Json.parseToJsonElement(request.payload()).jsonObject
                request.reply(
                    """data: {"choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}""" +
                        "\n\ndata: [DONE]\n\n"
                )
            }
        }
        val peer =
            CompatibleProvider(
                http,
                origin,
                "sample",
                AccessTokenSource { Secret("test") },
                isOn,
                65536,
                level,
            )
        assertEquals(TextResult.Completed("hi"), peer.stream(input) {})
        return requireNotNull(sent)
    }

    private val JsonObject.reasoning: String?
        get() = this["reasoning"]?.toString()

    private val JsonObject.tokens: Int?
        get() = this["max_tokens"]?.jsonPrimitive?.int

    private companion object {
        /** The reply allowance for a turn without a contract. */
        const val REPLY = 1536
        const val LEVELS =
            """{"id":"sample","supported_parameters":["max_tokens","reasoning"],""" +
                """"reasoning":{"mandatory":false,"supported_efforts":["max","high","low","none"]}}"""
        const val REQUIRED =
            """{"id":"sample","supported_parameters":["max_tokens","reasoning"],""" +
                """"reasoning":{"mandatory":true,"supported_efforts":["max","high","low"]}}"""
        const val ON_OFF =
            """{"id":"sample","supported_parameters":["max_tokens","reasoning"],""" +
                """"reasoning":{"mandatory":false}}"""
        const val NO_REASONING = """{"id":"sample","supported_parameters":["max_tokens"]}"""
    }
}
