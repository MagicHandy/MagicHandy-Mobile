package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ReplyLength
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.chat.PromptBudget
import com.magichandy.mobile.core.ports.ModelCatalog
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.ports.builtInPersona
import com.magichandy.mobile.core.testing.InMemoryModels
import com.magichandy.mobile.transport.simulated.DebugFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

internal class CompatibleProviderTest {
    private fun sse(content: String, finish: String) =
        """data: {"choices":[{"index":0,"delta":{"content":"$content"},"finish_reason":$finish}]}""" +
            "\n\n"

    private fun ollamaReply(content: String) =
        """{"message":{"content":"$content"},"done":true,"done_reason":"stop"}""" + "\n"

    @Test
    fun `only a completed first choice followed by DONE can authorize output`() {
        val good = sse("ok", "\"stop\"")
        val done = "data: [DONE]\n\n"
        assertEquals(
            TextResult.Completed("ok"),
            compatibleStream(Buffer().writeUtf8(good + done)) {},
        )
        for (raw in
            listOf(
                done,
                good,
                sse("a", "null") + done,
                good + sse("late", "null") + done,
                sse("a", "\"length\"") + done,
            )) assertEquals(TextResult.Interrupted, compatibleStream(Buffer().writeUtf8(raw)) {})
        assertEquals(
            TextResult.Refused,
            compatibleStream(Buffer().writeUtf8(sse("", "\"content_filter\""))) {},
        )
    }

    @Test
    fun `catalog schema and reasoning are negotiated once before hosted completion`() = runTest {
        val paths = mutableListOf<String>()
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val r = chain.request()
                    paths += r.url.encodedPath
                    assertEquals("Bearer synthetic", r.header("Authorization"))
                    val body =
                        if (r.url.encodedPath.endsWith("models"))
                            """{"data":[{"id":"sample","reasoning":{"mandatory":false},"supported_parameters":["structured_outputs","max_tokens"]}]}"""
                        else {
                            val buf = Buffer()
                            requireNotNull(r.body).writeTo(buf)
                            val json = Json.parseToJsonElement(buf.readUtf8()).jsonObject
                            assertEquals(
                                JsonPrimitive(true),
                                json.getValue("reasoning").jsonObject["enabled"],
                            )
                            assertEquals(
                                JsonPrimitive("json_schema"),
                                json.getValue("response_format").jsonObject["type"],
                            )
                            sse("ok", "\"stop\"") + "data: [DONE]\n\n"
                        }
                    Response.Builder()
                        .request(r)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        val peer =
            CompatibleProvider(
                http,
                "https://offline.invalid/v1/".toHttpUrl(),
                "sample",
                AccessTokenSource { Secret("synthetic") },
                true,
                65536,
            )
        repeat(2) {
            assertEquals(
                TextResult.Completed("ok"),
                peer.complete(
                    ModelRequest(
                        listOf(TextMessage(TextRole.User, "Synthetic.")),
                        JsonObject(emptyMap()),
                    )
                ),
            )
        }
        assertEquals(listOf("/v1/models", "/v1/chat/completions", "/v1/chat/completions"), paths)
    }

    @Test
    fun `structured local compatible and Ollama budgets match Simple Intermediate and Detailed contracts`() =
        runTest {
            data class Case(
                val level: CapabilityLevel,
                val length: ReplyLength,
                val context: Int,
                val expectedOutput: Int,
            )
            val cases =
                listOf(
                    Case(CapabilityLevel.Simple, ReplyLength.Short, 16384, 256),
                    Case(CapabilityLevel.Simple, ReplyLength.Detailed, 16384, 512),
                    Case(CapabilityLevel.Intermediate, ReplyLength.Short, 32768, 256),
                    Case(CapabilityLevel.Intermediate, ReplyLength.Detailed, 32768, 512),
                    Case(CapabilityLevel.Advanced, ReplyLength.Short, 65536, 1536),
                )
            for (kind in listOf(ProviderKind.OpenAiCompatible, ProviderKind.Ollama)) {
                for (case in cases) {
                    var body: JsonObject? = null
                    val wire = MemoryHttp { req ->
                        body = Json.parseToJsonElement(req.payload()).jsonObject
                        val payload = """{\"reply\":\"Ready.\"}"""
                        val response =
                            if (kind == ProviderKind.Ollama) ollamaReply(payload)
                            else sse(payload, "\"stop\"") + "data: [DONE]\n\n"
                        req.reply(
                            response,
                            if (kind == ProviderKind.Ollama) "application/x-ndjson"
                            else "text/event-stream",
                        )
                    }
                    val id = ConnectionId("local")
                    val info =
                        ModelConnection(
                            id,
                            "Local",
                            kind,
                            "local-model",
                            "Local",
                            case.level,
                            false,
                            false,
                            ConnectionStatus.Ready,
                        )
                    val saved = SavedModel(info, contextTokens = case.context)
                    val f =
                        DebugFixture(this) { d ->
                            d.copy(
                                models = InMemoryModels(ModelCatalog(mapOf(id to saved), id)),
                                provider = {
                                    if (kind == ProviderKind.Ollama)
                                        OllamaProvider(
                                            wire,
                                            "http://127.0.0.1:11434/".toHttpUrl(),
                                            info.model,
                                            case.context,
                                        )
                                    else
                                        CompatibleProvider(
                                            wire,
                                            "http://127.0.0.1:8080/v1/".toHttpUrl(),
                                            info.model,
                                            null,
                                            false,
                                            case.context,
                                        )
                                },
                            )
                        }
                    f.onboard()
                    assertEquals(
                        FormResult.Accepted,
                        f.core.submit(
                            UserIntent.SavePersona(
                                builtInPersona.id,
                                builtInPersona.name,
                                Personality(replyLength = case.length),
                            )
                        ),
                    )
                    f.send(UserIntent.SendMessage("Hello"))
                    val sent = requireNotNull(body)
                    if (kind == ProviderKind.Ollama) {
                        val options = sent.getValue("options").jsonObject
                        assertEquals(JsonPrimitive(case.context), options["num_ctx"])
                        assertEquals(JsonPrimitive(case.expectedOutput), options["num_predict"])
                    } else {
                        assertEquals(JsonPrimitive(case.expectedOutput), sent["max_tokens"])
                    }
                    assertEquals(
                        "Ready.",
                        (f.core.state.value.chat.entries.last() as ChatEntry.Message).text,
                    )
                    f.scope.cancel()
                }
            }
        }

