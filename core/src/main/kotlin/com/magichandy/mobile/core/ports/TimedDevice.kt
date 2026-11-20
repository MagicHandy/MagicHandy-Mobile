package com.magichandy.mobile.core.ports

import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.motion.MotionFrame
import kotlin.math.abs
import kotlin.math.sign
import kotlin.time.Duration

/** A finite window of already-admitted neutral positions shared by the real-time output owners. */
public class OutputSlice
internal constructor(
    public val atMillis: Long,
    public val frames: List<MotionFrame>,
    public val limits: Limits,
    public val pace: Percent,
    public val vibration: com.magichandy.mobile.core.score.VibrationEnvelope?,
    public val vibrationMillis: Long,
    public val tempo: Percent,
    public val airRevision: Long = 0,
    public val airSteps: Int = 0,
) {
    /** Linear interpolation of the actual quantized wire positions. */
    public fun position(timeMillis: Long): Double {
        val index = frames.binarySearchBy(timeMillis) { it.timeMillis }
        if (index >= 0) return frames[index].position.value.toDouble()
        val right = -index - 1
        if (right == 0) return frames.first().position.value.toDouble()
        if (right == frames.size) return frames.last().position.value.toDouble()
        val first = frames[right - 1]
        val last = frames[right]
        return first.position.value +
            (last.position.value - first.position.value) *
                (timeMillis - first.timeMillis).toDouble() / (last.timeMillis - first.timeMillis)
    }

    /** Mean absolute travel over the speed-follow window, in full spans per second. */
    public fun speed(): Double {
        var previous = position(atMillis - 100)
        var distance = 0.0
        for (i in 1..6) {
            val next = position(atMillis - 100 + i * 100 / 6)
            distance += abs(next - previous)
            previous = next
        }
        return distance / 10
    }

    /** First reversal or the end of the bounded lookahead, for one Ultra go-to. */
    public fun legEnd(maximumMillis: Long): Long {
        val end = minOf(atMillis + maximumMillis, frames.last().timeMillis)
        var prior = position(atMillis)
        var priorTime = atMillis
        var direction = 0
        for (frame in frames) {
            if (frame.timeMillis <= atMillis) continue
            if (frame.timeMillis > end) break
            val step = (frame.position.value - prior).sign.toInt()
            if (step != 0 && direction != 0 && step != direction) return priorTime
            if (step != 0) direction = step
            prior = frame.position.value.toDouble()
            priorTime = frame.timeMillis
        }
        return end
    }
}

/** The engine owns this device's cadence and leases every native operation, including on Stop. */
public interface TimedDevice : DeviceTransport {
    /** Never catch up by bursting commands after a late tick. */
    public val interval: Duration

    /** Mutate only while [fence] is current. An uncertain write retires the owner. */
    public suspend fun output(slice: OutputSlice, fence: OutputFence): WriteResult

    override suspend fun write(batch: MotionBatch, fence: OutputFence): WriteResult =
        WriteResult.Stale
}
