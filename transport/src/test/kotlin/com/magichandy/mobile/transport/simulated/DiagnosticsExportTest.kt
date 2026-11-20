package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiagnosticsExportTest {
    @Test
    fun `report retains Stop evidence after dismissal using only permitted fields`() = runTest {
        val secret = "private-marker-47"
        val f =
            DebugFixture(this) { d ->
                d.copy(
                    versionName = "1.2.3",
                    androidApi = 36,
                    diagnostics =
                        object : DiagnosticsSink {
                            override suspend fun record(at: Instant, kind: DiagnosticKind) {}

                            override suspend fun recent(limit: Int) =
                                List(5000) {
                                    DiagnosticEvent(
                                        Instant.fromEpochMilliseconds(it.toLong()),
                                        DiagnosticKind.AccountRejected,
                                    )
                                }
                        },
                )
            }
        f.onboard()
        f.core.submit(UserIntent.Rename(DeviceId("vibrator"), secret))
        f.core.submit(UserIntent.AddCloudHandy(secret, Secret("Key123456private")))
        f.send(UserIntent.SetMemory(true))
        f.send(UserIntent.SendMessage("Remember that " + secret))
        f.connect(DeviceId("vibrator"))
        f.send(UserIntent.StartMotion)
        val active = f.core.diagnosticsReport()
        assertContains(active.text, "Run journal armed")
        f.core.emergencyStop(StopSource.Notification)
        runCurrent()
        f.send(UserIntent.ReconcileDevice(DeviceId("vibrator")))
        f.send(UserIntent.DismissStopReceipt)
        val report = f.core.diagnosticsReport()
        for (text in listOf(active.text, report.text)) {
            assertTrue(text.encodeToByteArray().size <= 256 * 1024)
            assertFalse(secret in text)
            assertFalse("Key123456private" in text)
            assertFalse("127.0.0.1" in text)
            assertFalse("simulated-model" in text)
            assertContains(text, "App version 1.2.3")
            assertContains(text, "Android API 36")
            assertEquals(2048, text.lines().count { it.endsWith("AccountRejected") })
        }
        assertContains(report.text, "BridgeOnly")
        assertTrue(report.fileName.matches(Regex("MagicHandy-diagnostics-[0-9]+[.]txt")))
        f.scope.cancel()
    }
}
