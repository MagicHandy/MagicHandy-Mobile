package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.api.UserIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest

internal class BridgeRecoveryTest {
    @Test
    fun `simulated linear discovery and explicit local reconciliation preserve bridge evidence`() =
        runTest {
            val f = DebugFixture(this)
            f.onboard()
            val address = "ws://127.0.0.1:12345"
            val choice = f.core.intifaceLinearActuators(address).single()
            f.send(UserIntent.AddIntifaceLinear(address, choice.deviceIndex, choice.actuatorIndex))
            val row =
                f.core.state.value.devices.devices.single {
                    it.family == DeviceFamily.LinearActuator
                }
            f.connect(row.id)
            f.send(UserIntent.StartMotion)
            assertEquals(RunPhase.Playing, f.core.state.value.run.phase)
            f.core.emergencyStop(StopSource.AppFooter)
            f.send(UserIntent.DismissStopReceipt)
            assertEquals(
                UnconfirmedCause.BridgeOnly,
                f.core.state.value.stopReceipt?.outcomes?.single()?.cause,
            )
            f.send(UserIntent.ReconcileDevice(row.id))
            assertNull(f.core.state.value.stopReceipt)
            f.scope.cancel()
        }
}
