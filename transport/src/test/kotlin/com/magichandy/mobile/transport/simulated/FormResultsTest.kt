package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.FormIntent
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.MagicHandyCore
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.ServerCheck
import com.magichandy.mobile.core.api.ServerCheckFailure
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.ModelServers
import com.magichandy.mobile.core.ports.ModelSignIn
import com.magichandy.mobile.core.ports.ModelStore
import com.magichandy.mobile.core.ports.SavedModel
import com.magichandy.mobile.core.ports.SecretHandle
import com.magichandy.mobile.core.ports.ServerAssessment
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class FormResultsTest {
    private suspend fun MagicHandyCore.refuses(intent: FormIntent, reason: FormReason) {
        assertEquals(FormResult.Rejected(reason), submit(intent))
    }

    @Test
    fun `each device form receives its own acceptance or typed refusal`() = runTest {
        val f = DebugFixture(this)
        val c = f.core
        val handy = UserIntent.AddCloudHandy("Second", Secret("Synthetic123"))
        c.refuses(handy, FormReason.ConsentRequired)
        f.onboard()
        c.refuses(handy.copy(connectionKey = Secret("bad")), FormReason.InvalidKey)
        c.refuses(handy.copy(name = "a".repeat(65)), FormReason.InvalidName)
        assertEquals(FormResult.Accepted, c.submit(handy))
        c.refuses(handy, FormReason.Duplicate)
        val token =
            UserIntent.AddAutoblowDevice(DeviceFamily.VacuGlide, "Test", Secret("synthetic-token"))
        c.refuses(token.copy(family = DeviceFamily.Coyote), FormReason.Unsupported)
        c.refuses(token.copy(deviceToken = Secret("bad token")), FormReason.InvalidKey)
        assertEquals(FormResult.Accepted, c.submit(token))
        c.refuses(token, FormReason.Duplicate)
        val linear = UserIntent.AddIntifaceLinear("ws://127.0.0.1:12345")
        c.refuses(linear.copy(address = "http://example.invalid"), FormReason.InvalidAddress)
        c.refuses(linear.copy(deviceIndex = 275), FormReason.ChooseDevice)
        assertEquals(FormResult.Accepted, c.submit(linear))
        c.refuses(linear, FormReason.Duplicate)
        assertEquals(FormResult.Accepted, c.submit(UserIntent.Remove(DeviceId("vibrator"))))
        val vib = UserIntent.AddIntiface(linear.address)
        assertEquals(FormResult.Accepted, c.submit(vib))
        c.refuses(vib, FormReason.Duplicate)
        val id = c.state.value.devices.devices.single { it.name == "Second" }.id
        c.refuses(UserIntent.Rename(id, " "), FormReason.InvalidName)
        c.refuses(UserIntent.Rename(id, "Test"), FormReason.Duplicate)
        assertEquals(FormResult.Accepted, c.submit(UserIntent.Rename(id, "Changed")))
        assertEquals(FormResult.Accepted, c.submit(UserIntent.Remove(id)))
        c.refuses(UserIntent.Remove(id), FormReason.NotFound)
        c.refuses(UserIntent.Rename(id, "Gone"), FormReason.NotFound)
        val bridge =
            c.state.value.devices.devices.first { it.family == DeviceFamily.LinearActuator }.id
        f.connect(bridge)
        f.send(UserIntent.StartMotion)
        c.refuses(UserIntent.Remove(bridge), FormReason.RunInProgress)
        val model = requireNotNull(c.state.value.models.chatConnection)
        c.refuses(UserIntent.RemoveConnection(model), FormReason.RunInProgress)
        c.emergencyStop(StopSource.AppFooter)
        runCurrent()
        c.refuses(UserIntent.Remove(bridge), FormReason.ReconcileDevice)
        f.send(UserIntent.ReconcileDevice(bridge))
        assertEquals(FormResult.Accepted, c.submit(UserIntent.Remove(bridge)))
        assertEquals(FormResult.Accepted, c.submit(UserIntent.RemoveConnection(model)))
        c.refuses(UserIntent.RemoveConnection(model), FormReason.NotFound)
        f.scope.cancel()
    }

    @Test
    fun `LAN and router forms distinguish readiness refusal duplicate key syntax and storage failure`() =
        runTest {
            val name = "synthetic"
            val addr = "http://127.0.0.1:9000"
            var result = ServerAssessment(ServerCheck.Failed(addr, ServerCheckFailure.Unreachable))
            var io = false
            val port =
                object : ModelServers {
                    override suspend fun check(
                        address: String,
                        model: String?,
                        level: CapabilityLevel?,
                    ) = result.check

                    override suspend fun verify(
                        address: String,
                        model: String,
                        level: CapabilityLevel,
                    ) = result
                }
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        servers = port,
                        models =
                            object : ModelStore by d.models {
                                override suspend fun save(model: SavedModel) {
                                    if (io) throw IOException("synthetic confidential detail")
                                    d.models.save(model)
                                }
                            },
                    )
                }
            f.onboard()
            val c = f.core
            val save = UserIntent.SaveServer("Server", addr, name, CapabilityLevel.Simple)
            c.refuses(save, FormReason.Unreachable)
            result = result.copy(refused = true)
            c.refuses(save, FormReason.ProviderRefused)
            result =
                ServerAssessment(ServerCheck.Failed(addr, ServerCheckFailure.NotPrivateAddress))
            c.refuses(save, FormReason.InvalidAddress)
            result = ServerAssessment(ServerCheck.Failed(addr, ServerCheckFailure.NoModels))
            c.refuses(save, FormReason.InvalidModel)
            result = ServerAssessment(ServerCheck.Failed(addr, ServerCheckFailure.IncompleteReply))
            c.refuses(save, FormReason.IncompleteModelReply)
            result =
                ServerAssessment(
                    ServerCheck.Found(
                        addr,
                        ProviderKind.Ollama,
                        false,
                        listOf(name),
                        name,
                        CapabilityLevel.Simple,
                        null,
                        16384,
                        null,
                        Duration.ZERO,
                    )
                )
            assertEquals(FormResult.Accepted, c.submit(save))
            c.refuses(save, FormReason.Duplicate)
            val router =
                UserIntent.SaveOpenRouter(
                    Secret("synthetic-key"),
                    "fixture/model",
                    CapabilityLevel.Advanced,
                    false,
                )
            c.refuses(router.copy(apiKey = Secret("bad key")), FormReason.InvalidKey)
            c.refuses(router.copy(model = ""), FormReason.InvalidModel)
            io = true
            c.refuses(router, FormReason.StorageUnavailable)
            io = false
            assertEquals(FormResult.Accepted, c.submit(router))
            c.refuses(router, FormReason.Duplicate)
            f.scope.cancel()
        }

    @Test
    fun `waiting for form IO cannot delay a direct Stop or attribute a result to another form`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        servers =
                            ModelServers { address, _, _ ->
                                entered.complete(Unit)
                                finish.await()
                                ServerCheck.Failed(address, ServerCheckFailure.Unreachable)
                            }
                    )
                }
            f.onboard()
            f.connect(DeviceId("handy"))
            f.send(UserIntent.StartMotion)
            val work = async {
                f.core.submit(
                    UserIntent.SaveServer(
                        "X",
                        "http://127.0.0.1:9000",
                        "fixture",
                        CapabilityLevel.Simple,
                    )
                )
            }
            entered.await()
            val other = async { f.core.submit(UserIntent.Remove(DeviceId("missing"))) }
            f.core.emergencyStop(StopSource.Notification)
            runCurrent()
            assertTrue(requireNotNull(f.core.state.value.stopReceipt).isAllConfirmed)
            assertFalse(work.isCompleted)
            finish.complete(Unit)
            assertEquals(FormResult.Rejected(FormReason.Unreachable), work.await())
            assertEquals(FormResult.Rejected(FormReason.NotFound), other.await())
            f.scope.cancel()
        }

    @Test
    fun `selecting newly saved ChatGPT OpenRouter or LAN models for chat replaces an older default while Models saves preserve it`() =
        runTest {
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        signIn = ModelSignIn { null },
                        signInCredential = SecretHandle("synthetic-chatgpt"),
                        servers =
                            ModelServers { address, selected, capability ->
                                val model = selected ?: "fixture-lan"
                                val level = capability ?: CapabilityLevel.Simple
                                ServerCheck.Found(
                                    address,
                                    ProviderKind.Ollama,
                                    false,
                                    listOf(model),
                                    model,
                                    level,
                                    null,
                                    16384,
                                    null,
                                    Duration.ZERO,
                                )
                            },
                    )
                }
            f.onboard()
            val c = f.core
            val initialDefault = requireNotNull(c.state.value.models.chatConnection)
            assertEquals(ConnectionId("simulated-model"), initialDefault)

            assertEquals(
                FormResult.Accepted,
                c.submit(
                    UserIntent.SaveOpenRouter(
                        Secret("synthetic-key-models"),
                        "fixture/models-router",
                        CapabilityLevel.Advanced,
                        false,
                    )
                ),
            )
            assertEquals(initialDefault, c.state.value.models.chatConnection)

            assertEquals(
                FormResult.Accepted,
                c.submit(
                    UserIntent.SaveOpenRouter(
                        Secret("synthetic-key-setup"),
                        "fixture/setup-router",
                        CapabilityLevel.Advanced,
                        false,
                        selectForChat = true,
                    )
                ),
            )
            val setupRouter =
                c.state.value.models.connections.single { it.model == "fixture/setup-router" }.id
            assertEquals(setupRouter, c.state.value.models.chatConnection)

            assertEquals(
                FormResult.Accepted,
                c.submit(
                    UserIntent.SaveServer(
                        "Models LAN",
                        "http://127.0.0.1:11434",
                        "fixture-lan-models",
                        CapabilityLevel.Simple,
                    )
                ),
            )
            assertEquals(setupRouter, c.state.value.models.chatConnection)

            assertEquals(
                FormResult.Accepted,
                c.submit(
                    UserIntent.SaveServer(
                        "Setup LAN",
                        "http://127.0.0.1:11435",
                        "fixture-lan-setup",
                        CapabilityLevel.Simple,
                        selectForChat = true,
                    )
                ),
            )
            val setupLan =
                c.state.value.models.connections.single { it.model == "fixture-lan-setup" }.id
            assertEquals(setupLan, c.state.value.models.chatConnection)

            f.send(UserIntent.StartChatGptSignIn)
            assertTrue(c.state.value.models.connections.any { it.id == ConnectionId("chatgpt") })
            assertEquals(setupLan, c.state.value.models.chatConnection)

            f.send(UserIntent.StartChatGptSignInForChat)
            assertEquals(ConnectionId("chatgpt"), c.state.value.models.chatConnection)

            assertEquals(
                FormResult.Accepted,
                c.submit(
                    UserIntent.SaveOpenRouter(
                        Secret("synthetic-key-choose-ai"),
                        "fixture/choose-ai-router",
                        CapabilityLevel.Advanced,
                        false,
                        selectForChat = true,
                    )
                ),
            )
            val chooseAiRouter =
                c.state.value.models.connections
                    .single { it.model == "fixture/choose-ai-router" }
                    .id
            assertEquals(chooseAiRouter, c.state.value.models.chatConnection)
            f.scope.cancel()
        }
}
