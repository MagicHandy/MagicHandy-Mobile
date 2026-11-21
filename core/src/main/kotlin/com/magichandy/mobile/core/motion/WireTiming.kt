// Ported from internal/motion/{sampling_timing,sampling_tolerance,sampling_refine}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Fit integer positions and times jointly, keeping submitted tails and real reversals fixed. */
internal class WireTiming(
    private val probes: List<CurvePoint>,
    private val mandatory: Set<Long>,
    private val path: ContinuousPath,
    private val positionTolerance: Double,
) {
    private val acceleration =
        path.accelerationBoundBetween(probes.first().timeMillis, probes.last().timeMillis)
    private val tolerance = tolerance(0, probes.lastIndex)

    private fun tolerance(first: Int, last: Int): Double {
        var gap = 0L
        for (i in first + 1..last) gap = maxOf(gap, probes[i].timeMillis - probes[i - 1].timeMillis)
        return 0.5 + maxOf(0.0, positionTolerance - acceleration * gap * gap / 8e6)
    }

    fun fit(samples: List<CurvePoint>, previous: CurvePoint?): List<CurvePoint> {
        val trimmed = removeDuplicates(if (previous == null) samples else samples.drop(1))
        if (previous != null) {
            var i = 0
            while (
                i < trimmed.lastIndex &&
                    trimmed[i].encoded == previous.encoded &&
                    trimmed[i].timeMillis !in mandatory
            ) i++
            if (i > 0) trimmed.subList(0, i).clear()
            trimmed.add(0, previous)
        }
        val fitted = mutableListOf(trimmed.first())
        for (i in 1 until trimmed.size) refine(trimmed[i - 1], trimmed[i], fitted)
        var start = 0
        while (start < fitted.lastIndex) {
            var end = start + 1
            while (end < fitted.lastIndex && fitted[end].timeMillis !in mandatory) end++
            end = makeRoom(fitted, start, end)
            align(fitted, start, end)
            start = end
        }
        return if (previous == null) fitted else fitted.drop(1)
    }

    private fun removeDuplicates(samples: List<CurvePoint>): MutableList<CurvePoint> {
        val result = mutableListOf<CurvePoint>()
        for ((index, point) in samples.withIndex()) {
            val last = result.lastOrNull()
            if (last == null || last.encoded != point.encoded) result.add(point)
            else if (last.timeMillis !in mandatory) result[result.lastIndex] = point
            else if (point.timeMillis in mandatory || index == samples.lastIndex) result.add(point)
        }
        return result
    }

    private fun firstAt(at: Long): Int {
        val found = probes.binarySearchBy(at) { it.timeMillis }
        return if (found >= 0) found else -found - 1
    }

    private fun refine(left: CurvePoint, right: CurvePoint, out: MutableList<CurvePoint>) {
        var worst = 0.0
        var error = -1.0
        var candidate: Int? = null
        for (i in firstAt(left.timeMillis + 1) until firstAt(right.timeMillis)) {
            val probe = probes[i]
            val deviation = abs(probe.position - interpolate(left, right, probe.timeMillis, true))
            worst = maxOf(worst, deviation)
            if (
                probe.encoded != left.encoded && probe.encoded != right.encoded && deviation > error
            ) {
                error = deviation
                candidate = i
            }
        }
        if (worst > tolerance && candidate != null) {
            refine(left, probes[candidate], out)
            refine(probes[candidate], right, out)
        } else out.add(right)
    }

    private fun minimum(a: CurvePoint, b: CurvePoint) =
        maxOf(1L, ceil(abs(b.encoded - a.encoded) * 1000 / path.maximumVelocity - 1e-9).toLong())

    private fun makeRoom(points: MutableList<CurvePoint>, start: Int, last: Int): Int {
        var end = last
        while (
            (start + 1..end).sumOf { minimum(points[it - 1], points[it]) } >
                points[end].timeMillis - points[start].timeMillis
        ) {
            require(end - start > 1) { "Quantized reversal exceeds the velocity limit" }
            val drop =
                (start + 1 until end).minBy { i ->
                    abs(
                        points[i].position -
                            interpolate(points[i - 1], points[i + 1], points[i].timeMillis, false)
                    )
                }
            points.removeAt(drop)
            end--
        }
        return end
    }

    private fun align(points: MutableList<CurvePoint>, start: Int, end: Int) {
        val latest = LongArray(end - start + 1)
        val original = LongArray(latest.size) { points[start + it].timeMillis }
        latest[latest.lastIndex] = points[end].timeMillis
        for (i in end - 1 downTo start + 1) latest[i - start] =
            latest[i + 1 - start] - minimum(points[i], points[i + 1])
        val crossing = monotone(points[start].timeMillis, points[end].timeMillis)
        for (i in start + 1 until end) {
            val sample = points[i]
            val at = if (crossing) crossingTime(points, i, start, end) else sample.timeMillis
            points[i] =
                sample.copy(
                    timeMillis =
                        minOf(
                            latest[i - start],
                            maxOf(points[i - 1].timeMillis + minimum(points[i - 1], sample), at),
                        )
                )
        }
        if (crossing)
            for (i in start + 1..end) {
                val left = points[i - 1]
                val right = points[i]
                if (
                    left.timeMillis == original[i - start - 1] &&
                        right.timeMillis == original[i - start]
                )
                    continue
                var lo = firstAt(left.timeMillis)
                if (lo > 0 && (lo == probes.size || probes[lo].timeMillis > left.timeMillis)) lo--
                require(
                    fits(
                        left,
                        right,
                        tolerance(lo, minOf(firstAt(right.timeMillis), probes.lastIndex)),
                    )
                ) {
                    "Retimed stroke exceeds interpolation tolerance"
                }
            }
    }

    private fun monotone(start: Long, end: Long): Boolean {
        var direction = 0
        for (i in firstAt(start) + 1 until probes.size) {
            if (probes[i].timeMillis > end) break
            val next = probes[i].position.compareTo(probes[i - 1].position)
            if (next != 0 && direction != 0 && direction != next) return false
            if (next != 0) direction = next
        }
        return true
    }

    private fun crossingTime(samples: List<CurvePoint>, index: Int, start: Int, end: Int): Long {
        val sample = samples[index]
        val position = sample.encoded.toDouble()
        var distance = Double.POSITIVE_INFINITY
        var best = sample.timeMillis
        for (i in firstAt(samples[start].timeMillis) + 1 until probes.size) {
            val a = probes[i - 1]
            val b = probes[i]
            if (b.timeMillis > samples[end].timeMillis) break
            if (
                a.position == b.position ||
                    position !in minOf(a.position, b.position)..maxOf(a.position, b.position)
            )
                continue
            val at =
                a.timeMillis +
                    (position - a.position) / (b.position - a.position) *
                        (b.timeMillis - a.timeMillis)
            val gap = abs(at - sample.timeMillis)
            if (gap < distance) {
                best = at.roundToLong()
                distance = gap
            }
        }
        val point = sample.copy(timeMillis = best)
        val left = samples[index - 1]
        val right = samples[index + 1]
        return if (
            best - left.timeMillis >= minimum(left, point) &&
                right.timeMillis - best >= minimum(point, right) &&
                fits(left, point, tolerance) &&
                fits(point, right, tolerance)
        )
            best
        else sample.timeMillis
    }

    private fun fits(left: CurvePoint, right: CurvePoint, tolerance: Double): Boolean {
        if (right.timeMillis <= left.timeMillis) return false
        for (i in firstAt(left.timeMillis) until probes.size) {
            val p = probes[i]
            if (p.timeMillis > right.timeMillis) break
            if (abs(interpolate(left, right, p.timeMillis, true) - p.position) > tolerance)
                return false
        }
        return true
    }

    private fun interpolate(a: CurvePoint, b: CurvePoint, at: Long, quantized: Boolean): Double {
        val x = if (quantized) a.encoded.toDouble() else a.position
        val y = if (quantized) b.encoded.toDouble() else b.position
        return x + (y - x) * ((at - a.timeMillis).toDouble() / (b.timeMillis - a.timeMillis))
    }

    private val CurvePoint.encoded
        get() = position.roundToInt()
}
