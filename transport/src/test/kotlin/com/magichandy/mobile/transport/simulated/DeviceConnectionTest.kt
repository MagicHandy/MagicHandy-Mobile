package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.ReplyState
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class DeviceConnectionTest {
    @Test
    fun `an idle disconnect preserves the other owner and the current chat epoch`() = runTest {
        val f = DebugFixture(this)
        f.onboard()
        val other = DeviceId("ultra")
        val leaving = DeviceId("vacuglide")
        f.connect(other)
        f.connect(leaving)
        f.send(UserIntent.SendMessage("Hello"))
        val before = f.devices.output.value.getValue(other)
        f.send(UserIntent.Disconnect(leaving))
        assertEquals(before, f.devices.output.value.getValue(other))
        assertEquals(1, f.devices.output.value.getValue(leaving).stops)
        assertTrue(f.core.state.value.chat.reply is ReplyState.Streaming)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(ReplyState.Idle, f.core.state.value.chat.reply)
        assertNull(f.core.state.value.stopReceipt)
        f.send(UserIntent.StartMotion)
        assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
        f.scope.cancel()
    }

    @Test
    fun `disconnect during a run stops the healthy owner too and keeps it connected`() = runTest {
        val f = DebugFixture(this)
        val other = DeviceId("ultra")
        val leaving = DeviceId("vacuglide")
        f.onboard()
        f.connect(other)
        f.connect(leaving)
        f.send(UserIntent.StartMotion)
        assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
        f.send(UserIntent.Disconnect(leaving))
        for (id in listOf(leaving, other)) {
            val output = f.devices.output.value.getValue(id)
            assertEquals(0, output.speed.value)
            assertEquals(1, output.stops)
        }
        assertEquals(
            DeviceStatus.Connected,
            f.core.state.value.devices.devices.single { it.id == other }.status,
        )
        assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
        assertNull(f.d.journal.read())
        assertNull(f.core.state.value.stopReceipt)
        f.scope.cancel()
    }

    @Test
    fun `a slow connect yields to later intents and disconnect cancels only its owner scope`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val scopes = mutableListOf<CoroutineScope>()
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        devices =
                            object : DeviceDirectory by d.devices {
                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: CoroutineScope,
                                ): DeviceTransport? {
                                    scopes += ownerScope
                                    release.await()
                                    return d.devices.connect(device, ownerScope)
                                }
                            }
                    )
                }
            val id = DeviceId("ultra")
            f.onboard()
            f.connect(id)
            f.connect(id)
            f.send(UserIntent.NewChat)
            assertNotNull(f.core.state.value.chat.chatId)
            assertEquals(
                DeviceStatus.Connecting,
                f.core.state.value.devices.devices.single { it.id == id }.status,
            )
            val ownerJob = requireNotNull(scopes.single().coroutineContext[Job])
            release.complete(Unit)
            runCurrent()
            assertEquals(
                DeviceStatus.Connected,
                f.core.state.value.devices.devices.single { it.id == id }.status,
            )
            f.send(UserIntent.Disconnect(id))
            assertTrue(ownerJob.isCancelled)
            assertTrue(requireNotNull(f.scope.coroutineContext[Job]).isActive)
            f.scope.cancel()
        }

    @Test
    fun `disconnect during a pending connect prevents a late attachment`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val f =
            DebugFixture(this) { d ->
                d.copy(
                    devices =
                        object : DeviceDirectory by d.devices {
                            override suspend fun connect(
                                device: DeviceId,
                                ownerScope: CoroutineScope,
                            ): DeviceTransport? {
                                entered.complete(Unit)
                                awaitCancellation()
                            }
                        }
                )
            }
        val id = DeviceId("ultra")
        f.onboard()
        f.connect(id)
        assertTrue(entered.isCompleted)
        f.send(UserIntent.Disconnect(id))
        assertEquals(
            DeviceStatus.NotConnected,
            f.core.state.value.devices.devices.single { it.id == id }.status,
        )
        f.core.emergencyStop(StopSource.Notification)
        runCurrent()
        assertTrue(requireNotNull(f.core.state.value.stopReceipt).isNoDevice)
        f.scope.cancel()
    }
}
