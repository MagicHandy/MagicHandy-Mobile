// Ported from internal/motion/tempo_engine.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.MotionSource
import com.magichandy.mobile.core.ports.Epoch
import com.magichandy.mobile.core.score.TempoPlan
import com.magichandy.mobile.core.score.TempoScore
import kotlin.math.abs
import kotlin.time.Duration

internal data class TempoLane(val plan: TempoPlan, val since: Duration, val source: MotionSource)

internal data class AirAdjustment(val serial: Long, val steps: Int)

/** Two-second centered travel window smoothed over one second on the selected Handy calibration. */
internal class FollowTempo(private val path: ScoreTimeline, private val top: Double) {
    private val travel = DoubleArray(121)

    fun at(at: Long): Double {
        val start = (at - 1500).coerceAtLeast(0) / 25 * 25
        val end = at + 1500
        val count = ((end - start) / 25).toInt() + 1
        var previous = path.sampleAt(start).position
        for (i in 1 until count) {
            val position = path.sampleAt(start + i * 25).position
            travel[i] = travel[i - 1] + abs(position - previous)
            previous = position
        }
        var rate = 0.0
        for (offset in -500..500 step 100) {
            val t = (at + offset).coerceAtLeast(0)
            val first = ((t - 1000 - start) / 25).toInt().coerceIn(0, count - 1)
            val last = ((t + 1000 - start) / 25).toInt().coerceIn(0, count - 1)
            if (last > first) rate += (travel[last] - travel[first]) * 40 / (last - first)
        }
        val level = rate / 11 / top * 100
        return if (level < 1) 0.0 else level.coerceAtMost(100.0)
    }
}

internal fun EngineState.edgeTempo(expected: Epoch, enabled: Boolean, now: Duration): EngineState {
    val next = withEdge(expected, enabled)
    if (edgeRequested == next.edgeRequested || tempo == null) return next
    if (!enabled)
        return next.copy(
            tempo = beforeTempoHold?.copy(since = now) ?: tempo,
            beforeTempoHold = null,
        )
    val level = tempo.plan.level((now - tempo.since).inWholeMilliseconds)
    if (level < 1) return this
    val slow = TempoScore.steady(kotlin.math.floor(level * 0.4 + 0.5).toInt().coerceIn(1, 20))
    return next.copy(
        beforeTempoHold = tempo,
        tempo =
            TempoLane(
                TempoPlan(slow, level),
                now,
                MotionSource.You,
            ),
    )
}

internal fun EngineState.withTempo(
    expected: Epoch,
    score: TempoScore?,
    steps: Int,
    source: MotionSource,
    now: Duration,
): EngineState? {
    if (
        epoch != expected ||
            pausedPosition != null ||
            members.none {
                owners[it]?.device?.family == com.magichandy.mobile.core.api.DeviceFamily.VacuGlide
            } ||
            airBalance + steps !in -3..3
    )
        return null
    val lane =
        score?.let {
            val start =
                tempo?.let { previous ->
                    previous.plan.level((now - previous.since).inWholeMilliseconds)
                } ?: 0.0
            TempoLane(TempoPlan(it, start), now, source)
        } ?: tempo
    val nextAir = if (steps == 0) air else AirAdjustment((air?.serial ?: 0) + 1, steps)
    return copy(tempo = lane, air = nextAir, airBalance = airBalance + steps)
}
