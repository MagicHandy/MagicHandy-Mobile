// Ported from internal/handyble/session_rpc.go and internal/transport/cloud_client.go at 1b80d72e.
package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.ports.Clock
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Once synchronized, only elapsed time can advance the timestamp sent to the device. */
internal data class HandyClockReading(val timeMillis: Long, val measured: Duration) {
    fun at(now: Duration): Long? {
        val elapsed = (now - measured).inWholeMilliseconds
        return if (elapsed >= 0 && timeMillis in 0..(Long.MAX_VALUE - elapsed)) timeMillis + elapsed
        else null
    }
}

private data class BluetoothClockSample(
    val roundTrip: Duration,
    val offsetMillis: Long,
    val reading: HandyClockReading,
)

internal suspend fun synchronizeBluetoothClock(
    clock: Clock,
    read: suspend () -> Long?,
    set: suspend (HandyCommand.ClockSet) -> Boolean,
): HandyClockReading? {
    var best: BluetoothClockSample? = null
    repeat(3) {
        val start = clock.elapsed()
        val wall = clock.now().toEpochMilliseconds()
        val device = read()
        val end = clock.elapsed()
        val wallEnd = clock.now().toEpochMilliseconds()
        val trip = end - start
        val millis = trip.inWholeMilliseconds
        if (
            device == null ||
                millis !in 0..Int.MAX_VALUE.toLong() ||
                abs((wallEnd - wall) - millis) >= 100
        )
            return@repeat
        if (best?.let { trip >= it.roundTrip } == true) return@repeat
        best =
            BluetoothClockSample(
                trip,
                wall + (wallEnd - wall) / 2 - device,
                HandyClockReading(wallEnd, end),
            )
    }
    val sample = best ?: return null
    val command =
        HandyCommand.ClockSet(sample.offsetMillis, sample.roundTrip.inWholeMilliseconds.toInt())
    return sample.reading.takeIf { set(command) }
}

/** The Cloud server timestamp includes half the measured round trip for delivery of Play. */
internal class CloudClock(private val clock: Clock) {
    private var reading: HandyClockReading? = null

    suspend fun deliveryTime(read: suspend () -> Long?): Long? {
        val start = clock.elapsed()
        val cached = reading
        if (cached != null && start - cached.measured in Duration.ZERO..5.minutes)
            return cached.at(start)
        reading = null
        val server = read() ?: return null
        val end = clock.elapsed()
        val roundTrip = (end - start).inWholeMilliseconds
        if (roundTrip < 0 || server !in 1..(Long.MAX_VALUE - roundTrip)) return null
        return HandyClockReading(server + roundTrip, end).also { reading = it }.timeMillis
    }
}

internal fun parseServerTimeMillis(body: String): Long? {
    val element =
        try {
            Json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return null
        }
    return timestamp(element)
}

private fun timestamp(element: JsonElement): Long? {
    if (element is JsonPrimitive) {
        val text = element.content.trim()
        text.toLongOrNull()?.let {
            return it.takeIf { n -> n > 0 }
        }
        val millis = text.toDoubleOrNull() ?: return null
        return if (millis.isFinite() && millis > 0 && millis < Long.MAX_VALUE.toDouble())
            Math.round(millis)
        else null
    }
    if (element !is JsonObject) return null
    for (key in
        listOf(
            "server_time",
            "serverTime",
            "server_time_ms",
            "serverTimeMs",
            "time",
            "now",
        )) (element[key] as? JsonPrimitive)
        ?.let { timestamp(it) }
        ?.let {
            return it
        }
    for (key in listOf("result", "data", "state")) (element[key] as? JsonObject)
        ?.let { timestamp(it) }
        ?.let {
            return it
        }
    return null
}
