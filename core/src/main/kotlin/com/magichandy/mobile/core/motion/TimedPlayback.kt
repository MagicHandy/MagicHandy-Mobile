package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.ports.*
import com.magichandy.mobile.core.score.VibrationEnvelope
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shared finite buffer for timed transports. The output lane owns it and schedules each owner
 * independently.
 */
internal class TimedPlayback(
    private val d: CoreDependencies,
    private val engine: MotionEngine,
    private val stream: PlaybackStream,
    private val owners: List<TimedDevice>,
    private val follow: FollowTempo,
) {
    private var frames: List<MotionFrame> = emptyList()
    private var latest: MotionBatch? = null
    private var origin = Duration.ZERO

    fun append(batch: MotionBatch, sharedOrigin: Duration) {
        if (owners.isEmpty()) return
        val first = latest == null
        if (first) origin = sharedOrigin
        val prune = (d.clock.elapsed() - origin).inWholeMilliseconds - 200
        val start = frames.indexOfLast { it.timeMillis <= prune }.coerceAtLeast(0)
        frames = frames.subList(start, frames.size) + batch.frames
        latest = batch
        if (first)
            for (owner in owners) engine.launchWork(
                stream.epoch,
                WorkKind.Output,
                d.dispatchers.output,
            ) {
                send(owner)
            }
    }

    private suspend fun send(owner: TimedDevice) {
        var next = origin
        while (engine.state.value.plays(stream)) {
            val now = d.clock.elapsed()
            val at = (now - origin).inWholeMilliseconds
            val batch = latest ?: return
            if (at > frames.last().timeMillis) {
                engine.stop(null, stream = stream)
                return
            }
            val lane = engine.state.value.vibration
            val state = engine.state.value
            val tempo =
                if (owner.device.family != com.magichandy.mobile.core.api.DeviceFamily.VacuGlide)
                    batch.pace
                else {
                    val value =
                        state.tempo?.let { it.plan.level((now - it.since).inWholeMilliseconds) }
                            ?: follow.at(at)
                    com.magichandy.mobile.core.api.Percent(
                        kotlin.math.floor(value + 0.5).toInt().coerceIn(0, 100)
                    )
                }
            val slice =
                OutputSlice(
                    at,
                    frames,
                    batch.limits,
                    batch.pace,
                    lane?.envelope,
                    lane?.let { (now - it.since).inWholeMilliseconds } ?: at,
                    tempo,
                    state.air?.serial ?: 0L,
                    state.air?.steps ?: 0,
                )
            val result = withContext(d.dispatchers.io) { engine.output(stream, owner, slice) }
            if (result != WriteResult.Accepted) return
            next += owner.interval
            val elapsed = d.clock.elapsed()
            if (next <= elapsed) next = elapsed + owner.interval
            delay(next - elapsed)
        }
    }
}

internal data class VibrationLane(val envelope: VibrationEnvelope, val since: Duration)
