// Ported from internal/motion/holding.go and internal/modes/edge.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import kotlin.math.roundToInt

/** Frozen phase and travel of the currently playing leg, including during a change of band. */
internal data class PlaybackPosition(
    val phase: Double = 0.0,
    val band: IntRange = 0..100,
    val frozen: FrozenScore? = null,
)

/** The full-stroke manual score or its midpoint hold, all relative to the saved stroke window. */
internal data class ManualTarget(
    val pace: Percent,
    val band: IntRange,
    val holding: Boolean,
    val source: com.magichandy.mobile.core.api.MotionSource =
        com.magichandy.mobile.core.api.MotionSource.You,
    val requested: Int = pace.value,
) {
    fun physical(mark: Int, limits: Limits): Percent =
        Percent(
            limits.strokeLow.value +
                ((limits.strokeHigh.value - limits.strokeLow.value) * mark / 100.0).roundToInt()
        )

    companion object {
        fun from(limits: Limits, motorTest: Boolean, edge: Boolean): ManualTarget {
            val pace = minOf(if (motorTest) 10 else 25, limits.topSpeed.value)
            if (pace == 0 || !edge || motorTest) return ManualTarget(Percent(pace), 0..100, false)
            return ManualTarget(Percent((pace * 0.4).roundToInt().coerceIn(1, 20)), 25..75, true)
        }
    }
}
