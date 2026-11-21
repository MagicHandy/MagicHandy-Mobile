package com.magichandy.mobile.transport.intiface

import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.FormResult
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.StopOutcome
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UnconfirmedCause
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.createMagicHandyCore
import com.magichandy.mobile.core.ports.BridgeSelectionRequired
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceConfiguration
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.DiagnosticKind
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.LinearPoint
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.core.ports.confirmIdleForConnection
import com.magichandy.mobile.transport.simulated.simulatedDependencies
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout

internal class IntifaceLinearTest {
    // Borrow a short-lived test lease through the public transport seam. No Android or radio.
    private suspend fun DeviceTransport.withFence(block: suspend (OutputFence) -> Unit) {
        var entered = 0
        val probe =
            object : DeviceTransport by this {
                override suspend fun establishIdle(fence: OutputFence): StopEvidence {
                    entered++
                    block(fence)
                    return StopEvidence.Acknowledged
                }
            }
        probe.confirmIdleForConnection()
        assertEquals(1, entered)
    }

    @Test
    fun `discovery leases share a socket and reidentify across index changes or fail closed`() =
        runBlocking {
            BridgePeer().use { p ->
                val targets = p.find()
                val saved = targets.first()
                assertEquals(DeviceFamily.Handy, saved.family)
                p.index = 9
                val linear = p.linear(saved)
                assertEquals(180L, linear.linearSampling.preferredMillis)
                val vibrator =
                    IntifaceVibrator.connect(p.hub, p.row(targets.last()), { Percent(50) }, {})
                assertEquals(2, p.names.count { it == "RequestServerInfo" })
                assertFailsWith<BridgeSelectionRequired> { p.linear(saved) }
                linear.close()
                assertTrue(requireNotNull(vibrator.checkConnection).invoke())
                vibrator.close()
                p.copies = 2
                assertFailsWith<BridgeSelectionRequired> { p.linear(saved) }
                assertEquals(3, p.names.count { it == "RequestServerInfo" })
            }
        }

    @Test
    fun `a new discovery and owner can use the same origin before its dead lease is released`() =
        runBlocking {
            BridgePeer().use { p ->
                val target = p.find().first()
                val old = p.linear(target)
                requireNotNull(p.socket).close(1000, "fixture reconnect")
                withTimeout(2000) { p.lost.await() }
                assertEquals(target, p.find().first())
                val replacement = p.linear(target)
                val connections = p.names.count { it == "RequestServerInfo" }
                old.close()
                assertTrue(replacement.checkConnection())
                assertEquals(connections, p.names.count { it == "RequestServerInfo" })
                assertFalse(p.names.contains("LinearCmd"))
                replacement.close()
            }
        }

    @Test
    fun `a device catalog can exceed an ordinary reply while remaining bounded`() = runBlocking {
        BridgePeer().use { p ->
            p.index = 1000
            p.copies = 100
            p.descriptor = "Synthetic position feature. ".repeat(26)
            assertEquals(101, p.find().size)
            p.descriptor = "Synthetic position feature. ".repeat(105)
            assertFailsWith<java.io.IOException> { p.find() }
        }
    }

