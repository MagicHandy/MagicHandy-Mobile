package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.BridgeDiscovery
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.FormReason
import com.magichandy.mobile.core.api.IntifaceChoice
import com.magichandy.mobile.core.api.ProviderKind
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.ScanResultStatus
import com.magichandy.mobile.core.api.ScanState
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.BridgeTarget
import com.magichandy.mobile.core.ports.DeviceConfiguration
import com.magichandy.mobile.core.ports.DeviceDirectory
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

internal class SetupTest {
    @Test
    fun `a failed bridge lookup and a successful empty listing have different results`() = runTest {
        var available = false
        val f =
            DebugFixture(this) { dependencies ->
                val base = requireNotNull(dependencies.devices.configuration)
                dependencies.copy(
                    devices =
                        object : DeviceDirectory by dependencies.devices {
                            override val configuration =
                                object : DeviceConfiguration by base {
                                    override suspend fun bridgeTargets(
                                        address: String
                                    ): List<BridgeTarget> {
                                        assertEquals("ws://fixture.invalid/", address)
                                        if (!available)
                                            throw IOException("Simulated unreachable bridge")
                                        return emptyList()
                                    }
                                }
                        }
                )
            }
        f.onboard()
        val url = "WS://Fixture.invalid:80"
        assertEquals(
            BridgeDiscovery.Failed(FormReason.Unreachable),
            f.core.discoverIntifaceVibrators(url),
        )
        available = true
        assertEquals(BridgeDiscovery.Found(emptyList()), f.core.discoverIntifaceVibrators(url))
        f.scope.cancel()
    }

    @Test
    fun `bridge choices require consent and are read without adding or moving a device`() =
        runTest {
            val f = DebugFixture(this)
            assertTrue(f.core.intifaceVibrators("ws://127.0.0.1:12345").isEmpty())
            f.onboard()
            val rows = f.core.state.value.devices.devices
            assertTrue(f.core.intifaceVibrators("https://invalid").isEmpty())
            assertEquals(
                listOf(IntifaceChoice(0, "Simulated vibrator", "sim-vibration")),
                f.core.intifaceVibrators("127.0.0.1:12345"),
            )
            assertEquals(rows, f.core.state.value.devices.devices)
            assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
            f.scope.cancel()
        }

    @Test
    fun `Coyote scan requires notes and verification connects only the selected row without playback`() =
        runTest {
            val f = DebugFixture(this)
            f.onboard()
            f.send(UserIntent.StartScan(DeviceFamily.Coyote))
            assertEquals(ScanState.Idle, f.core.state.value.devices.scan)
            f.send(UserIntent.AcknowledgeCoyoteNotes)
            f.send(UserIntent.StartScan(DeviceFamily.Coyote))
            val hit = assertIs<ScanState.Scanning>(f.core.state.value.devices.scan).results.single()
            f.send(UserIntent.ConnectScanResult(hit.id, "Synthetic unit"))
            assertEquals(
                ScanResultStatus.Verified,
                assertIs<ScanState.Scanning>(f.core.state.value.devices.scan)
                    .results
                    .single()
                    .status,
            )
            val row = f.core.state.value.devices.devices.single { it.name == "Synthetic unit" }
            assertEquals(DeviceStatus.Connected, row.status)
            assertNull(f.d.journal.read())
            assertEquals(0, f.devices.output.value.getValue(row.id).batches)
            f.send(UserIntent.StopScan)
            assertEquals(ScanState.Idle, f.core.state.value.devices.scan)
            f.scope.cancel()
        }

