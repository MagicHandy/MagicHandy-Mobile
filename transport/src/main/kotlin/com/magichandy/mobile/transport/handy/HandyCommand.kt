// Ported from internal/handyble/codec.go and internal/transport/cloud.go at 1b80d72e.
package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.motion.MotionFrame
import com.magichandy.mobile.core.motion.hasIncreasingTimes

/** Reported by firmware. Setup and initial Add legitimately report idle before the first Play. */
internal enum class HandyPlayback {
    Uninitialized,
    Playing,
    Stopped,
    Paused,
    Starving;

    fun rejects(command: HandyCommand): Boolean =
        when (command) {
            is HandyCommand.Add -> command.refilling && this != Playing
            is HandyCommand.Play -> this == Paused || this == Starving
            else -> false
        }
}

/** Only the finite, starvation-pausing firmware commands used by the engine. */
internal sealed class HandyCommand(val method: Int) {
    data object StopCurrentMode : HandyCommand(715)

    data object HspStop : HandyCommand(864)

    data object State : HandyCommand(867)

    data object ClockGet : HandyCommand(712)

    data class ClockSet(val offset: Long, val roundTripMillis: Int) : HandyCommand(709)

    data class Stroke(val low: Double, val high: Double) : HandyCommand(841) {
        init {
            require(low >= 0 && low < high && high <= 100)
        }
    }

    data class Setup(val stream: Long) : HandyCommand(860) {
        init {
            require(stream in 1..0xffff_ffffL)
        }
    }

    data class Add(
        val frames: List<MotionFrame>,
        val tail: Long,
        val flush: Boolean,
        val refilling: Boolean = false,
    ) : HandyCommand(861) {
        init {
            require(frames.isNotEmpty() && tail in (frames.size - 1).toLong()..0xffff_ffffL)
            require(frames.last().timeMillis <= 0xffff_ffffL && frames.hasIncreasingTimes())
        }
    }

    data class Play(val start: Int, val serverTimeMillis: Long? = null) : HandyCommand(863)
}
