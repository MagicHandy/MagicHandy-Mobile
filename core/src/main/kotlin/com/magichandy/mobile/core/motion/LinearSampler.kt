// Ported from internal/motion/{sampling,sampling_timing}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.ports.LinearPoint
import com.magichandy.mobile.core.ports.LinearSampling
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Fits immediate LinearCmd endpoints to the shared path and the selected actuator's capabilities.
 */
internal class LinearSampler(private val path: MotionPath, private val policy: LinearSampling) {
    private var tail: LinearPoint? = null
    val nextMillis: Long
        get() = tail?.timeMillis?.plus(1) ?: 0

    fun through(end: Long, limits: Limits, finite: Boolean = false): List<LinearPoint> {
        if (nextMillis > end) return emptyList()
        val span = limits.strokeHigh.value - limits.strokeLow.value
        // Convert one physical actuator step back through the active stroke window.
        val resolution = minOf(100.0, 10000.0 / (span * policy.steps))
        val timing =
            LinearTiming(
                policy.intervalMillis,
                resolution,
                (path as? ContinuousPath)?.maximumVelocity,
            )
        var previous = tail
        val result = buildList {
            do {
                val fitted =
                    if (finite) fitWindow(previous, end + 1, timing, finite = true).points
                    else nextChunk(previous, timing)
                addAll(if (previous == null) fitted else fitted.drop(1))
                previous = fitted.last()
            } while (!finite && requireNotNull(previous).timeMillis < end)
        }
        // Commit the cursor only after the entire requested batch could be represented.
        tail = previous
        return result
    }

    private data class Window(val points: List<LinearPoint>, val fixed: Set<Long>)

    private fun nextChunk(previous: LinearPoint?, timing: LinearTiming): List<LinearPoint> {
        val start = previous?.timeMillis?.plus(1) ?: 0L
        val window = maxOf(1000L, policy.intervalMillis)
        val target = start + window
        for (count in 2..3) {
            val (points, fixed) = fitWindow(previous, start + count * window, timing)
            val crossing = points.indexOfFirst { it.timeMillis >= target }
            val cut = if (crossing >= 0) crossing else points.lastIndex
            val natural =
                crossing >= 0 && (cut < points.lastIndex || points[cut].timeMillis in fixed)
            if (natural || count == 3) {
                require(points[cut].timeMillis >= start) { "No new linear coverage" }
                return points.subList(0, cut + 1)
            }
        }
        error("Missing final linear lookahead window")
    }

    private fun fitWindow(
        previous: LinearPoint?,
        end: Long,
        timing: LinearTiming,
        finite: Boolean = false,
    ): Window {
        val start = previous?.timeMillis?.plus(1) ?: 0L
        val authored = path.knotTimes(start, end)
        val interval = maxOf(5L, (end - start + 511) / 512)
        val times = authored.toSortedSet()
        times += start
        if (finite) times += end - 1
        for (at in ((start + interval - 1) / interval * interval) until end step interval) times +=
            at
        val probes = buildList {
            previous?.let { add(it) }
            for (at in times) add(LinearPoint(at, path.sampleAt(at).position))
        }
        val anchors = anchors(probes, authored, finite)
        val fixed =
            anchors
                .filter { it == 0 || it < probes.lastIndex || finite }
                .mapTo(mutableSetOf()) { probes[it].timeMillis }
        return Window(timing.apply(fit(probes, anchors), fixed), fixed)
    }

    private fun anchors(
        points: List<LinearPoint>,
        authored: Set<Long>,
        finite: Boolean = false,
    ): List<Int> {
        val result = sortedSetOf(0, points.lastIndex)
        var direction = 0
        for (i in 1..points.lastIndex) {
            val next = points[i].position.compareTo(points[i - 1].position)
            if (next == 0) {
                // Retain the entry and exit of an authored stationary leg.
                if (points[i - 1].timeMillis in authored) result += i - 1
                if (points[i].timeMillis in authored) result += i
            } else {
                if (direction != 0 && direction != next) result += i - 1
                direction = next
            }
        }
        val indexes = result.toMutableList()
        val finalFloor = if (finite) policy.intervalMillis else preferred
        if (
            indexes.size > 2 &&
                points.last().timeMillis !in authored &&
                points.last().timeMillis - points[indexes[indexes.lastIndex - 1]].timeMillis <
                    finalFloor
        )
            indexes.removeAt(indexes.lastIndex)
        return indexes
    }