    @Test
    fun `cloud and OpenRouter setup keep credentials behind separate removable handles`() =
        runTest {
            val f = DebugFixture(this)
            f.onboard()
            val before = f.core.state.value.devices.devices
            f.send(UserIntent.AddCloudHandy("Invalid", Secret("bad")))
            assertEquals(before, f.core.state.value.devices.devices)
            f.send(UserIntent.AddCloudHandy("First", Secret("Synthetic1")))
            val disk = f.devices.saved.single { it.ref.name == "First" }
            assertEquals(Secret("Synthetic1"), f.d.vault.get(requireNotNull(disk.credential)))
            f.send(UserIntent.Rename(disk.ref.id, "Second"))
            assertTrue(f.core.state.value.devices.devices.any { it.name == "Second" })
            f.send(UserIntent.Remove(disk.ref.id))
            assertNull(f.d.vault.get(requireNotNull(disk.credential)))
            f.send(
                UserIntent.SaveOpenRouter(
                    Secret("fixture"),
                    "example/model",
                    CapabilityLevel.Advanced,
                    true,
                )
            )
            val model =
                f.d.models.state.value.models.values.single {
                    it.info.provider == ProviderKind.OpenRouter
                }
            assertEquals(Secret("fixture"), f.d.vault.get(requireNotNull(model.credential)))
            f.send(UserIntent.RemoveConnection(model.info.id))
            assertNull(f.d.vault.get(requireNotNull(model.credential)))
            f.scope.cancel()
        }

    /** Keeps discovery open like a real radio scan, and can refuse the first connections. */
    private class HandyRadio(base: DeviceDirectory) : DeviceDirectory by base {
        var scanning = false
        var refusals = 0
        private val directory = base
        private val records = requireNotNull(base.configuration)

        override val configuration =
            object : DeviceConfiguration by records {
                override fun scan(family: DeviceFamily) = flow {
                    scanning = true
                    try {
                        emitAll(records.scan(family))
                        awaitCancellation()
                    } finally {
                        scanning = false
                    }
                }
            }

        override suspend fun connect(device: DeviceId, ownerScope: CoroutineScope) =
            if (refusals-- > 0) throw IOException("Synthetic GATT status 133")
            else directory.connect(device, ownerScope)
    }

    @Test
    fun `a Bluetooth Handy verifies on the firmware's blank Stop reply and discovery ends first`() =
        runTest {
            lateinit var radio: HandyRadio
            val f =
                DebugFixture(this) {
                    radio = HandyRadio(it.devices)
                    it.copy(devices = radio)
                }
            f.onboard()
            f.send(UserIntent.StartScan(DeviceFamily.Handy))
            assertTrue(radio.scanning)
            val hit = assertIs<ScanState.Scanning>(f.core.state.value.devices.scan).results.single()
            f.send(UserIntent.ConnectScanResult(hit.id, "Bedroom Handy"))
            assertTrue(!radio.scanning)
            assertEquals(
                ScanResultStatus.Verified,
                assertIs<ScanState.Scanning>(f.core.state.value.devices.scan)
                    .results
                    .single()
                    .status,
            )
            val row = f.core.state.value.devices.devices.single { it.name == "Bedroom Handy" }
            assertEquals(DeviceStatus.Connected, row.status)
            assertEquals(0, f.devices.output.value.getValue(row.id).batches)
            f.scope.cancel()
        }

    @Test
    fun `a scan result that couldn't be verified can be retried from the same list`() = runTest {
        lateinit var radio: HandyRadio
        val f =
            DebugFixture(this) {
                radio = HandyRadio(it.devices).apply { refusals = 1 }
                it.copy(devices = radio)
            }
        f.onboard()
        f.send(UserIntent.StartScan(DeviceFamily.Handy))
        val hit = assertIs<ScanState.Scanning>(f.core.state.value.devices.scan).results.single()
        fun status() =
            assertIs<ScanState.Scanning>(f.core.state.value.devices.scan).results.single().status
        f.send(UserIntent.ConnectScanResult(hit.id, "Bedroom Handy"))
        assertEquals(ScanResultStatus.Failed, status())
        f.send(UserIntent.ConnectScanResult(hit.id, "Bedroom Handy"))
        assertEquals(ScanResultStatus.Verified, status())
        assertEquals(
            DeviceStatus.Connected,
            f.core.state.value.devices.devices.single { it.name == "Bedroom Handy" }.status,
        )
        assertEquals(1, f.devices.saved.count { it.ref.name == "Bedroom Handy" })
        f.scope.cancel()
    }
}
