// Ported from internal/transport/{cloud_client,handy_bluetooth_transport}.go at 1b80d72e.
package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.ports.BufferedPlaybackTiming
import com.magichandy.mobile.core.ports.Clock
import com.magichandy.mobile.core.ports.WriteResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Measures only the actual HSP command. Geometry reads, initial filling and clock sync are
 * separate.
 */
internal abstract class TimedHandyWire(protected val clock: Clock?) : HandyWire {
    private val trips = ArrayDeque<Duration>()
    @Volatile
    final override var bufferedTiming: BufferedPlaybackTiming? = clock?.let {
        BufferedPlaybackTiming()
    }
        private set

    protected abstract suspend fun dispatch(
        command: HandyCommand,
        allowed: () -> Boolean,
    ): WriteResult

    protected abstract suspend fun playTimestamp(allowed: () -> Boolean): Long?

    final override suspend fun command(command: HandyCommand, allowed: () -> Boolean): WriteResult =
        if (command is HandyCommand.Play) play({ command.start }, command.serverTimeMillis, allowed)
        else if (command is HandyCommand.Setup || command is HandyCommand.Stroke) {
            if (command is HandyCommand.Setup)
                bufferedTiming = bufferedTiming?.copy(startedAt = null)
            measure { dispatch(command, allowed) }
        } else dispatch(command, allowed)

    final override suspend fun playAligned(
        offsetMillis: () -> Int?,
        allowed: () -> Boolean,
    ): WriteResult = play(offsetMillis, null, allowed)

    final override suspend fun measureAppend(send: suspend () -> WriteResult): WriteResult =
        measure(send = send)

    private suspend fun play(
        start: () -> Int?,
        timestamp: Long?,
        allowed: () -> Boolean,
    ): WriteResult {
        if (!allowed()) return WriteResult.Stale
        val serverTime = if (clock == null) timestamp else playTimestamp(allowed)
        if (!allowed()) return WriteResult.Stale
        val offset = start() ?: return WriteResult.Uncertain
        return measure(offset) { dispatch(HandyCommand.Play(offset, serverTime), allowed) }
    }

    private suspend fun measure(offset: Int? = null, send: suspend () -> WriteResult): WriteResult {
        val timer = clock ?: return send()
        val sent = timer.elapsed()
        val result = send()
        if (result == WriteResult.Accepted) {
            val trip = (timer.elapsed() - sent).coerceAtLeast(Duration.ZERO)
            // Motion writes are serialized by the Handy owner; read-only checks and Stop bypass
            // this history.
            if (trips.size == 8) trips.removeFirst()
            trips.addLast(trip)
            val origin =
                if (offset != null) sent + trip / 2 - offset.milliseconds
                else bufferedTiming?.startedAt
            bufferedTiming = BufferedPlaybackTiming(origin, trips.max())
        }
        return result
    }
}
