// Ported from internal/motion/{gesture_curve,gesture_timing,top_speed,prepared}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.score.GestureScore
import com.magichandy.mobile.core.score.SweepDirection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

internal fun HandyModel.travelRate(speed: Int): Double =
    (32 + (maximumMillimetersPerSecond - 32) * ((speed - 1) / 99.0)) / strokeMillimeters * 100

internal class StrokeBudget(val maximumVelocity: Double, speed: Int) {
    private val knee = ((speed - 75) / 25.0).coerceIn(0.0, 1.0)
    private val share = knee * knee * (3 - 2 * knee)
    val acceleration = 7500 * (1 + 2 * share)
    val jerk = 150000 * (1 + 7 * share)
    val rampFloor = 1 - 0.65 * share

    fun scale(curve: StrokeCurve, leg: StrokeLeg? = null): Double {
        val peaks = curve.peaks
        val velocity =
            maximumVelocity * (if (leg != null && leg.cruise > 0 && leg.ramp < 1) 0.985 else 1.0)
        val floor = maxOf(100.0 / curve.duration, peaks.velocity * 1000 / velocity)
        val scale =
            maxOf(
                floor,
                sqrt(peaks.acceleration * 1e6 / acceleration),
                Math.cbrt(peaks.jerk * 1e9 / jerk),
            )
        if (curve.duration * scale >= 120) return scale
        val base =
            maxOf(
                floor,
                sqrt(peaks.acceleration * 1e6 / 7500),
                Math.cbrt(peaks.jerk * 1e9 / 150000),
            )
        return maxOf(scale, minOf(base, 120.0 / curve.duration))
    }
}

internal fun compileGesture(score: GestureScore, model: HandyModel): ContinuousPlan? {
    val legs = StrokeField(score).legs()
    val rate = model.travelRate(score.speed)
    val budget = StrokeBudget(model.travelRate(100), score.speed)
    val reference = legs.map { budget.scale(it.referenceCurve()) }
    fun fit(candidate: List<StrokeLeg>): List<StrokeCurve>? {
        val durations =
            LongArray(candidate.size) { i ->
                val leg = candidate[i]
                var floor =
                    if (leg.cruise == 0.0) reference[i] else budget.scale(leg.referenceCurve())
                if (
                    score.faster != SweepDirection.Even &&
                        (leg.to > leg.from) != (score.faster == SweepDirection.Tip)
                ) {
                    val bias = 0.65 * leg.contrast
                    floor *= (1 + bias) / (1 - bias)
                }
                ceil(maxOf(abs(leg.to - leg.from) / rate * leg.weight, floor) * 1000 * 1.002)
                    .toLong()
            }
        return fitStrokeCurves(candidate, durations, budget)
    }
    var best = fit(legs) ?: return null
    var duration = best.sumOf { it.duration }
    val requested = legs.sumOf { ceil(abs(it.to - it.from) / rate * it.weight * 1000 * 1.002) }
    val threshold = duration * 0.995
    for ((amount, ramp) in cruiseProfiles(budget)) {
        if (duration <= requested * 1.005) break
        val candidate = legs.mapIndexed { i, leg ->
            cruiseCandidate(leg, amount, ramp, reference[i], budget)
        }
        if (candidate.none { it.cruise > 0 }) continue
        val curves = fit(candidate) ?: continue
        val next = curves.sumOf { it.duration }
        if (
            next < duration && next < (if (budget.rampFloor >= 1) duration * 0.995 else threshold)
        ) {
            best = curves
            duration = next
        }
    }
    return ContinuousPlan.prepared(best, rate, budget)
}

internal fun cruiseProfiles(budget: StrokeBudget) = buildList {
    for (amount in listOf(0.25, 0.5, 0.75, 1.0)) add(amount to 1.0)
    for (step in 19 downTo 7) if (step / 20.0 >= budget.rampFloor - 1e-9) add(1.0 to step / 20.0)
}

internal fun cruiseCandidate(
    leg: StrokeLeg,
    amount: Double,
    ramp: Double,
    base: Double,
    budget: StrokeBudget,
): StrokeLeg {
    val span = abs(leg.to - leg.from)
    if (span <= 20 || leg.restSeconds > 0) return leg
    val x = ((span - 20) / 55).coerceIn(0.0, 1.0)
    val cruise = leg.copy(cruise = amount * (x * x * (3 - 2 * x)), ramp = ramp)
    return if (budget.scale(cruise.referenceCurve(), cruise) < base) cruise else leg
}

internal fun fitStrokeCurves(
    legs: List<StrokeLeg>,
    durations: LongArray,
    budget: StrokeBudget,
    commonClock: Boolean = false,
): List<StrokeCurve>? {
    repeat(16) {
        val turns =
            DoubleArray(legs.size) { i ->
                val prev = (i + legs.size - 1) % legs.size
                fun acceleration(index: Int, phase: Double): Double {
                    val s = legs[index]
                    return s.endpointAcceleration(phase) * (s.to - s.from) /
                        (durations[index] * durations[index])
                }
                val a = acceleration(prev, 1.0)
                val b = acceleration(i, 0.0)
                Math.copySign(minOf(abs(a), abs(b)), b)
            }
        var changed = false
        var largestScale = 1.0
        val curves = legs.mapIndexed { i, leg ->
            val curve = leg.curve(durations[i], turns[i], turns[(i + 1) % legs.size])
            val scale = budget.scale(curve, leg)
            if (scale > 1) {
                if (commonClock) largestScale = maxOf(largestScale, scale)
                else durations[i] = ceil(durations[i] * scale * 1.002).toLong()
                changed = true
            }
            curve
        }
        if (commonClock && changed)
            for (i in durations.indices) durations[i] =
                ceil(durations[i] * largestScale * 1.002).toLong()
        if (!changed)
            return curves.takeIf { list ->
                list.all { c ->
                    c.segments.all { it.monotone() } && c.states.all { it.position in 0.0..100.0 }
                }
            }
    }
    return null
}
