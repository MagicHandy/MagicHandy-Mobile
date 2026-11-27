package com.magichandy.mobile.transport.providers

import com.magichandy.mobile.core.api.ReasoningEffort
import com.magichandy.mobile.core.api.ReasoningEffort.High
import com.magichandy.mobile.core.api.ReasoningEffort.Low
import com.magichandy.mobile.core.api.ReasoningEffort.Max
import com.magichandy.mobile.core.api.ReasoningOptions
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.ports.KeyCheck
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class OpenRouterAccountTest {
    private val origin = "https://fixture.invalid/api/v1/".toHttpUrl()

    @Test
    fun `the catalog keeps models that can chat and reads their reasoning and prices`() {
        val models = routerModels(CATALOG)
        assertEquals(
            listOf(
                "deepseek/deepseek-v4.1-flash-1017",
                "z-ai/glm-5.3-flash",
                "tencent/hy4-flash",
                "xiaomi/mimo",
                "google/gemma-4",
            ),
            models.map { it.id },
        )
        val (deepSeek, glm, hy4, mimo, gemma) = models
        assertEquals("DeepSeek V4.1 Flash 1017", deepSeek.name)
        assertEquals(1_048_576, deepSeek.contextTokens)
        assertEquals(ReasoningOptions(listOf(Low, High, Max), false, High), deepSeek.reasoning)
        assertEquals(36_000L, deepSeek.inputMicrosPerMillion)
        assertEquals(500_000L, deepSeek.outputMicrosPerMillion)
        assertEquals(39_400L, deepSeek.hundredRepliesMicros)
        assertEquals(ReasoningOptions(listOf(Low, High, Max), true, Max), glm.reasoning)
        assertEquals(ReasoningEffort.entries, hy4.reasoning?.levels)
        assertTrue(hy4.isFree)
        assertNull(mimo.reasoning)
        assertNull(mimo.hundredRepliesMicros)
        assertNull(gemma.reasoning)
        assertNull(gemma.inputMicrosPerMillion)
        assertEquals(300_000L, gemma.outputMicrosPerMillion)
    }

    @Test
    fun `the catalog request carries no key, asks only for usable models and lasts 15 minutes`() =
        runTest {
            var calls = 0
            var now = 0L
            val http = MemoryHttp { request ->
                calls++
                assertNull(request.header("Authorization"))
                assertTrue(request.url.encodedPath.endsWith("/models"))
                assertEquals(
                    "structured_outputs",
                    request.url.queryParameter("supported_parameters"),
                )
                assertEquals("true", request.url.queryParameter("zdr"))
                assertEquals("most-popular", request.url.queryParameter("sort"))
                assertEquals("1000", request.url.queryParameter("limit"))
                request.reply(CATALOG)
            }
            val account = OpenRouterAccount(http, origin) { now }
            assertEquals(5, account.models().size)
            now = TimeUnit.MINUTES.toNanos(14)
            account.models()
            assertEquals(1, calls)
            now = TimeUnit.MINUTES.toNanos(16)
            account.models()
            assertEquals(2, calls)
        }

    @Test
    fun `a refused or unreadable catalog is reported as unreachable`() = runTest {
        for ((code, body) in listOf(503 to CATALOG, 200 to "<html>", 200 to """{"data":3}""")) {
            val account = OpenRouterAccount(MemoryHttp { it.reply(body, code = code) }, origin)
            assertFailsWith<IOException> { account.models() }
        }
    }

    @Test
    fun `the key check sends only the key and reads OpenRouter's answer`() = runTest {
        for ((code, answer) in
            listOf(
                200 to KeyCheck.Accepted,
                401 to KeyCheck.Rejected,
                403 to KeyCheck.Rejected,
                429 to KeyCheck.Unreachable,
                502 to KeyCheck.Unreachable,
            )) {
            val http = MemoryHttp { request ->
                assertEquals("Bearer sk-or-fixture", request.header("Authorization"))
                assertTrue(request.url.encodedPath.endsWith("/key"))
                assertNull(request.body)
                request.reply("""{"data":{"is_management_key":false}}""", code = code)
            }
            assertEquals(answer, OpenRouterAccount(http, origin).check(Secret("sk-or-fixture")))
        }
    }

    @Test
    fun `a management key isn't accepted for chat`() = runTest {
        for (field in listOf("is_management_key", "is_provisioning_key")) {
            val http = MemoryHttp { it.reply("""{"data":{"label":"x","$field":true}}""") }
            assertEquals(
                KeyCheck.Rejected,
                OpenRouterAccount(http, origin).check(Secret("sk-or-x")),
            )
        }
        val chat = MemoryHttp { it.reply("""{"data":{"is_management_key":false,"limit":null}}""") }
        assertEquals(KeyCheck.Accepted, OpenRouterAccount(chat, origin).check(Secret("sk-or-x")))
    }

    @Test
    fun `unreadable successful key replies are unavailable and cannot register a credential`() =
        runTest {
            for (body in
                listOf(
                    "",
                    "<html>maintenance</html>",
                    "{}",
                    """{"data":[]}""",
                    """{"data":{"is_management_key":"false"}}""",
                )) {
                val account = OpenRouterAccount(MemoryHttp { it.reply(body) }, origin)
                assertEquals(KeyCheck.Unreachable, account.check(Secret("synthetic")))
            }
        }

    @Test
    fun `the account adapter rejects header-unsafe keys before transport`() = runTest {
        val account = OpenRouterAccount(MemoryHttp { error("Unexpected network request") }, origin)
        for (key in listOf("a\nb", "a b", "а")) assertEquals(
            KeyCheck.Rejected,
            account.check(Secret(key)),
        )
    }

    private companion object {
        val CATALOG =
            """
            {"data":[
              null,
              {"id":"deepseek/deepseek-v4.1-flash-1017","name":"DeepSeek: DeepSeek V4.1 Flash 1017",
               "context_length":1048576,"pricing":{"prompt":"0.000000036","completion":"0.0000005"},
               "supported_parameters":["structured_outputs","reasoning","max_tokens"],
               "reasoning":{"mandatory":false,"supported_efforts":["max","high","low","none"],
                 "default_effort":"high"}},
              {"id":"z-ai/glm-5.3-flash","name":"Z.ai: GLM 5.3 Flash","context_length":1048576,
               "pricing":{"prompt":"0.00000015","completion":"0.0000005"},
               "supported_parameters":["structured_outputs","reasoning"],
               "reasoning":{"mandatory":true,"supported_efforts":["max","high","low"],
                 "default_effort":"max"}},
              {"id":"tencent/hy4-flash","name":"Tencent: Hy4 flash","context_length":1048576,
               "pricing":{"prompt":"0","completion":"0"},
               "supported_parameters":["structured_outputs","reasoning"],
               "reasoning":{"mandatory":false,"supported_efforts":null,"default_effort":null}},
              {"id":"xiaomi/mimo","name":"Xiaomi: MiMo","supported_parameters":["structured_outputs"],
               "reasoning":{"mandatory":false}},
              {"id":"google/gemma-4","name":"Google: Gemma 4",
               "supported_parameters":["structured_outputs"],
               "pricing":{"prompt":"-1","completion":"0.0000003"}},
              {"id":"deepseek/deepseek-v4.1-flash-1017:batch","name":"Batch",
               "supported_parameters":["structured_outputs"]},
              {"id":"no/format","name":"No format","supported_parameters":["max_tokens"]},
              {"id":"bad id","supported_parameters":["structured_outputs"]}
            ]}
            """
                .trimIndent()
    }
}
