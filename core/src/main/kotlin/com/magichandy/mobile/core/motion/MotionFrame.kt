// Ported from internal/motion/{plan,sampling}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.Percent

/** A commanded position on a run-relative stream clock. Never represents physical telemetry. */
public data class MotionFrame(public val timeMillis: Long, public val position: Percent) {
    init {
        require(timeMillis >= 0)
    }
}

internal data class CurvePoint(val timeMillis: Long, val position: Double) {
    init {
        require(timeMillis >= 0 && position in 0.0..100.0)
    }
}

/** True when each frame advances the stream clock. Works with any list in a single forward pass. */
public fun List<MotionFrame>.hasIncreasingTimes(): Boolean {
    var previous = -1L
    for (frame in this) {
        if (frame.timeMillis <= previous) return false
        previous = frame.timeMillis
    }
    return true
}
