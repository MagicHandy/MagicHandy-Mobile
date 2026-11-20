// Ported from internal/motion/{content,reversal_profile}.go at 9a4c36cf.
package com.magichandy.mobile.core.motion

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

/** A periodic monotone cubic with acceleration-budgeted guide knots surrounding each reversal. */
internal class BoundedCurve(authored: List<CurvePoint>, private val timeFactor: Double) {
    private val guideSlopes = mutableMapOf<Long, Double>()
    private val points = withReversalGuides(authored)
    private val slopes =
        DoubleArray(points.size) { index ->
            guideSlopes[points[index].timeMillis] ?: interiorSlope(index)
        }

    private fun withReversalGuides(authored: List<CurvePoint>): List<CurvePoint> {
        val result = authored.toMutableList()
        val directions = authored.zipWithNext { left, right ->
            (right.position - left.position).sign
        }
        for (i in directions.indices) {
            val direction = directions[i]
            if (direction == 0.0) continue
            val left = authored[i]
            val right = authored[i + 1]
            val duration = right.timeMillis - left.timeMillis
            val blend = minOf(duration / 3, reversalBlend(right.position - left.position, duration))
            val before = directions[(i + directions.size - 1) % directions.size]
            val after = directions[(i + 1) % directions.size]
            val leftBlend = if (before == -direction) blend else 0
            val rightBlend = if (after == -direction) blend else 0
            val velocity =
                (right.position - left.position) / (duration - (leftBlend + rightBlend) / 2.0)
            fun addGuide(at: Long, position: Double) {
                result.add(CurvePoint(at, position))
                guideSlopes[at] = velocity
            }
            if (leftBlend > 0)
                addGuide(left.timeMillis + leftBlend, left.position + velocity * leftBlend / 2)
            if (rightBlend > 0)
                addGuide(right.timeMillis - rightBlend, right.position - velocity * rightBlend / 2)
        }
        return result.sortedBy { it.timeMillis }
    }

    private fun reversalBlend(delta: Double, duration: Long): Long {
        val budget = abs(delta) / (0.005 * timeFactor * timeFactor)
        val discriminant = duration.toDouble() * duration - 4 * budget
        val blend = if (discriminant > 0) (duration - sqrt(discriminant)) / 2 else duration / 2.0
        val minimum = max(1, ceil(4 / timeFactor).toLong())
        return ceil(blend).toLong().coerceIn(minimum, max(minimum, ceil(75 / timeFactor).toLong()))
    }

    private fun interiorSlope(index: Int): Double {
        val left = if (index == 0) points.size - 2 else index - 1
        val right = if (index == points.lastIndex) 0 else index
        val h0 = (points[left + 1].timeMillis - points[left].timeMillis).toDouble()
        val h1 = (points[right + 1].timeMillis - points[right].timeMillis).toDouble()
        val d0 = (points[left + 1].position - points[left].position) / h0
        val d1 = (points[right + 1].position - points[right].position) / h1
        if (d0 * d1 <= 0) return 0.0
        val w1 = 2 * h1 + h0
        val w2 = h1 + 2 * h0
        return (w1 + w2) / (w1 / d0 + w2 / d1)
    }

    fun positionAt(at: Double): Double {
        val index = points.binarySearch { it.timeMillis.toDouble().compareTo(at) }
        if (index >= 0) return points[index].position
        val right = -index - 1
        val left = right - 1
        val width = (points[right].timeMillis - points[left].timeMillis).toDouble()
        val u = (at - points[left].timeMillis) / width
        val h00 = 2 * u * u * u - 3 * u * u + 1
        val h10 = u * u * u - 2 * u * u + u
        val h01 = -2 * u * u * u + 3 * u * u
        val h11 = u * u * u - u * u
        return (h00 * points[left].position +
                h10 * width * slopes[left] +
                h01 * points[right].position +
                h11 * width * slopes[right])
            .coerceIn(0.0, 100.0)
    }

    fun peakAcceleration(): Double {
        var peak = 0.0
        for (i in 0 until points.lastIndex) {
            val h = (points[i + 1].timeMillis - points[i].timeMillis).toDouble()
            // The cubic's second derivative is linear, so its absolute peak is at an endpoint.
            for (u in 0..1) {
                val numerator =
                    (12 * u - 6) * points[i].position +
                        (6 * u - 4) * h * slopes[i] +
                        (-12 * u + 6) * points[i + 1].position +
                        (6 * u - 2) * h * slopes[i + 1]
                peak = max(peak, abs(numerator / (h * h)))
            }
        }
        return peak / (timeFactor * timeFactor) * 1e6
    }
}
