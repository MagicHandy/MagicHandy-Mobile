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

internal suspend fun reconcileStartup(
    link: StartupPosition,
    batch: MotionBatch,
    fence: OutputFence,
): WriteResult {
    if (!fence.isCurrent()) return WriteResult.Stale
    if (link.halt(fence) != StopEvidence.Acknowledged) return WriteResult.Uncertain
    if (!fence.isCurrent()) return WriteResult.Stale
    var observed = link.read(fence) ?: return WriteResult.Accepted
    repeat(2) {
        if (observed.valid() && observed.speedAbsolute > 5) {
            delay(150)
            if (!fence.isCurrent()) return WriteResult.Stale
            observed = link.read(fence) ?: return WriteResult.Uncertain
        }
    }
    if (!observed.valid() || observed.speedAbsolute > 5) return WriteResult.Uncertain
    val lead = leadIn(observed, batch, link.reversed) ?: return WriteResult.Accepted
    if (!fence.isCurrent()) return WriteResult.Stale
    val sent = link.position(lead.batch, fence)
    if (sent != WriteResult.Accepted) return sent
    delay(lead.millis + 150)
    repeat(6) { attempt ->
        if (!fence.isCurrent()) return WriteResult.Uncertain
        val state = link.read(fence) ?: return WriteResult.Uncertain
        if (lead.arrival.reached(state)) {
            if (link.halt(fence) != StopEvidence.Acknowledged || !fence.isCurrent())
                return WriteResult.Uncertain
            val stopped = link.read(fence) ?: return WriteResult.Uncertain
            return if (lead.arrival.reached(stopped)) WriteResult.Accepted
            else WriteResult.Uncertain
        }
        if (attempt < 5) delay(150)
    }
    return WriteResult.Uncertain
}
