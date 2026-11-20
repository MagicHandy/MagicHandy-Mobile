package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.*
import kotlin.test.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

internal class SetupTest {
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
}
