package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.core.testing.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
internal class RefusalAndEdgeTest {
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
                                        waiting?.await()
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
}
