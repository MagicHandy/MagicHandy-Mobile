// Ported from internal/motion/{dynamic_timing,quintic}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Fit each interval independently, then distribute remaining time without shortening physical
 * floors.
 */
internal fun compileDynamic(
    points: List<CurvePoint>,
    speed: Int,
    model: HandyModel,
): ContinuousPlan? {
    require(
        points.size in 3..4096 &&
            points.first().position == points.last().position &&
            speed in 1..100
    )
    val positions = points.map { it.position }
    val authored = LongArray(points.lastIndex) { points[it + 1].timeMillis - points[it].timeMillis }
    require(authored.all { it > 0 })
    val travel = authored.indices.sumOf { abs(positions[it + 1] - positions[it]) }
    val target = ceil(travel * 1000 / model.travelRate(speed)).toLong().coerceAtLeast(1)
    val peak = model.travelRate(100)
    val floors = fitDynamic(positions, LongArray(authored.size) { 1 }, peak) ?: return null
    var durations = floors
    for (pass in 0 until 32) {
        val proposal = if (floors.sum() >= target) floors else distribute(floors, authored, target)
        durations = fitDynamic(positions, proposal, peak) ?: return null
        if (proposal === floors || durations.sum() <= target) break
        var changed = false
        for (i in floors.indices) if (durations[i] > proposal[i] && durations[i] > floors[i]) {
            floors[i] = durations[i]
            changed = true
        }
        if (!changed) break
    }
    return ContinuousPlan(dynamicCurves(positions, durations), durations.sum(), peak, 7500.0)
}

private fun distribute(floors: LongArray, authored: LongArray, target: Long): LongArray {
    fun value(i: Int, scale: Double) = maxOf(floors[i], floor(authored[i] * scale).toLong())
    fun total(scale: Double) = floors.indices.sumOf { value(it, scale) }
    var low = 0.0
    var high = 1.0
    while (total(high) < target) high *= 2
    repeat(60) {
        val mid = (low + high) / 2
        if (total(mid) <= target) low = mid else high = mid
    }
    val result = LongArray(floors.size) { value(it, low) }
    repeat((target - result.sum()).toInt()) { result[it % result.size]++ }
    return result
}

private fun fitDynamic(positions: List<Double>, seed: LongArray, velocity: Double): LongArray? {
    val durations = seed.copyOf()
    ensureReversalGaps(positions, durations)
    repeat(48) {
        val curves = dynamicCurves(positions, durations)
        var changed = false
        for ((i, c) in curves.withIndex()) {
            val p = c.peaks
            val factor =
                maxOf(
                    p.velocity * 1000 / velocity,
                    sqrt(p.acceleration * 1e6 / 7500),
                    Math.cbrt(p.jerk * 1e9 / 150000),
                )
            if (factor > 1.001) {
                durations[i] = ceil(durations[i] * factor * 1.002).toLong()
                changed = true
            }
        }
        if (!ensureReversalGaps(positions, durations) && !changed) return durations
    }
    return null
}

private fun ensureReversalGaps(p: List<Double>, d: LongArray): Boolean {
    val turns =
        d.indices.filter { i -> (p[i] - p[(i + d.size - 1) % d.size]) * (p[i + 1] - p[i]) < 0 }
    if (turns.size < 2) return false
    var changed = false
    for (i in turns.indices) {
        val start = turns[i]
        val end = if (i == turns.lastIndex) turns.first() + d.size else turns[i + 1]
        val gap = (start until end).sumOf { d[it % d.size] }
        if (gap >= 100) continue
        val factor = 100.0 / gap
        for (index in start until end) {
            val k = index % d.size
            d[k] = maxOf(d[k] + 1, ceil(d[k] * factor).toLong())
        }
        changed = true
    }
    return changed
}

private fun dynamicCurves(p: List<Double>, d: LongArray): List<StrokeCurve> {
    val states =
        Array(d.size) { i ->
            val prev = (i + d.size - 1) % d.size
            val before = p[i] - p[prev]
            val after = p[i + 1] - p[i]
            val h0 = d[prev].toDouble()
            val h1 = d[i].toDouble()
            if (before * after <= 0) {
                val a =
                    if (before * after < 0)
                        Math.copySign(
                            minOf(turnAcceleration(before, h0), turnAcceleration(after, h1)),
                            after,
                        )
                    else 0.0
                CurveState(p[i], 0.0, a)
            } else {
                val w1 = 2 * h1 + h0
                val w2 = h1 + 2 * h0
                val slope = (w1 + w2) / (w1 / (before / h0) + w2 / (after / h1))
                CurveState(
                    p[i],
                    Math.copySign(
                        minOf(abs(slope), 2 * minOf(abs(before) / h0, abs(after) / h1)),
                        after,
                    ),
                    0.0,
                )
            }
        }
    return d.indices.map { i ->
        StrokeCurve(longArrayOf(0, d[i]), arrayOf(states[i], states[(i + 1) % d.size]))
    }
}

private fun turnAcceleration(delta: Double, duration: Double): Double {
    val f = ((abs(delta) - 18) / 57).coerceIn(0.0, 1.0)
    val cosine = PI * PI / 2
    // Go rounds the constant (8 - pi²/2) once. Subtracting an already rounded Double can
    // change which side of a symmetric stroke wins the wire fitter's maximum-error tie.
    val cruiseGain = 3.06519779945532056
    return (cosine + cruiseGain * (f * f * (3 - 2 * f))) * abs(delta) / (duration * duration)
}
