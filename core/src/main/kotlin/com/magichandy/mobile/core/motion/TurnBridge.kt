// Ported from internal/motion/{flow_transition,flow_shape_transition}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** One monotone join that carries the incoming and destination velocity and acceleration. */
internal class TurnBridge
private constructor(private val curve: QuinticSegment, override val maximumVelocity: Double) :
    ContinuousPath {
    val duration = curve.duration
    override val accelerationBound = maxOf(7500.0, curve.peaks.acceleration * 1e6)

    override fun legPeakSpeed(at: Long): Double = curve.peaks.velocity * 1000

    override fun stateAt(at: Long): CurveState {
        val u = (at.toDouble() / duration).coerceIn(0.0, 1.0)
        return CurveState(curve.position(u), curve.velocity(u), curve.acceleration(u))
    }

    override fun sampleAt(timeMillis: Long): CurvePoint =
        CurvePoint(
            timeMillis,
            curve
                .position((timeMillis.toDouble() / duration).coerceIn(0.0, 1.0))
                .coerceIn(0.0, 100.0),
        )

    override fun knotTimes(start: Long, end: Long): Set<Long> =
        listOf(0L, duration).filterTo(mutableSetOf()) { it in start until end }

    companion object {
        fun fit(from: CurveState, to: CurveState, preferred: Long, velocity: Double): TurnBridge? {
            if (abs(to.position - from.position) < 1) return null
            // With inherited momentum a longer join can also overshoot. Search both sides of the
            // requested time.
            for (step in 0..96) {
                for (sign in listOf(1, -1)) {
                    if (step == 0 && sign < 0) continue
                    val millis =
                        (preferred * 1.02.pow(step * sign)).roundToLong().coerceAtLeast(100)
                    val candidate = QuinticSegment(millis, from, to)
                    val peaks = candidate.peaks
                    if (
                        peaks.velocity * 1000 <= velocity &&
                            peaks.acceleration * 1e6 <= 7500 &&
                            peaks.jerk * 1e9 <= 150000 &&
                            candidate.monotone() &&
                            candidate.hasSingleSpeedCrest()
                    )
                        return TurnBridge(candidate, velocity)
                }
            }
            return null
        }
    }
}
