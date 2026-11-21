// Ported from internal/motion/sampling.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.Percent
import kotlin.math.abs
import kotlin.math.roundToInt

/** Fits buffered integer-percent frames, retaining mandatory authored knots across append seams. */
internal interface MotionPath {
    fun sampleAt(timeMillis: Long): CurvePoint

    fun knotTimes(start: Long, end: Long): Set<Long>
}

internal const val precisePositionTolerance = 0.3

internal class BufferedSampler(
    private val plan: MotionPath,
    private val preferSparseGeneratedMotion: Boolean = false,
) {
    private var last: CurvePoint? = null
    val nextSampleMillis: Long
        get() = last?.timeMillis?.plus(1) ?: 0

    fun nextChunk(): List<MotionFrame> {
        val start = nextSampleMillis
        val tail = start + 1000
        for (windows in 2..3) {
            val end = start + windows * 1000
            val mandatory = plan.knotTimes(start, end)
            val fitted = fitWindow(start, end, mandatory)
            val crossing = fitted.indexOfFirst { it.timeMillis >= tail }
            val cutoff = if (crossing < 0) fitted.lastIndex else crossing
            val natural =
                crossing >= 0 &&
                    (crossing < fitted.lastIndex || fitted[crossing].timeMillis in mandatory)
            if (natural || windows == 3) {
                require(cutoff < 128) { "Too many essential points in the buffered window" }
                last = fitted[cutoff]
                return fitted.take(cutoff + 1).map {
                    MotionFrame(it.timeMillis, Percent(it.position.roundToInt()))
                }
            }
        }
        error("Missing final lookahead window")
    }

    private fun fitWindow(start: Long, end: Long, mandatory: Set<Long>): List<CurvePoint> {
        val interval = maxOf(5L, (end - start + 511) / 512)
        val times = mandatory.toSortedSet()
        times += start
        for (at in ((start + interval - 1) / interval * interval) until end step interval) times +=
            at
        val previous = last
        val reference = listOfNotNull(previous) + times.map(plan::sampleAt)
        val tolerance =
            if (preferSparseGeneratedMotion && plan is ContinuousPath && plan.usesWireTiming)
                plan.sparsePositionTolerance(reference.first().timeMillis, end)
            else precisePositionTolerance
        val firstPass = simplify(reference, mandatory, quantize = false, tolerance)
        val fitted = simplify(firstPass, mandatory, quantize = true, tolerance + 0.5)
        if (plan is ContinuousPath && plan.usesWireTiming)
            return WireTiming(reference, mandatory, plan, tolerance).fit(fitted, previous)
        return if (previous == null) fitted else fitted.drop(1)
    }

    private fun simplify(
        samples: List<CurvePoint>,
        mandatory: Set<Long>,
        quantize: Boolean,
        tolerance: Double,
    ): List<CurvePoint> {
        val keep = BooleanArray(samples.size)
        val anchors =
            samples.indices.filter {
                it == 0 || it == samples.lastIndex || samples[it].timeMillis in mandatory
            }
        for (index in anchors) keep[index] = true
        fun mark(start: Int, end: Int) {
            val a = samples[start]
            val b = samples[end]
            val left = if (quantize) a.position.roundToInt().toDouble() else a.position
            val right = if (quantize) b.position.roundToInt().toDouble() else b.position
            var maximum = 0.0
            var split = start
            for (i in start + 1 until end) {
                val fraction =
                    (samples[i].timeMillis - a.timeMillis).toDouble() /
                        (b.timeMillis - a.timeMillis)
                var score =
                    abs(samples[i].position - (left + (right - left) * fraction)) / tolerance
                if (quantize && plan is ContinuousPath && plan.usesWireTiming && left != right) {
                    val ratio = ((samples[i].position - left) / (right - left)).coerceIn(0.0, 1.0)
                    val duration = b.timeMillis - a.timeMillis
                    val timing =
                        abs(samples[i].timeMillis - (a.timeMillis + ratio * duration)) /
                            maxOf(12.0, duration * 0.06)
                    score = maxOf(score, timing)
                }
                if (score > maximum) {
                    maximum = score
                    split = i
                }
            }
            if (maximum <= 1.0) return
            keep[split] = true
            mark(start, split)
            mark(split, end)
        }
        anchors.zipWithNext().forEach { (start, end) -> mark(start, end) }
        return samples.filterIndexed { index, _ -> keep[index] }
    }
}
