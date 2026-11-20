// Ported from internal/motion/sampling.go and internal/transport/intiface.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.ports.*
import kotlin.math.*

/** Fit the shared path to a particular actuator before the owner sees any endpoints. */
internal class LinearSampler(private val path: MotionPath, private val policy: LinearSampling) {
    private var tail: LinearPoint? = null
    val nextMillis
        get() = tail?.timeMillis?.plus(1) ?: 0

    fun through(end: Long, limits: Limits): List<LinearPoint> {
        val prior = tail
        if (prior != null && prior.timeMillis >= end) return emptyList()
        val floor = policy.intervalMillis
        val first = prior ?: LinearPoint(0, path.sampleAt(0).position)
        val last =
            first.timeMillis +
                maxOf(1, ceil((end - first.timeMillis).toDouble() / floor).toLong()) * floor
        val knots = path.knotTimes(first.timeMillis + 1, last).sorted()
        val span = limits.strokeHigh.value - limits.strokeLow.value
        val resolution = 10000.0 / (span * policy.steps)
        val points = mutableListOf(first)
        var at = first.timeMillis
        while (at < last) {
            val turn = knots.firstOrNull {
                it >= at + floor && it <= at + floor * 2 && last - it >= floor
            }
            val next = turn ?: minOf(at + floor, last)
            if (last - next in 1 until floor) break
            points += LinearPoint(next, path.sampleAt(next).position)
            at = next
        }
        if (points.last().timeMillis != last)
            points += LinearPoint(last, path.sampleAt(last).position)
        val fitted = mutableListOf(first)
        var index = 0
        while (index < points.lastIndex) {
            var right = index + 1
            while (
                right < points.lastIndex &&
                    points[right].timeMillis !in knots &&
                    points[right + 1].timeMillis - points[index].timeMillis <=
                        maxOf(policy.preferredMillis, floor * 2) &&
                    fits(points, index, right + 1, resolution)
            ) right++
            fitted += points[right]
            index = right
        }
        tail = fitted.last()
        return if (prior == null) fitted else fitted.drop(1)
    }

    private fun fits(points: List<LinearPoint>, start: Int, end: Int, resolution: Double): Boolean {
        val left = points[start]
        val right = points[end]
        val tolerance = (resolution / 2).coerceIn(0.3, 2.0)
        return (start + 1 until end).all { index ->
            val p = points[index]
            val t =
                (p.timeMillis - left.timeMillis).toDouble() / (right.timeMillis - left.timeMillis)
            abs(p.position - (left.position + t * (right.position - left.position))) <= tolerance
        }
    }
}
