package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.ScanResultId
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.StopSource
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.StructuredProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.confirmIdleForConnection
import com.magichandy.mobile.core.testing.InMemoryModels
import com.magichandy.mobile.transport.ble.BluetoothConnector
import com.magichandy.mobile.transport.simulated.DebugFixture
import java.io.File
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Public Start, chat update and emergency Stop through real encoders and inert endpoint peers. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class HandyMotionCaptureTest {
    @Test fun `cloud manual wire`() = runTest { capture("cloud", "manual") }

    @Test fun `cloud chat wire`() = runTest { capture("cloud", "chat") }

    @Test fun `bluetooth manual wire`() = runTest { capture("ble", "manual") }

    @Test fun `bluetooth chat wire`() = runTest { capture("ble", "chat") }

    private suspend fun TestScope.capture(route: String, scenario: String) {
        val trace = HandyMotionCapture { testScheduler.currentTime }
        val modelPeer = ScriptedSweep()
        val f =
            DebugFixture(this) { d ->
                val catalog = d.models.state.value
                val model = catalog.models.values.single()
                d.copy(
                    models =
                        InMemoryModels(
                            catalog.copy(
                                models =
                                    mapOf(
                                        model.info.id to
                                            model.copy(
                                                info =
                                                    model.info.copy(
                                                        capability = CapabilityLevel.Simple
                                                    )
                                            )
                                    )
                            )
                        ),
                    provider = { modelPeer },
                    devices =
                        object : DeviceDirectory by d.devices {
                            override suspend fun connect(
                                device: DeviceId,
                                ownerScope: CoroutineScope,
                            ): DeviceTransport {
                                val ref = saved.single { it.ref.id == device }.ref
                                return if (route == "ble")
                                    HandyBluetoothSession.connect(
                                        ref,
                                        ScanResultId("fixture"),
                                        BluetoothConnector { _, _, _ -> trace.bluetooth() },
                                        ownerScope,
                                        d.clock,
                                    )
                                else
                                    trace.cloud(ref, d.clock).also {
                                        assertEquals(
                                            StopEvidence.Acknowledged,
                                            it.confirmIdleForConnection(),
                                        )
                                    }
                            }
                        },
                )
            }
        try {
            f.onboard()
            if (route == "cloud")
                f.send(
                    UserIntent.AddCloudHandy(
                        "Cloud fixture",
                        Secret("fixtureKey"),
                        connectAfterSave = true,
                    )
                )
            else f.connect(DeviceId("handy"))
            awaitCallback {
                f.core.state.value.devices.devices.any { it.status == DeviceStatus.Connected }
            }
            trace.record("action") { put("name", "start") }
            f.send(
                if (scenario == "manual") UserIntent.StartMotion
                else UserIntent.SendMessage("Use steady full sweeps.")
            )
            awaitCallback { trace.events.any { it.op == "play" } }
            val steadyMillis = System.getenv("MAGICHANDY_WIRE_STEADY_MS")?.toLong() ?: 13250L
            require(steadyMillis >= 13250)
            advanceTimeBy(steadyMillis)
            runCurrent()
            val beforeChange =
                trace.events.last { it.op == "add" }.points.last().jsonArray[0].jsonPrimitive.long
            trace.record("action") {
                put("name", "retarget")
                put("committed_tail_ms", beforeChange)
            }
            f.send(
                if (scenario == "manual") UserIntent.SetEdge(true)
                else UserIntent.SendMessage("Change to shorter strokes near the top.")
            )
            if (scenario == "chat") {
                val accepted = f.core.state.value.run
                assertEquals(25, accepted.commandedPace?.value)
                assertEquals(32, accepted.pendingPace?.value)
            }
            advanceTimeBy(7400)
            runCurrent()
            assertEquals(
                if (scenario == "manual") RunPhase.Edging else RunPhase.Playing,
                f.core.state.value.run.phase,
            )
            if (scenario == "chat") assertEquals(32, f.core.state.value.run.commandedPace?.value)
            assertEquals(null, f.core.state.value.run.pendingPace)
            val stop = testScheduler.currentTime
            trace.record("action") { put("name", "stop") }
            f.core.emergencyStop(StopSource.AppFooter)
            awaitCallback { f.core.state.value.stopReceipt?.isAllConfirmed == true }
            assertEquals(null, f.core.state.value.run.pendingPace)
            val completed = trace.events.toList()
            advanceTimeBy(6000)
            runCurrent()
            assertEquals(completed, trace.events.toList())
            checkStream(route, scenario, completed, beforeChange, stop)
            val output = System.getenv("MAGICHANDY_WIRE_CAPTURE")
            if (!output.isNullOrEmpty()) {
                val directory = File(output).also { it.mkdirs() }
                val data = buildJsonObject {
                    put("route", route)
                    put("scenario", scenario)
                    put("stop_ms", stop)
                    put("events", JsonArray(completed))
                }
                File(directory, "$route-$scenario.json").writeText(data.toString())
            }
        } finally {
            f.scope.cancel()
        }
    }

    private fun checkStream(
        route: String,
        scenario: String,
        events: List<JsonObject>,
        committed: Long,
        stop: Long,
    ) {
        val play = events.single { it.op == "play" }
        val playIndex = events.indexOf(play)
        assertEquals(1, events.count { it.op == "setup" })
        val coverage =
            events
                .take(playIndex)
                .filter { it.op == "add" }
                .last()
                .points
                .last()
                .jsonArray[0]
                .jsonPrimitive
                .long
        assertTrue(coverage >= 1500)
        val packets = events.filter { it.op == "add" }
        var nextIndex = 0
        for (packet in packets) {
            assertTrue(packet.points.size in 1..(if (route == "cloud") 100 else 20))
            assertEquals(nextIndex == 0, packet.getValue("flush").jsonPrimitive.boolean)
            nextIndex += packet.points.size
            assertEquals(nextIndex - 1, packet.getValue("tail").jsonPrimitive.int)
        }
        val points = packets.flatMap { it.points }.map { it.jsonArray }
        fun time(point: JsonArray) = point[0].jsonPrimitive.long
        fun position(point: JsonArray) = point[1].jsonPrimitive.int
        // The Original Handy's sparse fit at 25%, measured before the retarget. Desktop alpha.89
        // sends 56 over Cloud and its precise 149 over Bluetooth. Android adds sparse BLE
        // turnaround points.
        assertEquals(if (route == "ble") 83 else 56, points.count { time(it) < 12000 })
        assertTrue(points.zipWithNext().all { (a, b) -> time(a) < time(b) })
        assertTrue(points.all { position(it) in 0..100 })
        assertTrue(time(points.last()) > stop - play.getValue("at_ms").jsonPrimitive.long)
        val lastSecond = points.filter { time(it) > time(points.last()) - 1000 }
        val expected = if (scenario == "manual") 25..75 else 70..100
        assertTrue(time(lastSecond.first()) > committed)
        assertTrue(
            lastSecond.all { position(it) in expected },
            "Latest accepted positions must reflect the requested band.",
        )
        val stopAction = events.indexOfLast { it.op == "action" }
        assertEquals(listOf("stop"), events.drop(stopAction + 1).map { it.op })
    }

    private fun TestScope.awaitCallback(ready: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        while (!ready()) {
            runCurrent()
            check(start.elapsedNow() < 4.seconds) {
                "Virtual playback failed to reach the expected callback"
            }
            LockSupport.parkNanos(100_000)
        }
        runCurrent()
    }

    private val JsonObject.op
        get() = getValue("op").jsonPrimitive.content

    private val JsonObject.points
        get() = getValue("points").jsonArray

    private class ScriptedSweep : StructuredProvider {
        private var requestCount = 0

        override suspend fun complete(
            request: ModelRequest,
            preview: (String) -> Unit,
        ): TextResult {
            val text =
                if (++requestCount == 1)
                    """{"action":"start","edits":[{"geometry":"full_slider"},{"controls":{"speed_percent":25,"pace_variation_percent":0}},{"remove_layers":["pace"]}],"reply":"Full sweeps."}"""
                else
                    """{"action":"update","edits":[{"range":{"min_percent":70,"max_percent":100}},{"geometry":"full_band"},{"controls":{"speed_percent":32}}],"reply":"Shorter strokes."}"""
            return TextResult.Completed(text)
        }

        override suspend fun stream(
            messages: List<TextMessage>,
            preview: (String) -> Unit,
        ): TextResult = error("Expected structured chat")
    }
}
