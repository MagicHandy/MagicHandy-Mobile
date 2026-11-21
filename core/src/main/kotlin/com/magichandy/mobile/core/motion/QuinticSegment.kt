// Ported from internal/motion/quintic.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import kotlin.math.abs
import kotlin.math.sqrt

/** Position and its first two derivatives with respect to the surrounding time coordinate. */
internal data class CurveState(val position: Double, val velocity: Double, val acceleration: Double)

internal data class CurvePeaks(val velocity: Double, val acceleration: Double, val jerk: Double) {
    fun include(other: CurvePeaks) =
        CurvePeaks(
            maxOf(velocity, other.velocity),
            maxOf(acceleration, other.acceleration),
            maxOf(jerk, other.jerk),
        )
}

/** Quintic Hermite interpolation, with derivatives in percent per millisecond and its powers. */
internal class QuinticSegment(val duration: Long, left: CurveState, right: CurveState) {
    private val h = duration.toDouble()
    private val displacement = right.position - left.position
    private val x = left.position
    private val v = left.velocity * h
    private val endV = right.velocity * h
    private val startA = left.acceleration * (h * h)
    private val endA = right.acceleration * (h * h)
    private val a = startA / 2
    private val b = 10 * displacement - 6 * v - 4 * endV - 1.5 * startA + 0.5 * endA
    private val c = -15 * displacement + 8 * v + 7 * endV + 1.5 * startA - endA
    private val d = 6 * displacement - 3 * v - 3 * endV - 0.5 * startA + 0.5 * endA

    fun position(u: Double) = x + u * (v + u * (a + u * (b + u * (c + u * d))))

    fun velocity(u: Double) = (v + u * (2 * a + u * (3 * b + u * (4 * c + u * 5 * d)))) / h

    fun acceleration(u: Double) = (2 * a + u * (6 * b + u * (12 * c + u * 20 * d))) / (h * h)

    private fun jerk(u: Double) = (6 * b + u * (24 * c + u * 60 * d)) / (h * h * h)

    private var minimumV = minOf(velocity(0.0), velocity(1.0))
    private var maximumV = maxOf(velocity(0.0), velocity(1.0))
    val peaks: CurvePeaks

    init {
        cubicRoots(20 * d, 12 * c, 6 * b, 2 * a) { u ->
            val value = velocity(u)
            minimumV = minOf(minimumV, value)
            maximumV = maxOf(maximumV, value)
        }
        var peakA = maxOf(abs(acceleration(0.0)), abs(acceleration(1.0)))
        quadraticRoots(60 * d, 24 * c, 6 * b) { u -> peakA = maxOf(peakA, abs(acceleration(u))) }
        var peakJ = maxOf(abs(jerk(0.0)), abs(jerk(1.0)))
        if (abs(d) > 1e-12) {
            val vertex = -c / (5 * d)
            if (vertex > 0 && vertex < 1) peakJ = maxOf(peakJ, abs(jerk(vertex)))
        }
        peaks = CurvePeaks(maxOf(abs(minimumV), abs(maximumV)), peakA, peakJ)
    }

    fun monotone(): Boolean =
        if (position(1.0) - position(0.0) < 0) maximumV <= 1e-9 else minimumV >= -1e-9

    /** A connecting stroke may crest once. An interior speed minimum feels like a second start. */
    fun hasSingleSpeedCrest(): Boolean {
        var single = true
        val direction = kotlin.math.sign(displacement)
        cubicRoots(20 * d, 12 * c, 6 * b, 2 * a) { u ->
            if (
                u > 1e-6 && u < 1 - 1e-6 && direction * (6 * b + 24 * c * u + 60 * d * u * u) > 1e-8
            )
                single = false
        }
        return single
    }
}

private inline fun quadraticRoots(a: Double, b: Double, c: Double, accept: (Double) -> Unit) {
    if (abs(a) <= 1e-12) {
        if (abs(b) > 1e-12) {
            val x = -c / b
            if (x > 0 && x < 1) accept(x)
        }
        return
    }
    val discriminant = b * b - 4 * a * c
    if (discriminant < 0) return
    val root = sqrt(discriminant)
    val x = (-b - root) / (2 * a)
    val y = (-b + root) / (2 * a)
    if (x > 0 && x < 1) accept(x)
    if (y > 0 && y < 1) accept(y)
}

private fun cubicRoots(a: Double, b: Double, c: Double, d: Double, accept: (Double) -> Unit) {
    if (abs(a) <= 1e-12) return quadraticRoots(b, c, d, accept)
    val bounds = doubleArrayOf(0.0, 1.0, 1.0, 1.0)
    var count = 2
    quadraticRoots(3 * a, 2 * b, c) { bounds[count++] = it }
    bounds.sort(0, count)
    fun value(u: Double) = ((a * u + b) * u + c) * u + d
    for (i in 1 until count) {
        var left = bounds[i - 1]
        var right = bounds[i]
        var lo = value(left)
        val hi = value(right)
        if (abs(lo) <= 1e-12 && left > 0) accept(left)
        if (abs(hi) <= 1e-12 && right < 1) accept(right)
        if (lo * hi >= 0) continue
        repeat(60) {
            val middle = (left + right) / 2
            val mid = value(middle)
            if (lo * mid <= 0) right = middle
            else {
                left = middle
                lo = mid
            }
        }
        accept((left + right) / 2)
    }
}