    @Test
    fun `local compatible and Ollama admit prompts sized to contract output allowance and trim Ollama repair`() =
        runTest {
            val context = 16384
            val schema = buildJsonObject { put("type", "object") }
            val schemaBytes = schema.toString().encodeToByteArray().size
            for (allowance in listOf(256, 512)) {
                val limit = PromptBudget(context, allowance, schemaBytes = schemaBytes).limitBytes
                val developer = TextMessage(TextRole.Developer, "Rules.")
                val user = TextMessage(TextRole.User, "a".repeat(limit - 134))
                val request =
                    ModelRequest(listOf(developer, user), schema, outputAllowance = allowance)
                val sseOk = sse("ok", "\"stop\"") + "data: [DONE]\n\n"

                var compatibleBody: JsonObject? = null
                val compatible =
                    CompatibleProvider(
                        MemoryHttp { req ->
                            compatibleBody = Json.parseToJsonElement(req.payload()).jsonObject
                            req.reply(sseOk, "text/event-stream")
                        },
                        "http://127.0.0.1:8080/v1/".toHttpUrl(),
                        "sample",
                        null,
                        false,
                        context,
                    )
                assertEquals(TextResult.Completed("ok"), compatible.complete(request))
                val compatibleSent = requireNotNull(compatibleBody)
                assertEquals(JsonPrimitive(allowance), compatibleSent["max_tokens"])
                assertEquals(2, compatibleSent.getValue("messages").jsonArray.size)

                var ollamaBody: JsonObject? = null
                val ollama =
                    OllamaProvider(
                        MemoryHttp { req ->
                            ollamaBody = Json.parseToJsonElement(req.payload()).jsonObject
                            req.reply(ollamaReply("ok"))
                        },
                        "http://127.0.0.1:11434/".toHttpUrl(),
                        "sample",
                        context,
                    )
                assertEquals(TextResult.Completed("ok"), ollama.complete(request))
                val ollamaSent = requireNotNull(ollamaBody)
                assertEquals(
                    JsonPrimitive(allowance),
                    ollamaSent.getValue("options").jsonObject["num_predict"],
                )
                assertEquals(2, ollamaSent.getValue("messages").jsonArray.size)
            }

            val limit = PromptBudget(context, 256, schemaBytes = schemaBytes).limitBytes
            val developer = TextMessage(TextRole.Developer, "Rules.")
            val older = TextMessage(TextRole.User, "x".repeat(500))
            val repairTail =
                listOf(
                    TextMessage(TextRole.User, "a".repeat(limit - 320)),
                    TextMessage(TextRole.Assistant, "{"),
                    TextMessage(TextRole.User, "Repair."),
                )
            var calls = 0
            var trimmedBody: JsonObject? = null
            val ollama =
                OllamaProvider(
                    MemoryHttp { req ->
                        calls++
                        trimmedBody = Json.parseToJsonElement(req.payload()).jsonObject
                        req.reply(ollamaReply("ok"))
                    },
                    "http://127.0.0.1:11434/".toHttpUrl(),
                    "sample",
                    context,
                )
            assertEquals(
                TextResult.Completed("ok"),
                ollama.complete(
                    ModelRequest(
                        listOf(developer, older) + repairTail,
                        schema,
                        preserveTail = 3,
                        outputAllowance = 256,
                    )
                ),
            )
            assertEquals(1, calls)
            assertEquals(
                (listOf(developer) + repairTail).map { it.text },
                requireNotNull(trimmedBody).getValue("messages").jsonArray.map {
                    it.jsonObject.getValue("content").jsonPrimitive.content
                },
            )
            assertEquals(
                TextResult.Interrupted,
                ollama.complete(
                    ModelRequest(
                        listOf(
                            developer,
                            TextMessage(TextRole.User, "a".repeat(limit)),
                            TextMessage(TextRole.Assistant, "{"),
                            TextMessage(TextRole.User, "Repair."),
                        ),
                        schema,
                        preserveTail = 3,
                        outputAllowance = 256,
                    )
                ),
            )
            assertEquals(1, calls)
        }
}
