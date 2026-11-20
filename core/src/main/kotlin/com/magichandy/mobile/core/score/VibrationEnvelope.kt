// Ported from internal/motion/vibration.go at 1b80d72e.
package com.magichandy.mobile.core.score

import kotlin.math.PI
import kotlin.math.cos

/** Modes authored by the Advanced contract. Percent values are fractions of the user's ceiling. */
public enum class VibrationMode {
    Follow,
    Off,
    Steady,
    Wave,
    Pulse,
}

public data class VibrationEnvelope(
    public val mode: VibrationMode,
    public val low: Int,
    public val high: Int,
    public val periodSeconds: Double,
) {
    init {
        require(low in 0..high && high <= 100 && periodSeconds.isFinite())
        when (mode) {
            VibrationMode.Off -> require(low == 0 && high == 0 && periodSeconds == 0.0)
            VibrationMode.Follow -> require(periodSeconds == 0.0)
            VibrationMode.Steady -> require(low == high && periodSeconds == 0.0)
            VibrationMode.Wave,
            VibrationMode.Pulse -> require(periodSeconds in 0.5..30.0)
        }
    }

    /** Sample the requested drive without raising the saved hardware ceiling. */
    public fun level(elapsedMillis: Long, follow: Double): Double {
        val fraction =
            when (mode) {
                VibrationMode.Off -> return 0.0
                VibrationMode.Steady -> return high / 100.0
                VibrationMode.Follow ->
                    if (high == 0) return follow.coerceIn(0.0, 1.0) else follow.coerceIn(0.0, 1.0)
                VibrationMode.Wave,
                VibrationMode.Pulse -> {
                    val wave =
                        (1 -
                            cos(
                                2 * PI * (elapsedMillis.coerceAtLeast(0) % (periodSeconds * 1000)) /
                                    (periodSeconds * 1000)
                            )) / 2
                    if (mode == VibrationMode.Wave) wave
                    else {
                        val x = ((wave - 0.25) * 2).coerceIn(0.0, 1.0)
                        x * x * (3 - 2 * x)
                    }
                }
            }
        return (low + (high - low) * fraction) / 100.0
    }
}
