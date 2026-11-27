package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ReasoningEffort.High
import com.magichandy.mobile.core.api.ReasoningEffort.Low
import com.magichandy.mobile.core.api.ReasoningEffort.Max
import com.magichandy.mobile.core.api.ReasoningOptions
import com.magichandy.mobile.core.api.RouterCatalog
import com.magichandy.mobile.core.api.RouterModel
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UpdateCheck
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.KeyCheck
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.ModelStore
import com.magichandy.mobile.core.ports.OpenRouterAccess
import com.magichandy.mobile.core.ports.ReleaseFeed
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Saving and changing OpenRouter connections against a fake catalog (design §4). */
internal class OpenRouterSetupTest {
    private class FakeRouter(val models: List<RouterModel>) : OpenRouterAccess {
        var key = KeyCheck.Accepted
        var isReachable = true

        override suspend fun models(): List<RouterModel> =
            if (isReachable) models else throw IOException("Offline")

        override suspend fun check(key: Secret): KeyCheck = this.key
    }

    private val deepSeek =
        RouterModel(
            "deepseek/deepseek-v4.1-flash-1017",
            "DeepSeek V4.1 Flash 1017",
            1_048_576,
            ReasoningOptions(listOf(Low, High, Max), isRequired = false, defaultLevel = High),
            36_000,
            500_000,
        )
    private val glm =
        RouterModel(
            "z-ai/glm-5.3-flash",
            "GLM 5.3 Flash",
            1_048_576,
            ReasoningOptions(listOf(Low, High, Max), isRequired = true, defaultLevel = Max),
            150_000,
            500_000,
        )
    private val gemma = RouterModel("google/gemma-4", "Gemma 4", 262_144, null, 90_000, 300_000)

    @Test
    fun `saving checks the key and the model, and settles reasoning the model can't honour`() =
        runTest {
            val router = FakeRouter(listOf(deepSeek, glm, gemma))
            val f = DebugFixture(this) { it.copy(openRouter = router) }
            f.onboard()
            val save =
                UserIntent.SaveOpenRouter(
                    Secret("sk-or-fixture"),
                    glm.id,
                    CapabilityLevel.Advanced,
                    isReasoningOn = false,
                )
            router.key = KeyCheck.Rejected
            assertEquals(FormResult.Rejected(FormReason.InvalidKey), f.core.submit(save))
            router.key = KeyCheck.Unreachable
            assertEquals(FormResult.Rejected(FormReason.Unreachable), f.core.submit(save))
            router.key = KeyCheck.Accepted
            assertEquals(
                FormResult.Rejected(FormReason.InvalidModel),
                f.core.submit(save.copy(model = "unknown/model")),
            )
            router.isReachable = false
            assertEquals(FormResult.Rejected(FormReason.Unreachable), f.core.submit(save))
            assertTrue(router(f).isEmpty())
            router.isReachable = true
            assertEquals(FormResult.Accepted, f.core.submit(save))
            val saved = router(f).single()
            // GLM always reasons, so Off becomes its lowest level.
            assertEquals("GLM 5.3 Flash", saved.info.shortName)
            assertTrue(saved.info.isReasoningOn)
            assertEquals(Low, saved.info.reasoningEffort)
            assertEquals(
                FormResult.Rejected(FormReason.Duplicate),
                f.core.submit(save.copy(isReasoningOn = true)),
            )
            // A level the app doesn't offer becomes the nearest one it does.
            f.core.submit(
                save.copy(model = deepSeek.id, isReasoningOn = true, reasoningEffort = Max)
            )
            assertEquals(
                High,
                router(f).single { it.info.model == deepSeek.id }.info.reasoningEffort,
            )
            // A model that can't reason is saved with reasoning off.
            f.core.submit(save.copy(model = gemma.id, isReasoningOn = true, reasoningEffort = High))
            val plain = router(f).single { it.info.model == gemma.id }.info
            assertFalse(plain.isReasoningOn)
            assertNull(plain.reasoningEffort)
            f.scope.cancel()
        }

