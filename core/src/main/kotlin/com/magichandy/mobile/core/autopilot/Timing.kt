// Ported from internal/{config/autopilot,modes/autopilot_scheduler}.go at 1b80d72e (ADR 0072).
package com.magichandy.mobile.core.autopilot

import com.magichandy.mobile.core.api.ChangeFrequency
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class Timing(private val random: Random = Random.Default) {
    private val windows = listOf(90..240, 60..150, 45..120, 20..60, 14..45, 10..35, 8..24, 8..16)

    fun floor(level: ChangeFrequency) = maxOf(8, windows[level.level - 1].first / 2)

    fun motion(level: ChangeFrequency, next: String, seconds: Int?): Duration {
        if (seconds != null)
            return (seconds * (1 + 0.12 * (2 * random.nextDouble() - 1)))
                .roundToInt()
                .coerceIn(floor(level), 300)
                .seconds
        return sample(windows[level.level - 1], next)
    }

    fun speech(next: String) = sample(35..120, next)

    private fun sample(range: IntRange, category: String): Duration {
        val (low, peak, high) =
            when (category) {
                "soon" -> Triple(0.0, 0.12, 0.4)
                "later" -> Triple(0.6, 0.88, 1.0)
                else -> Triple(0.25, 0.5, 0.75)
            }
        val u = random.nextDouble()
        val f =
            if (u < (peak - low) / (high - low)) low + sqrt(u * (high - low) * (peak - low))
            else high - sqrt((1 - u) * (high - low) * (high - peak))
        return (range.first + f * (range.last - range.first)).roundToInt().seconds
    }
}
