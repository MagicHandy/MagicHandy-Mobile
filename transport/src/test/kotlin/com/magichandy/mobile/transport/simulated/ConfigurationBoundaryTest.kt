package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

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
}
