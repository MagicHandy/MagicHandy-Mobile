package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.Author
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ChatEntry
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.ConnectionStatus
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.InterruptCause
import com.magichandy.mobile.core.api.ModelConnection
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.ProviderReplyFailure
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.ModelCatalog
import com.magichandy.mobile.core.ports.ModelProvider
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.PlanningProvider
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import com.magichandy.mobile.core.testing.InMemoryModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class RefusalAndEdgeTest {
    @Test
    fun `a model Stop records the stopped turn instead of a provider failure`() = runTest {
        val canned = SimulatedModelProvider()
        val peer =
            object : StructuredProvider by canned {
                override suspend fun complete(request: ModelRequest, preview: (String) -> Unit) =
                    TextResult.Completed(
                        """{"tempo":null,"air_steps":null,"stop":true,"reply":"Stopping."}"""
                    )
            }
        val fixture = DebugFixture(this) { it.copy(provider = { peer }) }
        fixture.onboard()
        val original = fixture.d.models.state.value.models.values.single()
        fixture.d.models.save(
            original.copy(info = original.info.copy(capability = CapabilityLevel.Simple))
        )
        fixture.connect(DeviceId("vacuglide"))
        fixture.send(UserIntent.SetMotionMode(com.magichandy.mobile.core.api.MotionMode.Tempo))
        fixture.send(UserIntent.StartMotion)
        fixture.send(UserIntent.SendMessage("End this simulated session now."))
        runCurrent()
        assertEquals(RunPhase.Idle, fixture.core.state.value.run.phase)
        assertEquals(
            ReplyState.Interrupted(InterruptCause.MotionStopped),
            fixture.core.state.value.chat.reply,
        )
        fixture.scope.cancel()
    }

    @Test
    fun `a hosted request error reaches the chat bubble without retrying or committing a reply`() =
        runTest {
            val detail = ProviderReplyFailure(ProviderReplyFailure.Reason.UsageLimit, 429)
            var attempts = 0
            val fixture =
                DebugFixture(this) { d ->
                    d.copy(
                        provider = {
                            object : StructuredProvider {
                                override suspend fun complete(
                                    request: ModelRequest,
                                    preview: (String) -> Unit,
                                ): TextResult {
                                    attempts++
                                    return TextResult.Failed(detail)
                                }

                                override suspend fun stream(
                                    messages: List<TextMessage>,
                                    preview: (String) -> Unit,
                                ): TextResult = error("Expected a structured turn")
                            }
                        }
                    )
                }
            fixture.onboard()
            fixture.send(UserIntent.SendMessage("Hello"))
            val chat = fixture.core.state.value.chat
            assertEquals(ReplyState.Interrupted(InterruptCause.ProviderError, detail), chat.reply)
            assertEquals(1, attempts)
            assertTrue(
                chat.entries.filterIsInstance<ChatEntry.Message>().none {
                    it.author == Author.Persona
                }
            )
            fixture.scope.cancel()
        }

    @Test
    fun `a refusal is retried exactly once and only after explicit local authorization`() =
        runTest {
            val primary = ConnectionId("host")
            val alternate = ConnectionId("local")
            val host =
                ModelConnection(
                    primary,
                    "Hosted",
                    ProviderKind.OpenRouter,
                    "host",
                    "Host",
                    CapabilityLevel.Advanced,
                    true,
                    true,
                    ConnectionStatus.Ready,
                )
            var retries = 0
            var waiting: CompletableDeferred<Unit>? = null
            var cancelled = false
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        models =
                            InMemoryModels(
                                ModelCatalog(
                                    mapOf(
                                        primary to SavedModel(host),
                                        alternate to
                                            SavedModel(
                                                host.copy(
                                                    id = alternate,
                                                    provider = ProviderKind.Ollama,
                                                    capability = CapabilityLevel.Simple,
                                                )
                                            ),
                                    ),
                                    primary,
                                )
                            ),
                        provider = { m ->
                            object : StructuredProvider {
                                override suspend fun complete(
                                    request: ModelRequest,
                                    preview: (String) -> Unit,
                                ): TextResult =
                                    if (m.info.id == primary) TextResult.Refused
                                    else {
                                        retries++
                                        try {
                                            waiting?.await()
                                        } catch (stop: CancellationException) {
                                            cancelled = true
                                            throw stop
                                        }
                                        TextResult.Completed("""{"reply":"Local reply."}""")
                                    }

                                override suspend fun stream(
                                    messages: List<TextMessage>,
                                    preview: (String) -> Unit,
                                ): TextResult = error("unexpected")
                            }
                        },
                    )
                }
            f.onboard()
            f.send(UserIntent.SendMessage("Remember that shorter sessions suit me."))
            assertEquals(0, retries)
            assertEquals(
                "ProviderRefused",
                (f.core.state.value.chat.reply as ReplyState.Interrupted).cause.name,
            )
            f.core.setRefusalFallback(alternate)
            runCurrent()
            f.send(UserIntent.SetMemory(true))
            f.send(UserIntent.SendAgain)
            assertEquals(1, retries)
            assertEquals(listOf("shorter sessions suit me."), f.core.state.value.settings.memories)
            f.send(UserIntent.ClearMemory)
            assertTrue(f.core.state.value.settings.memories.isEmpty())
            val gate = CompletableDeferred<Unit>()
            waiting = gate
            f.send(UserIntent.SendMessage("Pending reply"))
            assertEquals(2, retries)
            f.core.setRefusalFallback(null)
            runCurrent()
            assertTrue(cancelled)
            gate.complete(Unit)
            runCurrent()
            assertIs<ReplyState.Interrupted>(f.core.state.value.chat.reply)
            assertEquals(
                1,
                f.core.state.value.chat.entries.filterIsInstance<ChatEntry.Message>().count {
                    it.author == Author.Persona
                },
            )
            f.scope.cancel()
        }

    @Test
    fun `Edge holds without inference outside Autopilot and a pending automatic return cannot survive Stop`() =
        runTest {
            var edgeCalls = 0
            val p =
                object : ModelProvider, PlanningProvider {
                    override suspend fun stream(
                        messages: List<TextMessage>,
                        preview: (String) -> Unit,
                    ) = TextResult.Interrupted

                    override suspend fun plan(request: ModelRequest): TextResult {
                        if (request.name == "edge_plan") {
                            edgeCalls++
                            return TextResult.Completed(
                                """{"hold_seconds":8,"resume_speed_percent":15,"resume_style":"gentle"}"""
                            )
                        }
                        return TextResult.Completed("""{"edits":{},"reply":"Hold."}""")
                    }
                }
            val f = DebugFixture(this) { it.copy(provider = { p }) }
            f.onboard()
            f.connect(DeviceId("handy"))
            f.send(UserIntent.StartMotion)
            f.send(UserIntent.SetEdge(true))
            assertEquals(0, edgeCalls)
            f.send(UserIntent.SetEdge(false))
            f.send(UserIntent.StartAutopilot)
            f.send(UserIntent.SetEdge(true))
            assertEquals(1, edgeCalls)
            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            val count = f.devices.output.value.getValue(DeviceId("handy")).batches
            advanceTimeBy(10000)
            runCurrent()
            assertEquals(count, f.devices.output.value.getValue(DeviceId("handy")).batches)
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            f.scope.cancel()
        }

    @Test
    fun `fallback sends only the current message and excludes persona memory`() = runTest {
        var refuse = false
        var invalid = false
        val localCalls = mutableListOf<ModelRequest>()
        val primary = ConnectionId("primary")
        val alternate = ConnectionId("alternate")
        val f =
            DebugFixture(this) { d ->
                val prototype = d.models.state.value.models.values.single().info
                val catalog =
                    ModelCatalog(
                        mapOf(
                            primary to
                                SavedModel(
                                    prototype.copy(id = primary, provider = ProviderKind.OpenRouter)
                                ),
                            alternate to
                                SavedModel(
                                    prototype.copy(id = alternate, provider = ProviderKind.Ollama)
                                ),
                        ),
                        primary,
                    )
                d.copy(
                    models = InMemoryModels(catalog),
                    provider = { m ->
                        object : StructuredProvider {
                            override suspend fun complete(
                                request: ModelRequest,
                                preview: (String) -> Unit,
                            ): TextResult {
                                if (m.info.id == primary && refuse) return TextResult.Refused
                                if (m.info.id == alternate) {
                                    localCalls += request
                                    if (invalid) return TextResult.Completed("""{"invalid":true}""")
                                }
                                return TextResult.Completed("""{"reply":"Ack."}""")
                            }

                            override suspend fun stream(
                                messages: List<TextMessage>,
                                preview: (String) -> Unit,
                            ): TextResult = error("unexpected")
                        }
                    },
                )
            }
        f.onboard()
        f.core.submit(
            UserIntent.SavePersona(
                null,
                "Local speaker",
                com.magichandy.mobile.core.api.Personality(
                    com.magichandy.mobile.core.api.Explicitness.Warm,
                    description = "Speaking fixture.",
                ),
                use = true,
            )
        )
        f.send(UserIntent.SetMemory(true))
        f.send(UserIntent.SendMessage("Remember that blue is my sample color."))
        assertEquals(listOf("blue is my sample color."), f.core.state.value.settings.memories)
        f.send(UserIntent.SendMessage("A previous fixture turn."))
        refuse = true
        f.send(UserIntent.SendMessage("The current fixture turn."))
        assertIs<ReplyState.Interrupted>(f.core.state.value.chat.reply)
        f.core.setRefusalFallback(alternate)
        runCurrent()
        f.send(UserIntent.SendAgain)
        assertEquals(
            "Local speaker",
            (f.core.state.value.chat.entries.last()
                    as com.magichandy.mobile.core.api.ChatEntry.Message)
                .speaker,
        )
        val messages = localCalls.single().messages
        assertTrue(messages.first().text.contains("Speaking fixture."))
        assertTrue(messages.first().text.contains("Local speaker"))
        val memory = messages.any {
            it.role == TextRole.Developer && it.text.contains("blue is my sample color.")
        }
        val history = messages.any {
            it.role == TextRole.User && it.text.contains("A previous fixture turn.")
        }
        assertFalse(memory)
        assertFalse(history)
        assertEquals(1, messages.count { it.role == TextRole.User })
        invalid = true
        val before = localCalls.size
        f.send(UserIntent.SendMessage("Another request."))
        assertEquals(before + 1, localCalls.size)
        f.scope.cancel()
    }

    @Test
    fun `streaming preview resets between hosted refusal and local retry and blocks stale or revoked text`() =
        runTest {
            val primary = ConnectionId("host")
            val alternate = ConnectionId("local")
            val host =
                ModelConnection(
                    primary,
                    "Hosted",
                    ProviderKind.OpenRouter,
                    "host",
                    "Host",
                    CapabilityLevel.Advanced,
                    true,
                    true,
                    ConnectionStatus.Ready,
                )
            var primaryGate: CompletableDeferred<Unit>? = null
            var localStart: CompletableDeferred<Unit>? = null
            var localFinish: CompletableDeferred<Unit>? = null
            var primaryPreview: ((String) -> Unit)? = null
            var localPreview: ((String) -> Unit)? = null
            var localCancelled = false
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        models =
                            InMemoryModels(
                                ModelCatalog(
                                    mapOf(
                                        primary to SavedModel(host),
                                        alternate to
                                            SavedModel(
                                                host.copy(
                                                    id = alternate,
                                                    provider = ProviderKind.Ollama,
                                                    capability = CapabilityLevel.Simple,
                                                )
                                            ),
                                    ),
                                    primary,
                                )
                            ),
                        provider = { m ->
                            object : StructuredProvider {
                                override suspend fun complete(
                                    request: ModelRequest,
                                    preview: (String) -> Unit,
                                ): TextResult =
                                    if (m.info.id == primary) {
                                        primaryPreview = preview
                                        preview("""{"reply":"Refusal preview """)
                                        primaryGate?.await()
                                        preview("buffered tail")
                                        TextResult.Refused
                                    } else {
                                        localPreview = preview
                                        try {
                                            localStart?.await()
                                            preview("""{"reply":"Local """)
                                            localFinish?.await()
                                            preview("""answer."}""")
                                        } catch (stop: CancellationException) {
                                            localCancelled = true
                                            throw stop
                                        }
                                        TextResult.Completed("""{"reply":"Local answer."}""")
                                    }

                                override suspend fun stream(
                                    messages: List<TextMessage>,
                                    preview: (String) -> Unit,
                                ): TextResult = error("unexpected")
                            }
                        },
                    )
                }
            f.onboard()
            f.core.submit(
                UserIntent.SavePersona(null, "Kit", Personality(Explicitness.Warm), use = true)
            )
            f.core.setRefusalFallback(alternate)
            runCurrent()

            val firstPrimary = CompletableDeferred<Unit>()
            val firstLocalStart = CompletableDeferred<Unit>()
            val firstLocalFinish = CompletableDeferred<Unit>()
            primaryGate = firstPrimary
            localStart = firstLocalStart
            localFinish = firstLocalFinish
            f.send(UserIntent.SendMessage("Try local"))
            assertEquals(
                ReplyState.Streaming("Refusal preview ", "Kit"),
                f.core.state.value.chat.reply,
            )

            firstPrimary.complete(Unit)
            runCurrent()
            assertEquals(ReplyState.Streaming("", "Kit"), f.core.state.value.chat.reply)
            primaryPreview?.invoke("stale hosted")
            assertEquals(ReplyState.Streaming("", "Kit"), f.core.state.value.chat.reply)

            firstLocalStart.complete(Unit)
            runCurrent()
            assertEquals(ReplyState.Streaming("Local ", "Kit"), f.core.state.value.chat.reply)
            primaryPreview?.invoke("more stale")
            assertEquals(ReplyState.Streaming("Local ", "Kit"), f.core.state.value.chat.reply)

            firstLocalFinish.complete(Unit)
            runCurrent()
            assertEquals(ReplyState.Idle, f.core.state.value.chat.reply)
            val committed = assertIs<ChatEntry.Message>(f.core.state.value.chat.entries.last())
            assertEquals("Local answer.", committed.text)
            assertEquals("Kit", committed.speaker)

            primaryGate = null
            localStart = CompletableDeferred()
            localFinish = null
            f.send(UserIntent.SendMessage("Revoke during local"))
            assertEquals(ReplyState.Streaming("", "Kit"), f.core.state.value.chat.reply)
            f.core.setRefusalFallback(null)
            runCurrent()
            assertTrue(localCancelled)
            assertEquals(
                ReplyState.Interrupted(InterruptCause.ProviderError),
                f.core.state.value.chat.reply,
            )
            localPreview?.invoke("""{"reply":"late local"}""")
            assertEquals(
                ReplyState.Interrupted(InterruptCause.ProviderError),
                f.core.state.value.chat.reply,
            )

            f.core.setRefusalFallback(alternate)
            runCurrent()
            localStart = CompletableDeferred()
            f.send(UserIntent.SendMessage("Stop during local"))
            assertEquals(ReplyState.Streaming("", "Kit"), f.core.state.value.chat.reply)
            f.core.emergencyStop(StopSource.AppFooter)
            runCurrent()
            assertEquals(
                ReplyState.Interrupted(InterruptCause.MotionStopped),
                f.core.state.value.chat.reply,
            )
            localPreview?.invoke("""{"reply":"after stop"}""")
            assertEquals(
                ReplyState.Interrupted(InterruptCause.MotionStopped),
                f.core.state.value.chat.reply,
            )
            f.scope.cancel()
        }

    @Test
    fun `explicit Remember persists after hosted refusal fallback while prior memories stay excluded`() =
        runTest {
            var refuse = false
            val localCalls = mutableListOf<ModelRequest>()
            val primary = ConnectionId("primary")
            val alternate = ConnectionId("alternate")
            val f =
                DebugFixture(this) { d ->
                    val prototype = d.models.state.value.models.values.single().info
                    val catalog =
                        ModelCatalog(
                            mapOf(
                                primary to
                                    SavedModel(
                                        prototype.copy(
                                            id = primary,
                                            provider = ProviderKind.OpenRouter,
                                        )
                                    ),
                                alternate to
                                    SavedModel(
                                        prototype.copy(
                                            id = alternate,
                                            provider = ProviderKind.Ollama,
                                            capability = CapabilityLevel.Simple,
                                        )
                                    ),
                            ),
                            primary,
                        )
                    d.copy(
                        models = InMemoryModels(catalog),
                        provider = { m ->
                            object : StructuredProvider {
                                override suspend fun complete(
                                    request: ModelRequest,
                                    preview: (String) -> Unit,
                                ): TextResult {
                                    if (m.info.id == primary && refuse) return TextResult.Refused
                                    if (m.info.id == alternate) localCalls += request
                                    return TextResult.Completed("""{"reply":"Noted."}""")
                                }

                                override suspend fun stream(
                                    messages: List<TextMessage>,
                                    preview: (String) -> Unit,
                                ): TextResult = error("unexpected")
                            }
                        },
                    )
                }
            f.onboard()
            f.core.submit(
                UserIntent.SavePersona(
                    null,
                    "Kit",
                    Personality(Explicitness.Warm, description = "Direct."),
                    use = true,
                )
            )
            f.send(UserIntent.SetMemory(true))
            f.send(UserIntent.SendMessage("Remember that earlier preference."))
            assertEquals(listOf("earlier preference."), f.core.state.value.settings.memories)

            f.core.setRefusalFallback(alternate)
            runCurrent()
            refuse = true
            f.send(UserIntent.SendMessage("Remember that new preference."))

            val localMessages = localCalls.single().messages
            assertFalse(localMessages.any { it.text.contains("earlier preference.") })
            assertEquals(1, localMessages.count { it.role == TextRole.User })
            assertTrue(localMessages.first().text.contains("Kit"))
            assertTrue(localMessages.first().text.contains("Direct."))
            assertEquals(
                listOf("new preference.", "earlier preference."),
                f.core.state.value.settings.memories,
            )

            f.send(UserIntent.SetMemory(false))
            f.send(UserIntent.SendMessage("Remember that ignored preference."))
            assertEquals(
                listOf("new preference.", "earlier preference."),
                f.core.state.value.settings.memories,
            )
            f.scope.cancel()
        }
}