    @Test
    fun `a reassigned vibrator index cannot receive a targeted zero from the old lease`() =
        runBlocking {
            BridgePeer().use { p ->
                val target = p.find().single { it.family == DeviceFamily.Vibrator }
                val owner =
                    IntifaceVibrator.connect(p.hub, p.row(target), { Percent(50) }) {
                        p.lost.complete(Unit)
                    }
                val before = p.scalars.size
                requireNotNull(p.socket).send("""[{"DeviceRemoved":{"Id":0,"DeviceIndex":22}}]""")
                withTimeout(2000) { p.lost.await() }
                owner.confirmIdleForConnection()
                assertEquals(before, p.scalars.size)
                assertTrue("StopAllDevices" in p.names)
                owner.close()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `dispatch work does not push the next command past its advertised timing gap`() =
        runBlocking {
            val scheduler = TestCoroutineScheduler()
            val output = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
            var busyMillis = 0L
            var dispatching = false
            val clock =
                object : Clock {
                    override fun elapsed() = (scheduler.currentTime + busyMillis).milliseconds

                    override fun now() =
                        Instant.fromEpochMilliseconds(elapsed().inWholeMilliseconds)
                }
            BridgePeer().use { peer ->
                peer.gap = 100
                val owner =
                    peer.linear(
                        outputScope = output,
                        playbackClock = clock,
                        liveLimits = {
                            if (dispatching) busyMillis += 16
                            peer.bounds
                        },
                    )
                try {
                    owner.withFence { fence ->
                        val points = (0..8).map { i -> LinearPoint(i * 110L, i * 10.0) }
                        assertEquals(
                            WriteResult.Accepted,
                            owner.write(peer.batch(points, defer = true), fence),
                        )
                        peer.next()
                        dispatching = true
                        assertEquals(
                            WriteResult.Accepted,
                            requireNotNull(owner.linkedStart).play(0, fence),
                        )
                        repeat(6) {
                            scheduler.runCurrent()
                            assertEquals(110L, peer.next().millis)
                            scheduler.advanceTimeBy(110 - 16L)
                        }
                        assertFalse(peer.lost.isCompleted)
                        owner.stop(fence)
                    }
                } finally {
                    owner.close()
                    output.cancel()
                }
            }
        }

    @Test
    fun `anchor precedes absolute segments and queued direction survives a live range edit`() =
        runBlocking {
            BridgePeer().use { p ->
                p.gap = 270
                p.reverse = true
                val linear = p.linear()
                linear.withFence { f ->
                    val write = async {
                        linear.write(
                            p.batch(
                                listOf(
                                    LinearPoint(0, 10.5),
                                    LinearPoint(400, 80.25),
                                    LinearPoint(800, 15.0),
                                )
                            ),
                            f,
                        )
                    }
                    val anchor = p.next()
                    p.reverse = false
                    p.bounds = Limits(Percent(80), Percent(20), Percent(80))
                    assertEquals(270L, anchor.millis)
                    assertEquals(0.895, anchor.position, 0.00001)
                    assertEquals(WriteResult.Accepted, write.await())
                    val first = p.next()
                    assertEquals(0.3185, first.position, 0.00001)
                    assertTrue(first.at - anchor.at >= 245)
                    assertTrue(first.millis in 300..400)
                    assertEquals(
                        StopEvidence.Unconfirmed(UnconfirmedCause.BridgeOnly),
                        linear.stop(f),
                    )
                    delay(430)
                    assertEquals("StopAllDevices", p.names.last())
                    assertTrue(p.moves.tryReceive().isFailure)
                }
                linear.close()
            }
        }

    @Test
    fun `expired and overcompressed segments starve and a missing anchor ack is never retried`() =
        runBlocking {
            for (fault in listOf("missing", "rejected", "late", "expired", "capacity")) {
                BridgePeer().use { p ->
                    val l = p.linear()
                    l.withFence { f ->
                        val points =
                            if (fault == "capacity") (0..65).map { LinearPoint(it * 100L, 50.0) }
                            else
                                listOf(
                                    LinearPoint(0, 20.0),
                                    LinearPoint(200, 80.0),
                                    LinearPoint(400, 30.0),
                                )
                        if (fault == "missing") p.respond = false
                        if (fault == "rejected") p.reject = true
                        val started = p.clock.elapsed()
                        val result = l.write(p.batch(points, defer = true), f)
                        if (fault == "missing")
                            assertTrue(
                                (p.clock.elapsed() - started).inWholeMilliseconds in 600..1300
                            )
                        if (fault == "late" || fault == "expired") {
                            assertEquals(WriteResult.Accepted, result)
                            requireNotNull(l.linkedStart).play(if (fault == "late") 80 else 500, f)
                        } else assertEquals(WriteResult.Uncertain, result)
                        withTimeout(1800) { p.lost.await() }
                        assertEquals(
                            if (fault == "capacity") 0 else 1,
                            p.names.count { it == "LinearCmd" },
                        )
                        if (fault == "late" || fault == "expired")
                            assertContains(p.diagnostics, DiagnosticKind.IntifaceStarved)
                        assertEquals("StopDeviceCmd", p.names.last())
                    }
                    l.close()
                }
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `the ninth unacknowledged movement stops the bridge`() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val output = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val clock =
            object : Clock {
                override fun elapsed() = scheduler.currentTime.milliseconds

                override fun now() = Instant.fromEpochMilliseconds(scheduler.currentTime)
            }
        BridgePeer().use { peer ->
            val owner = peer.linear(outputScope = output, playbackClock = clock)
            try {
                owner.withFence { fence ->
                    val points = (0..20).map { LinearPoint(it * 60L, 50.0) }
                    assertEquals(
                        WriteResult.Accepted,
                        owner.write(peer.batch(points, defer = true), fence),
                    )
                    peer.next() // Consume the acknowledged startup anchor.
                    peer.respond = false
                    requireNotNull(owner.linkedStart).play(0, fence)
                    repeat(8) {
                        scheduler.runCurrent()
                        peer.next() // Each submission reaches the loopback socket before the next
                        // tick.
                        scheduler.advanceTimeBy(60)
                    }
                    withTimeout(2000) {
                        while (!peer.lost.isCompleted) {
                            scheduler.runCurrent()
                            delay(1)
                        }
                    }
                    assertEquals(9, peer.names.count { it == "LinearCmd" })
                    assertEquals("StopDeviceCmd", peer.names.last())
                }
            } finally {
                owner.close()
                output.cancel()
            }
        }
    }

    @Test
    fun `a failed ping or dead peer signals loss and fences later movements`() = runBlocking {
        for (kill in listOf(false, true)) BridgePeer().use { p ->
            p.ping = 300
            val owner = p.linear()
            owner.withFence { f ->
                assertEquals(
                    WriteResult.Accepted,
                    owner.write(
                        p.batch(
                            listOf(
                                LinearPoint(0, 30.0),
                                LinearPoint(500, 75.0),
                                LinearPoint(1000, 30.0),
                            )
                        ),
                        f,
                    ),
                )
                if (kill) p.kill() else p.refusePing = true
                withTimeout(2200) { p.lost.await() }
                val n = p.names.count { it == "LinearCmd" }
                delay(550)
                assertEquals(n, p.names.count { it == "LinearCmd" })
            }
            owner.close()
        }
    }

    @Test
    fun `Stop during the startup anchor cannot start the pending generation`() = runBlocking {
        BridgePeer().use { p ->
            val l = p.linear()
            l.withFence { f ->
                val attempt = async {
                    l.write(p.batch(listOf(LinearPoint(0, 25.0), LinearPoint(300, 75.0))), f)
                }
                p.next()
                assertEquals(StopEvidence.Unconfirmed(UnconfirmedCause.BridgeOnly), l.stop(f))
                assertTrue(attempt.await() != WriteResult.Accepted)
                delay(320)
                assertEquals(1, p.names.count { it == "LinearCmd" })
                assertEquals("StopAllDevices", p.names.last())
            }
            l.close()
        }
    }

    @Test
    fun `a direct Stop reaches a live discovery-only socket before selection`() = runBlocking {
        BridgePeer().use { p ->
            val d = simulatedDependencies(p.scope)
            val directory =
                object : DeviceDirectory by d.devices {
                    override fun stopOpenBridges(epoch: Epoch) = p.hub.emergencyStop(epoch)

                    override val configuration =
                        object : DeviceConfiguration by requireNotNull(d.devices.configuration) {
                            override suspend fun bridgeTargets(address: String) =
                                p.hub.targets(address)
                        }
                }
            val core = createMagicHandyCore(d.copy(devices = directory))
            core.dispatch(UserIntent.AcknowledgeAdultsOnly)
            withTimeout(2000) { core.state.first { it.firstRun.hasAcknowledgedAdultsOnly } }
            p.holdList = true
            val listing = async { core.intifaceLinearActuators(p.address) }
            withTimeout(2000) { p.listSeen.await() }
            core.emergencyStop(StopSource.AppFooter)
            withTimeout(1800) { p.allStopped.await() }
            assertFalse(listing.isCompleted)
            requireNotNull(p.replyToList).invoke()
            listing.await()
            assertEquals(1, p.names.count { it == "StopAllDevices" })
            assertFalse("LinearCmd" in p.names)
        }
    }

    @Test
    fun `expired endpoints are discarded and an eligible live segment uses its remaining time`() =
        runBlocking {
            BridgePeer().use { p ->
                val l = p.linear()
                l.withFence { f ->
                    val points =
                        listOf(
                            LinearPoint(0, 5.0),
                            LinearPoint(100, 13.0),
                            LinearPoint(200, 26.0),
                            LinearPoint(700, 75.125),
                        )
                    assertEquals(WriteResult.Accepted, l.write(p.batch(points, defer = true), f))
                    p.next()
                    requireNotNull(l.linkedStart).play(255, f)
                    val move = p.next()
                    assertEquals(0.75125, move.position, 0.00001)
                    assertTrue(move.millis in 401..445)
                    l.stop(f)
                    assertEquals(2, p.names.count { it == "LinearCmd" })
                }
                l.close()
            }
        }

    @Test
    fun `a killed bridge stops a public run and its unconfirmed device can be checked locally`() =
        runBlocking {
            BridgePeer().use { p ->
                val d = simulatedDependencies(p.scope)
                val losses = MutableSharedFlow<DeviceId>(extraBufferCapacity = 8)
                var connected: IntifaceLinear? = null
                val directory =
                    object : DeviceDirectory by d.devices {
                        override val losses = losses
                        override val configuration =
                            object :
                                DeviceConfiguration by requireNotNull(d.devices.configuration) {
                                override suspend fun bridgeTargets(address: String) =
                                    p.hub.targets(address)
                            }

                        override suspend fun connect(
                            device: DeviceId,
                            ownerScope: CoroutineScope,
                        ): DeviceTransport {
                            val row = saved.single { it.ref.id == device }
                            return IntifaceLinear.connect(
                                    p.hub,
                                    row,
                                    ownerScope,
                                    p.clock,
                                    { p.bounds },
                                    { false },
                                    { losses.tryEmit(device) },
                                )
                                .also { connected = it }
                        }

                        override suspend fun disconnect(device: DeviceId) {
                            connected?.close()
                        }

                        override fun stopOpenBridges(epoch: Epoch) = p.hub.emergencyStop(epoch)
                    }
                val core = createMagicHandyCore(d.copy(devices = directory))
                suspend fun state(matches: (AppState) -> Boolean) =
                    withTimeout(5000) { core.state.first(matches) }
                core.dispatch(UserIntent.AcknowledgeAdultsOnly)
                state { it.firstRun.hasAcknowledgedAdultsOnly }
                assertEquals(
                    FormResult.Accepted,
                    core.submit(UserIntent.AddIntifaceLinear(p.address)),
                )
                val id =
                    core.state.value.devices.devices.single { it.name == "The Handy (FW4+)" }.id
                core.dispatch(UserIntent.Connect(id))
                state {
                    it.devices.devices.any { row ->
                        row.id == id && row.status == DeviceStatus.Connected
                    }
                }
                core.dispatch(UserIntent.StartMotion)
                state { it.run.phase == RunPhase.Playing }
                p.next()
                p.next()
                p.kill()
                state {
                    it.run.phase == RunPhase.Idle &&
                        it.stopReceipt?.outcomes?.any { row ->
                            row.device == id && row.outcome == StopOutcome.Unconfirmed
                        } == true
                }
                core.dispatch(UserIntent.ReconcileDevice(id))
                state { it.stopReceipt == null }
            }
        }

    @Test
    fun `an incompatible saved family or mislabeled linear feature cannot issue any actuator command`() =
        runBlocking {
            BridgePeer().use { p ->
                val selected = p.find().first()
                val saved = p.row(selected)
                val wrong = saved.copy(ref = saved.ref.copy(family = DeviceFamily.Vibrator))
                assertFailsWith<BridgeSelectionRequired> {
                    IntifaceVibrator.connect(p.hub, wrong, { Percent(50) }, {})
                }
                p.axisType = "Rotate"
                assertTrue(p.find().none { it.family != DeviceFamily.Vibrator })
                p.axisType = null
                assertTrue(p.find().none { it.family != DeviceFamily.Vibrator })
                p.axisType = "Linear"
                assertEquals(DeviceFamily.Handy, p.find().first().family)
                assertFalse(
                    p.names.any {
                        it in setOf("LinearCmd", "ScalarCmd", "StopDeviceCmd", "StopAllDevices")
                    }
                )
            }
        }

    @Test
    fun `generic linear axes retain unsigned step precision and their own timing preference`() =
        runBlocking {
            BridgePeer().use { p ->
                p.deviceName = "Synthetic linear actuator"
                p.stepCount = 0xffffffffL
                val row = p.find().first()
                assertEquals(DeviceFamily.LinearActuator, row.family)
                val l = p.linear(row)
                assertEquals(0xffffffffL, l.linearSampling.steps)
                assertEquals(0L, l.linearSampling.preferredMillis)
                l.close()
            }
        }

    @Test
    fun `mounting stays fixed on subsequent appends in the same stream`() = runBlocking {
        BridgePeer().use { p ->
            p.reverse = false
            val linear = p.linear()
            linear.withFence { fence ->
                val first =
                    p.batch(listOf(LinearPoint(0, 20.0), LinearPoint(700, 30.0)), defer = true)
                assertEquals(WriteResult.Accepted, linear.write(first, fence))
                p.next()
                p.reverse = true
                val next = p.batch(listOf(LinearPoint(1400, 30.0)), first = false)
                assertEquals(WriteResult.Accepted, linear.write(next, fence))
                assertEquals(
                    WriteResult.Accepted,
                    requireNotNull(linear.linkedStart).play(0, fence),
                )
                val a = p.next()
                val b = p.next()
                assertEquals(0.3, a.position, 0.0001)
                assertEquals(0.3, b.position, 0.0001)
                linear.stop(fence)
            }
            linear.close()
        }
    }

    @Test
    fun `a denied linear submission emits no command and keeps the bridge usable`() = runBlocking {
        BridgePeer().use { peer ->
            val socket = ButtplugSocket({}, {})
            try {
                socket.open(okhttp3.OkHttpClient(), peer.address)
                socket.identify()
                assertFailsWith<FencedBridgeRequest> {
                    socket.begin("LinearCmd", allowed = { false })
                }
                assertTrue(socket.features().isNotEmpty())
                assertTrue("LinearCmd" !in peer.names)
            } finally {
                socket.close()
            }
        }
    }
}
