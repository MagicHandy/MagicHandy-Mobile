package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.DeviceConnectionFailure
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
internal class CloudBootRetryTest {
    @Test
    fun `a silent booting Handy can be retried immediately or after the three-second deadline`() =
        runTest {
            for (retryEarly in listOf(false, true)) {
                var attempts = 0
                var cancelled = 0
                val f =
                    DebugFixture(this) { d ->
                        d.copy(
                            devices =
                                object : DeviceDirectory by d.devices {
                                    override suspend fun connect(
                                        device: DeviceId,
                                        ownerScope: CoroutineScope,
                                    ): DeviceTransport? {
                                        if (++attempts == 1) {
                                            try {
                                                awaitCancellation()
                                            } finally {
                                                cancelled++
                                            }
                                        }
                                        assertEquals(1, cancelled)
                                        return d.devices.connect(device, ownerScope)
                                    }
                                }
                        )
                    }
                f.onboard()
                f.send(
                    UserIntent.AddCloudHandy(
                        "Booting fixture",
                        Secret("fixtureKey"),
                        connectAfterSave = true,
                    )
                )
                fun row() =
                    f.core.state.value.devices.devices.single { it.name == "Booting fixture" }
                val id = row().id
                advanceTimeBy(if (retryEarly) 200 else 2999)
                runCurrent()
                assertEquals(DeviceStatus.Connecting, row().status)
                assertEquals(true, row().canRetryWhileConnecting)
                if (retryEarly) f.send(UserIntent.RetryConnection(id))
                else {
                    advanceTimeBy(1)
                    runCurrent()
                    assertEquals(DeviceStatus.Lost, row().status)
                    assertEquals(
                        DeviceConnectionFailure.Reason.TimedOut,
                        row().connectionFailure?.reason,
                    )
                    f.connect(id)
                }
                assertEquals(DeviceStatus.Connected, row().status)
                assertNull(row().connectionFailure)
                f.send(
                    UserIntent.RetryConnection(id)
                ) // A stale tap cannot disconnect the new owner.
                advanceTimeBy(5000)
                runCurrent()
                assertEquals(DeviceStatus.Connected, row().status)
                assertEquals(2, attempts)
                assertNull(f.d.journal.read())
                f.scope.cancel()
            }
        }
}
