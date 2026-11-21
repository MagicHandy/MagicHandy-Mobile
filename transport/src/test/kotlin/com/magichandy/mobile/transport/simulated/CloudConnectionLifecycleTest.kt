package com.magichandy.mobile.transport.simulated

import com.magichandy.mobile.core.api.DeviceConnectionFailure
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.core.ports.DeviceConnectionException
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceLinkLoss
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.confirmIdleForConnection
import com.magichandy.mobile.transport.handy.cloudOwner
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

@OptIn(ExperimentalCoroutinesApi::class)
internal class CloudConnectionLifecycleTest {
    @Test
    fun `retry while booting makes a fresh cloud request and a later offline check explains the loss`() =
        runBlocking {
            MockWebServer().use { peer ->
                peer.start()
                peer.enqueue(
                    MockResponse.Builder()
                        .headersDelay(2, TimeUnit.SECONDS)
                        .body("""{"result":{"connected":false}}""")
                        .build()
                )
                for (body in
                    listOf(
                        """{"result":{"connected":true}}""",
                        """{"result":{"mode":6}}""",
                        """{"result":"ok"}""",
                        """{"result":{"mode":7}}""",
                        """{"error":{"code":1001,"connected":false}}""",
                    )) peer.enqueue(MockResponse.Builder().body(body).build())
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val dependencies = simulatedDependencies(scope)
                    val devices =
                        object : DeviceDirectory by dependencies.devices {
                            override suspend fun connect(
                                device: DeviceId,
                                ownerScope: CoroutineScope,
                            ): DeviceTransport {
                                val row = saved.single { it.ref.id == device }
                                val key =
                                    requireNotNull(
                                        dependencies.vault.get(requireNotNull(row.credential))
                                    )
                                val owner =
                                    cloudOwner(
                                        row.ref,
                                        key,
                                        Secret("fixture"),
                                        OkHttpClient(),
                                        peer.url("/"),
                                    )
                                assertEquals(
                                    StopEvidence.Acknowledged,
                                    owner.confirmIdleForConnection(),
                                )
                                return owner
                            }
                        }
                    val core = createMagicHandyCore(dependencies.copy(devices = devices))
                    core.dispatch(UserIntent.AcknowledgeAdultsOnly)
                    withTimeout(3000) { core.state.first { it.firstRun.hasAcknowledgedAdultsOnly } }
                    assertEquals(
                        FormResult.Accepted,
                        core.submit(
                            UserIntent.AddCloudHandy(
                                "Cloud fixture",
                                Secret("fixtureKey"),
                                connectAfterSave = true,
                            )
                        ),
                    )
                    suspend fun waitFor(status: DeviceStatus) =
                        withTimeout(3000) {
                            core.state
                                .first { state ->
                                    state.devices.devices.any {
                                        it.name == "Cloud fixture" && it.status == status
                                    }
                                }
                                .devices
                                .devices
                                .single { it.name == "Cloud fixture" }
                        }
                    val booting = waitFor(DeviceStatus.Connecting)
                    val first = requireNotNull(peer.takeRequest(2, TimeUnit.SECONDS))
                    assertEquals("/connected", first.url.encodedPath)
                    core.dispatch(UserIntent.RetryConnection(booting.id))
                    val ready = waitFor(DeviceStatus.Connected)
                    core.dispatch(UserIntent.CheckConnection(ready.id))
                    val lost = waitFor(DeviceStatus.Lost)
                    assertEquals(
                        DeviceConnectionFailure.Reason.Offline,
                        lost.connectionFailure?.reason,
                    )
                    assertEquals(1001, lost.connectionFailure?.deviceCode)
                    assertEquals(ready.id, lost.id)
                    assertEquals(6, peer.requestCount)
                    val routes =
                        List(5) {
                            peer.takeRequest().let { request ->
                                assertEquals("fixtureKey", request.headers["X-Connection-Key"])
                                request.method + " " + request.url.encodedPath
                            }
                        }
                    assertEquals(
                        listOf(
                            "GET /connected",
                            "GET /mode",
                            "PUT /mode2",
                            "GET /mode",
                            "GET /connected",
                        ),
                        routes,
                    )
                } finally {
                    scope.cancel()
                }
            }
        }

    @Test
    fun `Connect from the form verifies the saved row and a reconnect clears its old failure`() =
        runTest {
            val issue =
                DeviceConnectionFailure(DeviceConnectionFailure.Reason.Offline, deviceCode = 1001)
            var offline = true
            val generations = mutableListOf<Job>()
            val losses = MutableSharedFlow<DeviceLinkLoss>()
            val f =
                DebugFixture(this) { d ->
                    d.copy(
                        devices =
                            object : DeviceDirectory by d.devices {
                                override val connectionLosses = losses

                                override suspend fun connect(
                                    device: DeviceId,
                                    ownerScope: CoroutineScope,
                                ): DeviceTransport? {
                                    generations += ownerScope.coroutineContext.job
                                    if (offline) throw DeviceConnectionException(issue)
                                    return d.devices.connect(device, ownerScope)
                                }
                            }
                    )
                }
            f.onboard()
            assertEquals(
                FormResult.Accepted,
                f.core.submit(
                    UserIntent.AddCloudHandy(
                        "Fixture Handy",
                        Secret("fixtureKey"),
                        connectAfterSave = true,
                    )
                ),
            )
            runCurrent()
            fun row() = f.core.state.value.devices.devices.single { it.name == "Fixture Handy" }
            assertEquals(DeviceStatus.Lost, row().status)
            assertEquals(issue, row().connectionFailure)
            offline = false
            f.connect(row().id)
            assertEquals(DeviceStatus.Connected, row().status)
            assertNull(row().connectionFailure)
            losses.emit(DeviceLinkLoss(row().id, generations.first()))
            runCurrent()
            assertEquals(DeviceStatus.Connected, row().status)
            losses.emit(DeviceLinkLoss(row().id, generations.last()))
            runCurrent()
            assertEquals(DeviceStatus.Lost, row().status)
            f.scope.cancel()
        }
}
