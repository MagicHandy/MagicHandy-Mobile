// Ported from internal/motion/{engine,content,plan}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.CoreDependencies
import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.ports.DeviceTransport
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.WriteResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Manual output uses the verified bounded-ramp sampler for both ordinary runs and motor tests. */
internal class ManualPlayback(private val d: CoreDependencies, private val engine: MotionEngine) {
    private data class Playing(
        val stream: PlaybackStream,
        val initialPosition: PlaybackPosition,
        val timeline: ScoreTimeline? = null,
        val origin: Duration = Duration.ZERO,
    )

    private val playing = MutableStateFlow<Playing?>(null)

    fun position(stream: PlaybackStream): PlaybackPosition {
        val live = playing.value?.takeIf { it.stream == stream } ?: return PlaybackPosition()
        return live.timeline?.positionAt((d.clock.elapsed() - live.origin).inWholeMilliseconds)
            ?: live.initialPosition
    }

    fun play(
        stream: PlaybackStream,
        owners: List<DeviceTransport>,
        model: HandyModel,
        test: Boolean,
        position: PlaybackPosition,
        commanded: (MotionBatch, ManualTarget) -> Unit,
    ) {
        if (test) scheduleMotorStop(stream)
        val initialPosition = Playing(stream, position)
        playing.value = initialPosition
        val output =
            engine.launchWork(stream.epoch, WorkKind.Output, d.dispatchers.output) {
                val initial =
                    ManualTarget.from(
                            d.settings.state.value.values.limits,
                            test,
                            engine.state.value.edgeRequested,
                        )
                        .pace
                if (initial.value == 0) engine.stop(null, stream = stream)
                else
                    send(
                        initialPosition,
                        owners,
                        ScoreTimeline(initial.value, model, position),
                        test,
                        commanded,
                    )
            }
        output.invokeOnCompletion {
            playing.value?.takeIf { it.stream == stream }?.let { playing.compareAndSet(it, null) }
        }
    }

    private fun scheduleMotorStop(stream: PlaybackStream) {
        // A device can already be moving while the first write is awaiting its reply.
        engine.launchWork(stream.epoch, WorkKind.Output, d.dispatchers.stop) {
            delay(1500.milliseconds)
            engine.stop(null, stream = stream)
        }
    }

    private suspend fun send(
        initialPosition: Playing,
        owners: List<DeviceTransport>,
        timeline: ScoreTimeline,
        test: Boolean,
        commanded: (MotionBatch, ManualTarget) -> Unit,
    ) {
        val stream = initialPosition.stream
        val sampler = BufferedSampler(timeline)
        val timed =
            TimedPlayback(
                d,
                engine,
                stream,
                owners.filterIsInstance<com.magichandy.mobile.core.ports.TimedDevice>(),
                FollowTempo(timeline, timeline.maximumVelocity),
            )
        val buffered = owners.filterNot { it is com.magichandy.mobile.core.ports.TimedDevice }
        val linear =
            buffered
                .mapNotNull { it.linearSampling }
                .distinct()
                .associateWith { LinearSampler(timeline, it) }
        var previous: Limits? = null
        var previousTarget: ManualTarget? = null
        var origin = d.clock.elapsed()
        while (engine.state.value.plays(stream)) {
            val limits = d.settings.state.value.values.limits
            val target =
                timeline.update(
                    engine.state.value,
                    limits,
                    test,
                    maxOf(
                        sampler.nextSampleMillis,
                        linear.values.maxOfOrNull { it.nextMillis } ?: 0,
                    ),
                    (d.clock.elapsed() - origin).inWholeMilliseconds,
                ) ?: return engine.stop(null, stream = stream)
            val batch =
                try {
                    nextBatch(sampler, previous == null, limits, target.pace, test)
                } catch (_: IllegalArgumentException) {
                    com.magichandy.mobile.core.ports.DiagnosticReporter.record(
                        com.magichandy.mobile.core.ports.DiagnosticKind.TransportWriteFailed
                    )
                    return engine.stop(null, stream = stream)
                }
            if (
                batch.isFirst &&
                    !sendToOwners(stream, owners.filter { it.startupPosition != null }, batch, true)
            )
                return engine.stop(null, stream = stream)
            val linearBatches = linear.mapValues { (_, fit) ->
                batch.copy(linearPoints = fit.through(batch.frames.last().timeMillis, limits))
            }
            val linked = batch.isFirst && owners.size > 1 && buffered.any { it.linkedStart != null }
            if (
                linked &&
                    !sendToOwners(
                        stream,
                        buffered.filter { it.linkedStart != null },
                        batch.copy(deferPlay = true),
                        linearBatches = linearBatches,
                    )
            )
                return engine.stop(null, stream = stream)
            if (linked) origin = d.clock.elapsed()
            if (
                !sendToOwners(
                    stream,
                    buffered,
                    batch,
                    origin = origin.takeIf { linked },
                    linearBatches = linearBatches,
                ) || !engine.state.value.plays(stream)
            ) {
                engine.stop(null, stream = stream)
                return
            }
            if (previous == null) {
                if (!linked) origin = d.clock.elapsed()
                playing.compareAndSet(
                    initialPosition,
                    initialPosition.copy(timeline = timeline, origin = origin),
                )
            }
            timed.append(batch, origin)
            if (previous != limits || previousTarget != target) commanded(batch, target)
            previousTarget = target
            previous = limits
            if (test) return
            val lead = batch.frames.last().timeMillis.milliseconds - (d.clock.elapsed() - origin)
            if (lead <= 0.milliseconds) {
                engine.stop(null, stream = stream)
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
        stream: PlaybackStream,
        owners: List<DeviceTransport>,
        batch: MotionBatch,
        prepare: Boolean = false,
        origin: Duration? = null,
        linearBatches: Map<com.magichandy.mobile.core.ports.LinearSampling, MotionBatch> =
            emptyMap(),
    ): Boolean = coroutineScope {
        owners
            .map { owner ->
                async(d.dispatchers.io) {
                    if (origin != null && owner.linkedStart != null)
                        engine.startBuffered(stream, owner, origin)
                    else
                        engine.write(
                            stream,
                            owner,
                            linearBatches[owner.linearSampling]?.copy(deferPlay = batch.deferPlay)
                                ?: batch,
                            prepare,
                        )
                }
            }
            .awaitAll()
            .all { it == WriteResult.Accepted }
    }
}
