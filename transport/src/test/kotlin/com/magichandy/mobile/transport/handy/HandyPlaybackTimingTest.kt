package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.ConnectionKind
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceId
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.api.RunPhase
import com.magichandy.mobile.core.api.Secret
import com.magichandy.mobile.core.api.UserIntent
import com.magichandy.mobile.core.motion.MotionFrame
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.DeviceDirectory
import com.magichandy.mobile.core.ports.DeviceRef
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import com.magichandy.mobile.core.ports.confirmIdleForConnection
import com.magichandy.mobile.transport.providers.payload
import com.magichandy.mobile.transport.providers.reply
import com.magichandy.mobile.transport.simulated.DebugFixture
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@OptIn(ExperimentalCoroutinesApi::class)
internal class HandyPlaybackTimingTest {
    @Test
    fun `one logical refill accounts for every MTU-limited Add round trip`() = runTest {
        val elapsed = AtomicLong()
        val clock =
            object : Clock {
                override fun elapsed() = elapsed.get().milliseconds

                override fun now() = Instant.fromEpochMilliseconds(elapsed.get())
            }
        val tails = mutableListOf<Long>()
        val wire =
            object : TimedHandyWire(clock) {
                override val maxPoints = 1
                override val stopTimeout = 2.seconds

                override suspend fun playTimestamp(allowed: () -> Boolean): Long? = null

                override suspend fun stop(allowed: () -> Boolean) = StopEvidence.Acknowledged

                override suspend fun dispatch(
                    command: HandyCommand,
                    allowed: () -> Boolean,
                ): WriteResult {
                    if (command is HandyCommand.Add) {
                        elapsed.addAndGet(400)
                        tails += command.tail
                    }
                    return WriteResult.Accepted
                }
            }
        val owner =
            HandyTransport(
                DeviceRef(
                    DeviceId("fixture"),
                    "Fixture",
                    DeviceFamily.Handy,
                    ConnectionKind.Bluetooth,
                ),
                wire,
            )
        val frames = (0L..4).map { MotionFrame(it * 250, Percent((it * 25).toInt())) }
        val batch =
            MotionBatch(frames, Percent(50), Limits(Percent(50), Percent(0), Percent(100)), true)
        val bridge =
            object : DeviceTransport by owner {
                override suspend fun establishIdle(fence: OutputFence): StopEvidence {
                    assertEquals(WriteResult.Accepted, owner.write(batch, fence))
                    return StopEvidence.Acknowledged
                }
            }
        bridge.confirmIdleForConnection()
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), tails)
        assertEquals(2000.milliseconds, owner.bufferedTiming?.recentCommandLatency)
        assertEquals(2000.milliseconds, owner.bufferedTiming?.startedAt)
    }

    @Test
    fun `cold Cloud clock sync precedes linked offset selection and is outside the Play round trip`() =
        runBlocking {
            val elapsed = AtomicLong(1000)
            val clock =
                object : Clock {
                    override fun elapsed() = elapsed.get().milliseconds

                    override fun now() = Instant.fromEpochMilliseconds(100000 + elapsed.get())
                }
            var plays = 0
            val client =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        when (request.url.encodedPath) {
                            "/servertime" -> {
                                elapsed.addAndGet(700)
                                request.reply("""{"server_time":100000}""")
                            }
                            "/hsp/play" -> {
                                plays++
                                val body = Json.parseToJsonElement(request.payload()).jsonObject
                                assertEquals(JsonPrimitive(700), body["start_time"])
                                assertEquals(JsonPrimitive(100700), body["server_time"])
                                elapsed.addAndGet(400)
                                request.reply("""{"result":{"play_state":2}}""")
                            }
                            else -> error("Unexpected fixture path")
                        }
                    }
                    .build()
            val wire =
                CloudWire(
                    Secret("fixture"),
                    Secret("fixture"),
                    client,
                    "https://fixture.invalid/".toHttpUrl(),
                    clock,
                )
            assertEquals(
                WriteResult.Accepted,
                wire.playAligned({ (elapsed.get() - 1000).toInt() }) { true },
            )
            assertEquals(1200.milliseconds, wire.bufferedTiming?.startedAt)
            assertEquals(400.milliseconds, wire.bufferedTiming?.recentCommandLatency)
            assertEquals(WriteResult.Uncertain, wire.playAligned({ null }) { true })
            assertEquals(1, plays)
        }

    @Test
    fun `real session refills from Play midpoint with measured latency and stops an expired timeline before dispatch`() =
        runTest {
            for (sleepThroughBuffer in listOf(false, true)) {
                var jump = 0L
                var playAt = -1L
                var initialTail = 0L
                val refills = mutableListOf<Long>()
                val id = DeviceId("handy")
                val f =
                    DebugFixture(this) { d ->
                        val clock =
                            object : Clock by d.clock {
                                override fun elapsed() = d.clock.elapsed() + jump.milliseconds
                            }
                        val wire =
                            object : TimedHandyWire(clock) {
                                override val maxPoints = 128
                                override val stopTimeout = 2.seconds

                                override suspend fun playTimestamp(allowed: () -> Boolean): Long? =
                                    null

                                override suspend fun stop(allowed: () -> Boolean) =
                                    StopEvidence.Acknowledged

                                override suspend fun dispatch(
                                    command: HandyCommand,
                                    allowed: () -> Boolean,
                                ): WriteResult {
                                    when (command) {
                                        is HandyCommand.Play -> {
                                            playAt = testScheduler.currentTime
                                            delay(1200)
                                        }
                                        is HandyCommand.Add -> {
                                            if (command.refilling)
                                                refills += testScheduler.currentTime
                                            else initialTail = command.frames.last().timeMillis
                                        }
                                        else -> Unit
                                    }
                                    return if (allowed()) WriteResult.Accepted
                                    else WriteResult.Stale
                                }
                            }
                        d.copy(
                            clock = clock,
                            devices =
                                object : DeviceDirectory by d.devices {
                                    override suspend fun connect(
                                        device: DeviceId,
                                        ownerScope: CoroutineScope,
                                    ): DeviceTransport =
                                        HandyTransport(
                                            saved.single { it.ref.id == device }.ref,
                                            wire,
                                        )
                                },
                        )
                    }
                f.onboard()
                f.connect(id)
                f.send(UserIntent.StartMotion)
                assertTrue(initialTail >= 1500 && playAt >= 0)
                advanceTimeBy(initialTail + 2000)
                runCurrent()
                if (sleepThroughBuffer) {
                    val sent = refills.size
                    assertTrue(sent > 0)
                    jump = initialTail + 4000
                    advanceTimeBy(initialTail + 2000)
                    runCurrent()
                    assertEquals(sent, refills.size)
                    assertEquals(RunPhase.Idle, f.core.state.value.run.phase)
                } else {
                    val ack = playAt + 1200
                    val midpoint = playAt + 600
                    assertEquals(maxOf(ack, midpoint + initialTail - 1650), refills.first())
                }
                f.scope.cancel()
                runCurrent()
            }
        }
}
