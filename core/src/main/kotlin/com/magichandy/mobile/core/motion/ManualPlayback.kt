// Ported from internal/motion/{engine,content}.go at 9a4c36cf.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.WriteResult
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** Manual output uses the verified bounded-ramp sampler for both ordinary runs and motor tests. */
internal class ManualPlayback(private val d: CoreDependencies, private val engine: MotionEngine) {
    fun play(
        expected: Epoch,
        owners: List<DeviceTransport>,
        limits: Limits,
        test: Boolean,
        started: (Percent) -> Unit,
    ) {
        val pace = Percent(minOf(if (test) 10 else 25, limits.topSpeed.value))
        if (pace.value == 0) {
            engine.stop(null, expected)
            return
        }
        if (test) scheduleMotorStop(expected)
        engine.launchWork(expected, WorkKind.Output, d.dispatchers.output) {
            stream(expected, owners, limits, pace, test, started)
        }
    }

    private fun scheduleMotorStop(expected: Epoch) {
        // A device can already be moving while the first write is awaiting its reply.
        engine.launchWork(expected, WorkKind.Output, d.dispatchers.stop) {
            delay(1500.milliseconds)
            engine.stop(null, expected)
        }
    }

    private suspend fun stream(
        expected: Epoch,
        owners: List<DeviceTransport>,
        limits: Limits,
        pace: Percent,
        test: Boolean,
        started: (Percent) -> Unit,
    ) {
        val knots = (0..12).map { i -> CurvePoint(i * 550L, if (i % 2 == 0) 0.0 else 100.0) }
        val sampler = BufferedSampler(MotionPlan(knots, pace.value, HandyModel.Original))
        var first = true
        var origin = d.clock.elapsed()
        while (engine.epoch == expected) {
            val batch = nextBatch(sampler, first, limits, pace, test)
            if (!sendToOwners(expected, owners, batch) || engine.epoch != expected) {
                engine.stop(null, expected)
                return
            }
            if (first) {
                origin = d.clock.elapsed()
                started(pace)
                first = false
            }
            if (test) return
            val lead = batch.frames.last().timeMillis.milliseconds - (d.clock.elapsed() - origin)
            if (lead <= 0.milliseconds) {
                engine.stop(null, expected)
                return
            }
            delay((lead - 1500.milliseconds).coerceAtLeast(0.milliseconds))
        }
    }

    private fun nextBatch(
        sampler: BufferedSampler,
        first: Boolean,
        limits: Limits,
        pace: Percent,
        test: Boolean,
    ): MotionBatch {
        val frames = buildList {
            addAll(sampler.nextChunk())
            if (first && last().timeMillis < 1500 && !test) addAll(sampler.nextChunk())
        }
        return MotionBatch(
            if (test) frames.takeWhile { it.timeMillis <= 1500 } else frames,
            pace,
            limits,
            first,
        )
    }

    private suspend fun sendToOwners(
        epoch: Epoch,
        owners: List<DeviceTransport>,
        batch: MotionBatch,
    ): Boolean = coroutineScope {
        owners
            .map { owner -> async(d.dispatchers.io) { engine.write(epoch, owner, batch) } }
            .awaitAll()
            .all { it == WriteResult.Accepted }
    }
}