    @Test
    fun `editing keeps the saved key unless a new one is checked and accepted`() = runTest {
        val router = FakeRouter(listOf(deepSeek, glm, gemma))
        val f = DebugFixture(this) { it.copy(openRouter = router) }
        f.onboard()
        val first = Secret("sk-or-first")
        f.core.submit(
            UserIntent.SaveOpenRouter(
                first,
                deepSeek.id,
                CapabilityLevel.Advanced,
                true,
                false,
                High,
            )
        )
        val saved = router(f).single()
        val handle = requireNotNull(saved.credential)
        val edit =
            UserIntent.EditOpenRouter(
                saved.info.id,
                null,
                gemma.id,
                CapabilityLevel.Intermediate,
                isReasoningOn = true,
                reasoningEffort = High,
            )
        assertEquals(FormResult.Accepted, f.core.submit(edit))
        val edited = router(f).single()
        assertEquals(saved.info.id, edited.info.id)
        assertEquals("Gemma 4", edited.info.shortName)
        assertEquals(CapabilityLevel.Intermediate, edited.info.capability)
        assertFalse(edited.info.isReasoningOn)
        assertEquals(first, f.d.vault.get(handle))
        router.key = KeyCheck.Rejected
        assertEquals(
            FormResult.Rejected(FormReason.InvalidKey),
            f.core.submit(edit.copy(apiKey = Secret("sk-or-second"))),
        )
        assertEquals(first, f.d.vault.get(handle))
        router.key = KeyCheck.Accepted
        assertEquals(FormResult.Accepted, f.core.submit(edit.copy(apiKey = Secret("sk-or-second"))))
        val changed = router(f).single()
        assertTrue(changed.credential != handle)
        assertNull(f.d.vault.get(handle))
        assertEquals(Secret("sk-or-second"), f.d.vault.get(requireNotNull(changed.credential)))
        assertEquals(
            FormResult.Rejected(FormReason.NotFound),
            f.core.submit(edit.copy(connection = ConnectionId("missing"))),
        )
        f.scope.cancel()
    }

