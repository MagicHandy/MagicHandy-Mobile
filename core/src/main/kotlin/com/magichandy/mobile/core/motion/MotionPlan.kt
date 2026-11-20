// Ported from internal/motion/{plan,content}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.math.sign

internal enum class HandyModel(
    val strokeMillimeters: Double,
    val maximumMillimetersPerSecond: Double,
) {
    Original(110.0, 400.0),
    Standard(125.0, 400.0),
    Pro(125.0, 450.0);

    companion object {
        /**
         * The least capable participating model sets linked timeline speed, as in
         * WithHandyCalibration.
         */
        fun calibration(names: Collection<String>): HandyModel =
            when {
                "handy_2_standard" in names -> Standard
                "handy_2_pro" in names -> Pro
                else -> Original
            }
    }
}

/** M0 bounded-ramp loop. The semantic score compiler provides its authored coordinates. */
internal class MotionPlan(
    authored: List<CurvePoint>,
    speedPercent: Int,
    model: HandyModel,
    val handoffMillis: Long = 0,
    private val phaseOffset: Double = 0.0,
) : MotionPath {
    private val points = authored.toList()

    init {
        require(points.size >= 2 && points.first().timeMillis == 0L)
        require(points.zipWithNext().all { (a, b) -> a.timeMillis < b.timeMillis })
        require(points.last().position == points.first().position)
        require(speedPercent in 1..100)
    }

    private val cycleMillis = points.last().timeMillis
    val periodMillis: Long
    private val curve: BoundedCurve

    init {
        val travel = points.zipWithNext().sumOf { (a, b) -> abs(b.position - a.position) }
        val rate =
            (32 + (model.maximumMillimetersPerSecond - 32) * ((speedPercent - 1) / 99.0)) /
                model.strokeMillimeters * 100
        val requested = if (travel > 0) travel * 1000 / rate else cycleMillis * 100.0 / speedPercent
        periodMillis = maxOf(requested.roundToLong(), minimumSafePeriod())
        curve = curveFor(periodMillis)
    }

    private fun curveFor(period: Long) = BoundedCurve(points, period.toDouble() / cycleMillis)

    private fun minimumSafePeriod(): Long {
        val gap = reversalGap()
        var lower =
            maxOf(500L, if (gap > 0) ceil(cycleMillis.toDouble() * 100 / gap).toLong() else 0L)
        fun withinEnvelope(period: Long): Boolean =
            curveFor(period).peakAcceleration() <= 7500 * 1.001
        if (withinEnvelope(lower)) return lower
        var upper = maxOf(cycleMillis, lower + 1)
        while (!withinEnvelope(upper)) upper = Math.multiplyExact(upper, 2)
        while (lower + 1 < upper) {
            val candidate = lower + (upper - lower) / 2
            if (withinEnvelope(candidate)) upper = candidate else lower = candidate
        }
        return upper
    }

    private fun reversalGap(): Long {
        val turns = mutableListOf<Long>()
        var first = 0.0
        var previous = 0.0
        for ((a, b) in points.zipWithNext()) {
            val direction = (b.position - a.position).sign
            if (direction == 0.0) continue
            if (first == 0.0) first = direction
            if (previous == -direction) turns += a.timeMillis
            previous = direction
        }
        if (first != previous) turns += cycleMillis
        if (turns.isEmpty()) return 0
        return (turns + (turns.first() + cycleMillis)).zipWithNext { a, b -> b - a }.min()
    }

    fun phaseAt(timeMillis: Long): Double =
        (phaseOffset + (timeMillis - handoffMillis).coerceAtLeast(0).toDouble() / periodMillis) %
            1.0

    override fun sampleAt(timeMillis: Long): CurvePoint {
        val phase = phaseAt(timeMillis)
        val raw = curve.positionAt(phase * cycleMillis)
        return CurvePoint(timeMillis, (100.0 * raw / 100.0).coerceIn(0.0, 100.0))
    }

    override fun knotTimes(start: Long, end: Long): Set<Long> = buildSet {
        for (point in points) {
            val phase = point.timeMillis.toDouble() / cycleMillis
            var cycle =
                ceil((start - handoffMillis).toDouble() / periodMillis - phase + phaseOffset)
                    .toLong()
            while (true) {
                val at =
                    handoffMillis + ((phase - phaseOffset + cycle) * periodMillis).roundToLong()
                if (at >= end) break
                if (at >= start) add(at)
                cycle++
            }
        }
    }
}
