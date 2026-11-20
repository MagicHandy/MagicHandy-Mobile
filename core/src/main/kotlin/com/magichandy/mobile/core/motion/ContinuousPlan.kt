// Ported from internal/motion/{prepared,plan,sampling}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.math.sqrt

internal class ContinuousPlan(
    private val legs: List<StrokeCurve>,
    val periodMillis: Long,
    override val maximumVelocity: Double,
    override val accelerationBound: Double,
) : ContinuousPath {
    private val starts =
        LongArray(legs.size + 1).apply {
            for (i in legs.indices) this[i + 1] = this[i] + legs[i].duration
        }
    private val duration = starts.last()
    private val knots =
        LongArray(starts.size) { (starts[it].toDouble() / duration * periodMillis).roundToLong() }

    fun phaseAt(at: Long) = (at.toDouble() / periodMillis) % 1.0

    fun stateAt(at: Long): CurveState {
        val time = phaseAt(at) * duration
        val found = starts.binarySearch(time.toLong())
        val index = (if (found >= 0) found else -found - 2).coerceIn(0, legs.lastIndex)
        val leg = legs[index]
        val local = time - starts[index]
        val foundKnot = leg.times.binarySearch(local.toLong())
        val k =
            (if (foundKnot >= 0) foundKnot else -foundKnot - 2).coerceIn(0, leg.segments.lastIndex)
        val segment = leg.segments[k]
        val u = ((local - leg.times[k]) / segment.duration).coerceIn(0.0, 1.0)
        val scale = duration.toDouble() / periodMillis
        return CurveState(
            leg.sample(local),
            segment.velocity(u) * scale,
            segment.acceleration(u) * scale * scale,
        )
    }

    override fun sampleAt(timeMillis: Long): CurvePoint {
        val time = phaseAt(timeMillis) * duration
        val found = starts.binarySearch(time.toLong())
        val i = (if (found >= 0) found else -found - 2).coerceIn(0, legs.lastIndex)
        return CurvePoint(
            timeMillis,
            (100.0 * legs[i].sample(time - starts[i]) / 100.0).coerceIn(0.0, 100.0),
        )
    }

    override fun knotTimes(start: Long, end: Long): Set<Long> = buildSet {
        if (end <= start) return@buildSet
        val firstCycle = start / periodMillis
        for (cycle in firstCycle..(end - 1) / periodMillis) {
            val origin = cycle * periodMillis
            val lower = (start - origin).coerceAtLeast(0)
            val found = knots.binarySearch(lower)
            var i = if (found >= 0) found else -found - 1
            while (i < knots.size && origin + knots[i] < end) add(origin + knots[i++])
        }
    }

    companion object {
        fun prepared(legs: List<StrokeCurve>, rate: Double, budget: StrokeBudget): ContinuousPlan {
            val duration = legs.sumOf { it.duration }
            val peaks = legs.fold(CurvePeaks(0.0, 0.0, 0.0)) { acc, c -> acc.include(c.peaks) }
            val travel = legs.sumOf { abs(it.states.last().position - it.states.first().position) }
            val factor =
                maxOf(
                    1.0,
                    travel * 1000 / duration / rate,
                    peaks.velocity * 1000 / budget.maximumVelocity,
                    sqrt(peaks.acceleration * 1e6 / budget.acceleration),
                    Math.cbrt(peaks.jerk * 1e9 / budget.jerk),
                    100.0 / legs.minOf { it.duration },
                )
            val period = ceil(duration * factor).toLong()
            val scale = duration.toDouble() / period
            return ContinuousPlan(
                legs,
                period,
                budget.maximumVelocity,
                maxOf(7500.0, peaks.acceleration * scale * scale * 1e6),
            )
        }
    }
}

internal interface ContinuousPath : MotionPath {
    val usesWireTiming: Boolean
        get() = true

    val maximumVelocity: Double
    val accelerationBound: Double
}