    @Test
    fun `a rejected database edit leaves the old key attached and erases the staged key`() =
        runTest {
            val access = FakeRouter(listOf(gemma, glm))
            var fail = false
            var attempted: SavedModel? = null
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        openRouter = access,
                        models =
                            object : ModelStore by d.models {
                                override suspend fun save(model: SavedModel) = save(model, false)

                                override suspend fun save(
                                    model: SavedModel,
                                    selectForChat: Boolean,
                                ) {
                                    attempted = model
                                    if (fail) throw IOException("synthetic write failure")
                                    d.models.save(model, selectForChat)
                                }
                            },
                    )
                }
            f.onboard()
            val first = Secret("synthetic-original")
            assertEquals(
                FormResult.Accepted,
                f.core.submit(
                    UserIntent.SaveOpenRouter(first, gemma.id, CapabilityLevel.Advanced, false)
                ),
            )
            val saved = router(f).single()
            fail = true
            assertEquals(
                FormResult.Rejected(FormReason.StorageUnavailable),
                f.core.submit(
                    UserIntent.EditOpenRouter(
                        saved.info.id,
                        Secret("synthetic-replacement"),
                        glm.id,
                        CapabilityLevel.Simple,
                        false,
                        null,
                    )
                ),
            )
            assertEquals(saved, router(f).single())
            assertEquals(first, f.d.vault.get(requireNotNull(saved.credential)))
            val staged = requireNotNull(attempted?.credential)
            assertTrue(staged != saved.credential)
            assertNull(f.d.vault.get(staged))
            f.scope.cancel()
        }

    @Test
    fun `loading the catalog publishes it, and a failed refresh keeps what's shown`() = runTest {
        val router = FakeRouter(listOf(deepSeek, gemma))
        val f = DebugFixture(this) { it.copy(openRouter = router) }
        f.onboard()
        router.isReachable = false
        f.send(UserIntent.LoadOpenRouterModels)
        assertEquals(RouterCatalog.Failed, f.core.state.value.models.routerCatalog)
        router.isReachable = true
        f.send(UserIntent.LoadOpenRouterModels)
        assertEquals(
            RouterCatalog.Loaded(listOf(deepSeek, gemma)),
            f.core.state.value.models.routerCatalog,
        )
        router.isReachable = false
        f.send(UserIntent.LoadOpenRouterModels)
        assertEquals(
            RouterCatalog.Loaded(listOf(deepSeek, gemma)),
            f.core.state.value.models.routerCatalog,
        )
        f.scope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a reasoning edit starts on the next message while a replaced key fences the old reply`() =
        runTest {
            for (replaceKey in listOf(false, true)) {
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val f =
                    DebugFixture(this) { d ->
                        d.copy(
                            openRouter = FakeRouter(listOf(deepSeek)),
                            provider = {
                                object : StructuredProvider {
                                    override suspend fun complete(
                                        request: ModelRequest,
                                        preview: (String) -> Unit,
                                    ): TextResult {
                                        started.complete(Unit)
                                        finish.await()
                                        return TextResult.Completed(
                                            """{"reply":"Original turn."}"""
                                        )
                                    }

                                    override suspend fun stream(
                                        messages: List<TextMessage>,
                                        preview: (String) -> Unit,
                                    ): TextResult = error("Expected the structured chat path")
                                }
                            },
                        )
                    }
                f.onboard()
                f.core.submit(
                    UserIntent.SaveOpenRouter(
                        Secret("initial"),
                        deepSeek.id,
                        CapabilityLevel.Advanced,
                        false,
                        true,
                    )
                )
                val saved = router(f).single()
                f.send(UserIntent.SendMessage("Hello there."))
                assertTrue(started.isCompleted)
                assertEquals(
                    FormResult.Accepted,
                    f.core.submit(
                        UserIntent.EditOpenRouter(
                            saved.info.id,
                            if (replaceKey) Secret("replaced") else null,
                            deepSeek.id,
                            CapabilityLevel.Advanced,
                            true,
                            High,
                        )
                    ),
                )
                finish.complete(Unit)
                runCurrent()
                val replies =
                    f.core.state.value.chat.entries.filterIsInstance<ChatEntry.Message>().filter {
                        it.author == Author.Persona
                    }
                assertEquals(
                    if (replaceKey) emptyList() else listOf("Original turn."),
                    replies.map { it.text },
                )
                f.scope.cancel()
            }
        }

    @Test
    fun `saving and changing a model carries its smaller context without expanding the mobile default`() =
        runTest {
            val small = gemma.copy(contextTokens = 8192)
            val f = DebugFixture(this) { it.copy(openRouter = FakeRouter(listOf(small, deepSeek))) }
            f.onboard()
            f.core.submit(
                UserIntent.SaveOpenRouter(
                    Secret("fixture"),
                    small.id,
                    CapabilityLevel.Advanced,
                    false,
                )
            )
            val saved = router(f).single()
            assertEquals(8192, saved.contextTokens)
            f.core.submit(
                UserIntent.EditOpenRouter(
                    saved.info.id,
                    null,
                    deepSeek.id,
                    CapabilityLevel.Advanced,
                    false,
                    null,
                )
            )
            assertEquals(65536, router(f).single().contextTokens)
            f.scope.cancel()
        }

    @Test
    fun `catalog and release loaders leave loading after an unreadable response`() = runTest {
        val f =
            DebugFixture(this) { d ->
                d.copy(
                    openRouter =
                        object : OpenRouterAccess {
                            override suspend fun check(key: Secret) = KeyCheck.Accepted

                            override suspend fun models(): List<RouterModel> =
                                throw IllegalArgumentException("Invalid payload")
                        },
                    releases =
                        ReleaseFeed {
                            throw IllegalArgumentException("Invalid payload")
                        },
                )
            }
        f.onboard()
        f.send(UserIntent.LoadOpenRouterModels)
        f.send(UserIntent.CheckForUpdate)
        assertEquals(RouterCatalog.Failed, f.core.state.value.models.routerCatalog)
        assertEquals(
            UpdateCheck.Failed,
            f.core.state.value.app.update,
        )
        f.scope.cancel()
    }

    private fun router(f: DebugFixture): List<SavedModel> =
        f.d.models.state.value.models.values.filter { it.info.provider == ProviderKind.OpenRouter }
}