    private val preferred = maxOf(policy.intervalMillis, policy.preferredMillis)

    private fun fit(probes: List<LinearPoint>, anchors: List<Int>): List<LinearPoint> = buildList {
        add(probes.first())
        fun leg(left: Int, right: Int) {
            var split = -1
            var worst = precisePositionTolerance
            for (i in left + 1 until right) {
                if (
                    probes[i].timeMillis - probes[left].timeMillis < preferred ||
                        probes[right].timeMillis - probes[i].timeMillis < preferred
                )
                    continue
                val deviation = linearError(probes[left], probes[right], probes[i])
                if (deviation > worst) {
                    worst = deviation
                    split = i
                }
            }
            if (split >= 0) {
                leg(left, split)
                leg(split, right)
            } else add(probes[right])
        }
        for ((a, b) in anchors.zipWithNext()) {
            require(probes[b].timeMillis - probes[a].timeMillis >= policy.intervalMillis) {
                "Linear reversal is shorter than the actuator's command interval"
            }
            leg(a, b)
        }
    }
}

private fun linearError(left: LinearPoint, right: LinearPoint, sample: LinearPoint): Double {
    val fraction =
        (sample.timeMillis - left.timeMillis).toDouble() / (right.timeMillis - left.timeMillis)
    return abs(sample.position - (left.position + fraction * (right.position - left.position)))
}

/**
 * Duplicate physical steps carry no curve detail. A submitted tail, true turn or hold stays fixed.
 */
private class LinearTiming(
    private val floorMillis: Long,
    private val resolution: Double,
    private val maximumVelocity: Double?,
) {
    private fun encoded(p: LinearPoint) =
        ((p.position / resolution).roundToLong() * resolution).coerceIn(0.0, 100.0)

    private fun minimum(a: LinearPoint, b: LinearPoint): Long {
        val velocity = maximumVelocity ?: return floorMillis
        return maxOf(
            floorMillis,
            ceil(abs(encoded(b) - encoded(a)) * 1000.0 / velocity - 1e-9).toLong(),
        )
    }

    fun apply(source: List<LinearPoint>, fixed: Set<Long>): List<LinearPoint> {
        val points = mutableListOf<LinearPoint>()
        for ((i, sample) in source.withIndex()) {
            val previous = points.lastOrNull()
            if (previous == null || encoded(previous) != encoded(sample)) points.add(sample)
            else if (previous.timeMillis !in fixed) points[points.lastIndex] = sample
            else if (sample.timeMillis in fixed || i == source.lastIndex) points.add(sample)
        }
        var start = 0
        while (start < points.lastIndex) {
            var end = start + 1
            while (end < points.lastIndex && points[end].timeMillis !in fixed) end++
            while (
                (start + 1..end).sumOf { minimum(points[it - 1], points[it]) } >
                    points[end].timeMillis - points[start].timeMillis
            ) {
                require(end - start > 1) {
                    "Linear reversal exceeds the actuator's timing or velocity limit"
                }
                val remove =
                    (start + 1 until end).minBy {
                        linearError(points[it - 1], points[it + 1], points[it])
                    }
                points.removeAt(remove)
                end--
            }
            val latest = LongArray(end - start + 1)
            latest[latest.lastIndex] = points[end].timeMillis
            for (i in end - 1 downTo start + 1) latest[i - start] =
                latest[i + 1 - start] - minimum(points[i], points[i + 1])
            for (i in start + 1 until end) {
                val earliest = points[i - 1].timeMillis + minimum(points[i - 1], points[i])
                points[i] =
                    points[i].copy(
                        timeMillis = points[i].timeMillis.coerceIn(earliest, latest[i - start])
                    )
            }
            start = end
        }
        return points
    }
}
