package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.Explicitness
import com.magichandy.mobile.core.api.Personality
import com.magichandy.mobile.core.api.ReactionStyle
import com.magichandy.mobile.core.api.ReplyLength
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DiagnosticEvent
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.DiagnosticsSink
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class DiagnosticsExportTest {
    @Test
    fun `report includes only persona count and three enumerated wording settings`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        val name = "Sensitive synthetic speaker"
        val description = "Description marker\nStopRequested"
        val memory = "sensitive memory for the persona"
        val p =
            Personality(
                Explicitness.Intimate,
                ReactionStyle.Tender,
                description,
                ReplyLength.Detailed,
            )
        f.core.submit(UserIntent.SavePersona(null, name, p, use = true))
        f.core.submit(
            UserIntent.DuplicatePersona(f.core.state.value.settings.persona.id, "Private copy")
        )
        f.send(UserIntent.SetMemory(true))
        f.send(UserIntent.SendMessage("Remember that $memory"))
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf(memory), f.core.state.value.settings.memories)
        val report = f.core.diagnosticsReport()
        assertContains(report.text, "Personas 3\n")
        assertContains(report.text, "Active wording Intimate Tender Detailed\n")
        for (privateText in
            listOf(
                name,
                description,
                "Description marker",
                memory,
                "Private copy",
                f.core.state.value.settings.persona.id.value,
            )) {
            assertFalse(privateText in report.text || privateText in report.fileName)
        }
        assertTrue(report.text.encodeToByteArray().size <= 256 * 1024)
        f.scope.cancel()
    }

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

    @Test
    fun `phone test diagnostics identify the exact installed build`() = runTest {
        val fixture = DebugFixture(this) { it.copy(versionName = "0.1.1-device-test") }
        assertContains(fixture.core.diagnosticsReport().text, "App version 0.1.1-device-test\n")
        fixture.scope.cancel()
    }
}
