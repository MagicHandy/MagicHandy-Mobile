package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.ConnectionId
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceLimit
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.SignInFailure
import com.magichandy.mobile.core.api.SignInStage
import com.magichandy.mobile.core.api.SignInState
import com.magichandy.mobile.core.api.StepDirection
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.BridgeSelectionRequired
import com.magichandy.mobile.core.ports.BridgeTarget
import com.magichandy.mobile.core.ports.DeviceConfiguration
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.ModelSignIn
import com.magichandy.mobile.core.ports.ModelStore
import com.magichandy.mobile.core.ports.SavedModel
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class ConfigurationBoundaryTest {
    @Test
    fun `recycled discovery indices require the visible choice and a fresh unique identity`() =
        runTest {
            var identity = "first"
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        devices =
                            object : DeviceDirectory by d.devices {
                                override val configuration =
                                    object :
                                        DeviceConfiguration by requireNotNull(
                                            d.devices.configuration
                                        ) {
                                        override suspend fun bridgeTargets(address: String) =
                                            listOf(
                                                BridgeTarget(
                                                    7,
                                                    "Synthetic axis",
                                                    identity,
                                                    DeviceFamily.LinearActuator,
                                                )
                                            )
                                    }
                            }
                    )
                }
            f.onboard()
            val address = "ws://LOCALHOST:12345"
            val first = f.core.intifaceLinearActuators(address).single()
            identity = "second"
            val second = f.core.intifaceLinearActuators(address).single()
            val action = UserIntent.AddIntifaceLinear(address, 7, 0)
            assertEquals(FormResult.Rejected(FormReason.ChooseDevice), f.core.submit(action))
            assertEquals(
                FormResult.Rejected(FormReason.ChooseDevice),
                f.core.submit(action.copy(selection = first.selection)),
            )
            assertEquals(
                FormResult.Accepted,
                f.core.submit(action.copy(selection = second.selection)),
            )
            assertEquals(
                FormResult.Rejected(FormReason.Duplicate),
                f.core.submit(
                    action.copy(address = "ws://localhost:12345/", selection = second.selection)
                ),
            )
            f.scope.cancel()
        }

    @Test
    fun `a failed database deletion restores the credential and the same form can retry`() =
        runTest {
            var fail = true
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        models =
                            object : ModelStore by d.models {
                                override suspend fun remove(id: ConnectionId) {
                                    if (fail) throw IOException("synthetic")
                                    d.models.remove(id)
                                }
                            }
                    )
                }
            f.onboard()
            val key = Secret("synthetic-form-key")
            assertEquals(
                FormResult.Accepted,
                f.core.submit(
                    UserIntent.SaveOpenRouter(key, "fixture", CapabilityLevel.Simple, false)
                ),
            )
            val row =
                f.d.models.state.value.models.values.single {
                    it.info.provider == ProviderKind.OpenRouter
                }
            val handle = requireNotNull(row.credential)
            assertEquals(
                FormResult.Rejected(FormReason.StorageUnavailable),
                f.core.submit(UserIntent.RemoveConnection(row.info.id)),
            )
            assertEquals(key, f.d.vault.get(handle))
            assertNotNull(f.d.models.state.value.models[row.info.id])
            fail = false
            assertEquals(
                FormResult.Accepted,
                f.core.submit(UserIntent.RemoveConnection(row.info.id)),
            )
            assertNull(f.d.vault.get(handle))
            f.scope.cancel()
        }

    @Test
    fun `the published tile observation is initially unknown and duplicate callbacks do not rewrite settings`() =
        runTest {
            val f = DebugFixture(this)
            assertNull(f.core.state.value.app.isStopTileAdded)
            f.core.updateStopTileAdded(true)
            runCurrent()
            assertEquals(true, f.core.state.value.app.isStopTileAdded)
            val revision = f.d.settings.state.value.revision
            repeat(3) { f.core.updateStopTileAdded(true) }
            runCurrent()
            assertEquals(revision, f.d.settings.state.value.revision)
            f.core.updateStopTileAdded(false)
            runCurrent()
            assertEquals(false, f.core.state.value.app.isStopTileAdded)
            f.scope.cancel()
        }

    @Test
    fun `unreachable bridge and a full form lane return bounded actionable refusals`() = runTest {
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val f =
            DebugFixture(this) { d ->
                d.copy(
                    devices =
                        object : DeviceDirectory by d.devices {
                            override val configuration =
                                object :
                                    DeviceConfiguration by requireNotNull(d.devices.configuration) {
                                    override suspend fun bridgeTargets(
                                        address: String
                                    ): List<BridgeTarget> {
                                        first.complete(Unit)
                                        release.await()
                                        throw IOException("synthetic sensitive reason")
                                    }
                                }
                        }
                )
            }
        f.onboard()
        val pending = async { f.core.submit(UserIntent.AddIntiface("ws://127.0.0.1:12345")) }
        first.await()
        val others =
            List(32) {
                async(start = CoroutineStart.UNDISPATCHED) {
                    f.core.submit(UserIntent.Remove(DeviceId("missing")))
                }
            }
        assertEquals(
            FormResult.Rejected(FormReason.Busy),
            f.core.submit(UserIntent.Remove(DeviceId("last"))),
        )
        release.complete(Unit)
        assertEquals(FormResult.Rejected(FormReason.Unreachable), pending.await())
        assertTrue(others.awaitAll().all { it == FormResult.Rejected(FormReason.NotFound) })
        f.scope.cancel()
    }

    @Test
    fun `identical device descriptions explain how to distinguish both devices`() = runTest {
        val address = "ws://127.0.0.1:12345/"
        val f =
            DebugFixture(this) { d ->
                val records = requireNotNull(d.devices.configuration)
                d.copy(
                    devices =
                        object : DeviceDirectory by d.devices {
                            override val configuration =
                                object : DeviceConfiguration by records {
                                    override suspend fun bridgeTargets(address: String) =
                                        listOf(2, 7).map {
                                            BridgeTarget(
                                                it,
                                                "Same Device",
                                                "same-description/linear/0",
                                                DeviceFamily.LinearActuator,
                                            )
                                        }
                                }
                        }
                )
            }
        f.onboard()
        val choices = f.core.intifaceLinearActuators(address)
        assertEquals(2, choices.size)
        val picked = choices.last()
        val result =
            f.core.submit(
                UserIntent.AddIntifaceLinear(
                    address,
                    picked.deviceIndex,
                    picked.actuatorIndex,
                    picked.selection,
                )
            )
        assertEquals(FormResult.Rejected(FormReason.AmbiguousDevice), result)
        f.scope.cancel()
    }

    @Test
    fun `reselecting a uniquely renamed bridge device preserves its saved identity and limits`() =
        runTest {
            val id = DeviceId("vibrator")
            val address = "ws://127.0.0.1:12345/"
            val renamed = BridgeTarget(7, "Renamed device", "renamed-scalar", DeviceFamily.Vibrator)
            val fixture =
                DebugFixture(this) { dependencies ->
                    val directory = dependencies.devices
                    val records = requireNotNull(directory.configuration)
                    dependencies.copy(
                        devices =
                            object : DeviceDirectory by directory {
                                override val configuration =
                                    object : DeviceConfiguration by records {
                                        override suspend fun bridgeTargets(address: String) =
                                            listOf(renamed)
                                    }

                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: CoroutineScope,
                                ): DeviceTransport? {
                                    if (
                                        saved.single { it.ref.id == device }.bridgeIdentity !=
                                            renamed.identity
                                    )
                                        throw BridgeSelectionRequired()
                                    return directory.connect(device, ownerScope)
                                }
                            }
                    )
                }
            fixture.onboard()
            fixture.send(
                UserIntent.StepDeviceLimit(id, DeviceLimit.VibrationMaximum, StepDirection.Down)
            )
            val previous = fixture.devices.saved.single { it.ref.id == id }
            val ids = fixture.devices.saved.map { it.ref.id }
            fixture.connect(id)
            assertEquals(
                DeviceStatus.NeedsSetup,
                fixture.core.state.value.devices.devices.single { it.id == id }.status,
            )
            val choice = fixture.core.intifaceVibrators(address).single()
            assertEquals(
                FormResult.Accepted,
                fixture.core.submit(
                    UserIntent.AddIntiface(
                        address,
                        choice.deviceIndex,
                        choice.selection,
                        replaceDevice = id,
                    )
                ),
            )
            runCurrent()
            val saved = fixture.devices.saved.single { it.ref.id == id }
            assertEquals(previous.settings, saved.settings)
            assertEquals(renamed.identity, saved.bridgeIdentity)
            assertEquals(ids, fixture.devices.saved.map { it.ref.id })
            fixture.connect(id)
            assertEquals(
                DeviceStatus.Connected,
                fixture.core.state.value.devices.devices.single { it.id == id }.status,
            )
            fixture.scope.cancel()
        }

    @Test
    fun `setup observes a second successful sign-in to an existing connection`() = runTest {
        var result: SignInState.Failed? = null
        val fixture = DebugFixture(this) { d -> d.copy(signIn = ModelSignIn { result }) }
        fixture.onboard()
        repeat(2) { index ->
            fixture.send(UserIntent.StartChatGptSignIn)
            assertEquals((index + 1).toLong(), fixture.core.state.value.models.completedSignIns)
            assertEquals(
                1,
                fixture.core.state.value.models.connections.count {
                    it.provider == ProviderKind.ChatGpt
                },
            )
        }
        result = SignInState.Failed(SignInFailure.NetworkError, SignInStage.CodeExchange, 503)
        fixture.send(UserIntent.StartChatGptSignIn)
        assertEquals(2L, fixture.core.state.value.models.completedSignIns)
        assertEquals(result, fixture.core.state.value.models.signIn)
        fixture.scope.cancel()
    }

    @Test
    fun `a catalog write failure after verification is a local save error`() = runTest {
        val fixture =
            DebugFixture(this) { d ->
                d.copy(
                    signIn = ModelSignIn { null },
                    models =
                        object : ModelStore by d.models {
                            override suspend fun save(model: SavedModel, selectForChat: Boolean) {
                                throw IOException("fixture write failure")
                            }
                        },
                )
            }
        fixture.onboard()
        fixture.send(UserIntent.StartChatGptSignInForChat)
        val models = fixture.core.state.value.models
        assertEquals(
            SignInState.Failed(SignInFailure.StorageUnavailable, SignInStage.SavingConnection),
            models.signIn,
        )
        assertEquals(0L, models.completedSignIns)
        assertTrue(models.connections.none { it.provider == ProviderKind.ChatGpt })
        fixture.scope.cancel()
    }
}
