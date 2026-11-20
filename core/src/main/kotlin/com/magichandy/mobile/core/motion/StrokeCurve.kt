// Ported from internal/motion/{gesture_curve,gesture_cruise}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.motion.StrokeOscillator.cos
import com.magichandy.mobile.core.motion.StrokeOscillator.sin
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong

internal class StrokeCurve(val times: LongArray, val states: Array<CurveState>) {
    val segments =
        Array(times.size - 1) { i ->
            QuinticSegment(times[i + 1] - times[i], states[i], states[i + 1])
        }
    val peaks = segments.fold(CurvePeaks(0.0, 0.0, 0.0)) { sum, s -> sum.include(s.peaks) }
    val duration
        get() = times.last()

    fun sample(at: Double): Double {
        val exact = times.binarySearch(at.toLong())
        if (exact >= 0 && at == times[exact].toDouble()) return states[exact].position
        val i = (if (exact >= 0) exact else -exact - 2).coerceIn(0, segments.lastIndex)
        return segments[i]
            .position(((at - times[i]) / segments[i].duration).coerceIn(0.0, 1.0))
            .coerceIn(0.0, 100.0)
    }
}

private fun StrokeLeg.progress(u: Double): CurveState {
    val k = 0.65 * inertia
    val angle = PI * u
    val x = u - k * sin(angle) / PI
    val dx = 1 - k * cos(angle)
    val ddx = k * PI * sin(angle)
    var f = (1 - cos(PI * x)) / 2
    var df = PI * sin(PI * x) / 2
    var ddf = PI * PI * cos(PI * x) / 2
    if (softness > 0) {
        val rest = 1 - x
        val septic = x * x * x * x * (35 + x * (-84 + x * (70 - 20 * x)))
        f += softness * (septic - f)
        df += softness * (140 * x * x * x * rest * rest * rest - df)
        ddf += softness * (420 * x * x * rest * rest * (1 - 2 * x) - ddf)
    }
    return CurveState(f, df * dx, ddf * dx * dx + df * ddx)
}

private fun StrokeLeg.ramps(): Pair<Double, Double> {
    val base = (0.38 - 0.001 * abs(to - from)) * ramp
    return base * (1 + 0.65 * inertia) to base * (1 - 0.65 * inertia)
}

private fun ramp(at: Double, h: Double, crest: Double, acceleration: Double): CurveState {
    val u = at / h
    val u2 = u * u
    val u3 = u2 * u
    val u4 = u3 * u
    val a = acceleration * h
    return CurveState(
        h * (crest * (u3 - u4 / 2) + a * (u2 / 2 - 2 * u3 / 3 + u4 / 4)),
        crest * (3 * u2 - 2 * u3) + a * (u - 2 * u2 + u3),
        (crest * (6 * u - 6 * u2) + a * (1 - 4 * u + 3 * u2)) / h,
    )
}

private fun cruise(u: Double, rise: Double, fall: Double, start: Double, end: Double): CurveState {
    val crest = (1 - start * rise * rise / 12 + end * fall * fall / 12) / (1 - (rise + fall) / 2)
    if (u < rise) return ramp(u, rise, crest, start)
    if (u > 1 - fall) {
        val r = ramp(1 - u, fall, crest, -end)
        return CurveState(1 - r.position, r.velocity, -r.acceleration)
    }
    return CurveState(crest * (u - rise / 2) + start * rise * rise / 12, crest, 0.0)
}

internal fun StrokeLeg.endpointAcceleration(u: Double): Double {
    val a = progress(u).acceleration
    if (cruise == 0.0) return a
    val (rise, fall) = ramps()
    return a + cruise * (cruise(u, rise, fall, 8.0, -8.0).acceleration - a)
}

private fun blend(u: Double, start: Double, end: Double): CurveState {
    val u2 = u * u
    val u3 = u2 * u
    val u4 = u3 * u
    val u5 = u4 * u
    return CurveState(
        start * 0.5 * (u2 - 3 * u3 + 3 * u4 - u5) + end * 0.5 * (u3 - 2 * u4 + u5),
        start * (u - 4.5 * u2 + 6 * u3 - 2.5 * u4) + end * (1.5 * u2 - 4 * u3 + 2.5 * u4),
        start * (1 - 9 * u + 18 * u2 - 10 * u3) + end * (3 * u - 12 * u2 + 10 * u3),
    )
}

internal fun StrokeLeg.curve(duration: Long, startA: Double, endA: Double): StrokeCurve {
    val baseTimes = LongArray(9) { (it.toDouble() * duration / 8).roundToLong() }
    val (rise, fall) = ramps()
    val riseAt = (rise * duration).roundToLong()
    val fallAt = ((1 - fall) * duration).roundToLong()
    val times =
        if (cruise > 0) (baseTimes + riseAt + fallAt).distinct().sorted().toLongArray()
        else baseTimes
    val square = (duration * duration).toDouble()
    val distance = to - from
    val ds = startA * square - distance * progress(0.0).acceleration
    val de = endA * square - distance * progress(1.0).acceleration
    val states =
        Array(times.size) { i ->
            val u = times[i].toDouble() / duration
            val native = progress(u)
            val correction = blend(u, ds, de)
            var x = distance * native.position + correction.position
            var v = distance * native.velocity + correction.velocity
            var a = distance * native.acceleration + correction.acceleration
            if (cruise > 0 && distance != 0.0) {
                val gain = square / distance
                val q =
                    cruise(
                        u,
                        riseAt.toDouble() / duration,
                        1 - fallAt.toDouble() / duration,
                        startA * gain,
                        endA * gain,
                    )
                x += cruise * (distance * q.position - x)
                v += cruise * (distance * q.velocity - v)
                a += cruise * (distance * q.acceleration - a)
            }
            CurveState(from + x, v / duration, a / square)
        }
    states[0] = CurveState(from, 0.0, startA)
    states[states.lastIndex] = CurveState(to, 0.0, endA)
    return StrokeCurve(times, states)
}

internal fun StrokeLeg.referenceCurve(): StrokeCurve {
    val gain = (to - from) / 1e6
    return curve(1000, endpointAcceleration(0.0) * gain, endpointAcceleration(1.0) * gain)
}
