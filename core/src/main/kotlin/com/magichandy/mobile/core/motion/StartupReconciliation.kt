// Ported from internal/motion/{startup,startup_reset}.go at 1b80d72e.
package com.magichandy.mobile.core.motion

import com.magichandy.mobile.core.api.Limits
import com.magichandy.mobile.core.api.Percent
import com.magichandy.mobile.core.ports.MotionBatch
import com.magichandy.mobile.core.ports.OutputFence
import com.magichandy.mobile.core.ports.SliderReading
import com.magichandy.mobile.core.ports.StartupPosition
import com.magichandy.mobile.core.ports.StopEvidence
import com.magichandy.mobile.core.ports.WriteResult
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private data class Arrival(
    val absolute: Double,
    val tolerance: Double,
    val low: Double,
    val high: Double,
) {
    fun reached(s: SliderReading): Boolean =
        s.valid() &&
            s.speedAbsolute <= 5 &&
            abs(s.positionAbsolute - absolute) <= tolerance &&
            s.positionAbsolute in low - tolerance..high + tolerance
}

private fun SliderReading.valid() =
    listOf(
            positionAbsolute,
            speedAbsolute,
            minimumPercent,
            maximumPercent,
            minimumAbsolute,
            maximumAbsolute,
        )
        .all(Double::isFinite) &&
        speedAbsolute >= 0 &&
        minimumPercent >= 0 &&
        maximumPercent <= 100 &&
        maximumPercent > minimumPercent &&
        maximumAbsolute > minimumAbsolute

private data class LeadIn(val batch: MotionBatch, val millis: Long, val arrival: Arrival)

private fun leadIn(s: SliderReading, main: MotionBatch, reversed: Boolean): LeadIn? {
    require(s.valid())
    val travel =
        (s.maximumAbsolute - s.minimumAbsolute) * 100 / (s.maximumPercent - s.minimumPercent)
    val origin = s.minimumAbsolute - s.minimumPercent / 100 * travel
    val observed = (s.positionAbsolute - origin) / travel * 100
    require(travel.isFinite() && travel > 0 && observed in -10.0..110.0)
    val low = main.limits.strokeLow.value
    val high = main.limits.strokeHigh.value
    val mark = main.frames.first().position.value
    val target = low + (if (reversed) 100 - mark else mark) / 100.0 * (high - low)
    if (observed in low.toDouble()..high.toDouble() && abs(target - observed) <= 1) return null
    val coverLow =
        floor(minOf(observed.coerceIn(0.0, 100.0), s.minimumPercent, low.toDouble())).toInt()
    val coverHigh =
        ceil(maxOf(observed.coerceIn(0.0, 100.0), s.maximumPercent, high.toDouble())).toInt()
    fun encoded(x: Double): Percent {
        val coordinate = ((x - coverLow) / (coverHigh - coverLow) * 100).coerceIn(0.0, 100.0)
        return Percent((if (reversed) 100 - coordinate else coordinate).roundToInt())
    }
    val first = encoded(observed)
    val end = encoded(target)
    val encodedTarget =
        coverLow + (if (reversed) 100 - end.value else end.value) / 100.0 * (coverHigh - coverLow)
    val millis =
        maxOf(500L, ceil(abs(target - observed) * 1000 / maxOf(1, main.pace.value)).toLong())
    val frames =
        listOf(MotionFrame(0, first), MotionFrame(millis, end), MotionFrame(millis + 10000, end))
    return LeadIn(
        MotionBatch(
            frames,
            main.pace,
            Limits(main.limits.topSpeed, Percent(coverLow), Percent(coverHigh)),
            true,
        ),
        millis,
        Arrival(
            origin + encodedTarget / 100 * travel,
            travel / 100,
            origin + low / 100.0 * travel,
            origin + high / 100.0 * travel,
        ),
    )
}

/** Only Ready admits main motion. StoppedAway is positive halt evidence for a failed resume. */
internal enum class StartupResult(val evidence: WriteResult) {
    Ready(WriteResult.Accepted),
    StoppedAway(WriteResult.Accepted),
    Stale(WriteResult.Stale),
    Uncertain(WriteResult.Uncertain),
}

internal fun WriteResult.asStartupResult(): StartupResult =
    when (this) {
        WriteResult.Accepted -> StartupResult.Ready
        WriteResult.Stale -> StartupResult.Stale
        WriteResult.Uncertain -> StartupResult.Uncertain
    }

private fun SliderReading.stationaryWithinTravel(): Boolean {
    if (!valid() || speedAbsolute != 0.0) return false
    val travel = (maximumAbsolute - minimumAbsolute) * 100 / (maximumPercent - minimumPercent)
    val origin = minimumAbsolute - minimumPercent / 100 * travel
    return travel.isFinite() && travel > 0 && (positionAbsolute - origin) / travel in -0.01..1.01
}

internal suspend fun reconcileStartup(
    link: StartupPosition,
    batch: MotionBatch,
    fence: OutputFence,
    resuming: Boolean = false,
): StartupResult {
    if (!fence.isCurrent()) return StartupResult.Stale
    if (link.halt(fence) != StopEvidence.Acknowledged) return StartupResult.Uncertain
    if (!fence.isCurrent()) return StartupResult.Stale
    val firstRead = link.read(fence)
    if (!fence.isCurrent()) return StartupResult.Stale
    var observed = firstRead ?: return StartupResult.Ready
    repeat(2) {
        if (observed.valid() && observed.speedAbsolute > 5) {
            delay(150)
            if (!fence.isCurrent()) return StartupResult.Stale
            val next = link.read(fence)
            if (!fence.isCurrent()) return StartupResult.Stale
            observed = next ?: return StartupResult.Uncertain
        }
    }
    if (!observed.valid() || observed.speedAbsolute > 5) return StartupResult.Uncertain
    val lead = leadIn(observed, batch, link.reversed)
    if (!fence.isCurrent()) return StartupResult.Stale
    if (lead == null) return StartupResult.Ready
    val sent = link.position(lead.batch, fence)
    if (sent != WriteResult.Accepted) return sent.asStartupResult()
    delay(lead.millis + 150)
    repeat(6) { attempt ->
        if (!fence.isCurrent()) return StartupResult.Uncertain
        val state = link.read(fence) ?: return StartupResult.Uncertain
        if (!state.valid()) return StartupResult.Uncertain
        if (lead.arrival.reached(state)) return verifyHalt(link, fence, lead.arrival)
        if (attempt < 5) delay(150)
        else if (resuming && state.stationaryWithinTravel())
            return verifyHalt(link, fence, lead.arrival, missedAt = state)
    }
    return StartupResult.Uncertain
}

/** A stopped, stable miss is retryable only on the same physical lease that acknowledged halt. */
private suspend fun verifyHalt(
    link: StartupPosition,
    fence: OutputFence,
    target: Arrival,
    missedAt: SliderReading? = null,
): StartupResult {
    if (!fence.isCurrent() || link.halt(fence) != StopEvidence.Acknowledged || !fence.isCurrent())
        return StartupResult.Uncertain
    val stopped = link.read(fence) ?: return StartupResult.Uncertain
    if (!fence.isCurrent()) return StartupResult.Uncertain
    return when {
        target.reached(stopped) -> StartupResult.Ready
        missedAt != null &&
            stopped.stationaryWithinTravel() &&
            abs(stopped.positionAbsolute - missedAt.positionAbsolute) <= target.tolerance ->
            StartupResult.StoppedAway
        else -> StartupResult.Uncertain
    }
}
