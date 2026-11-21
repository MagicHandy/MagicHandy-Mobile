package com.magichandy.mobile.transport.handy

import com.magichandy.mobile.core.api.DeviceConnectionFailure
import com.magichandy.mobile.core.api.DeviceConnectionFailure.Reason
import com.magichandy.mobile.core.ports.DeviceConnectionException
import com.magichandy.mobile.transport.http.boundedText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Response

internal const val wrongHandyMode = 100004

internal fun cloudFailure(reason: Reason, http: Int? = null, deviceCode: Int? = null) =
    DeviceConnectionException(DeviceConnectionFailure(reason, http, deviceCode))

/** API v3 reports device errors inside HTTP 200. Response bodies never enter diagnostics. */
internal fun Response.handyReply(): JsonObject {
    if (!isSuccessful) {
        val reason =
            when (code) {
                401,
                403 -> Reason.AccessDenied
                408,
                504 -> Reason.TimedOut
                429 -> Reason.RateLimited
                else -> Reason.ServiceUnavailable
            }
        throw cloudFailure(reason, http = code)
    }
    val text = boundedText(65536)
    if (text.isBlank()) return JsonObject(emptyMap())
    val reply =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw cloudFailure(Reason.InvalidReply)
    for (obj in
        listOfNotNull(reply, reply["result"] as? JsonObject, reply["data"] as? JsonObject)) {
        val error = obj["error"]
        if (error != null && error != JsonNull && error != JsonPrimitive(false)) {
            val device = error as? JsonObject
            val code = (device?.get("code") as? JsonPrimitive)?.intOrNull
            val reason =
                when {
                    code == 1001 || device?.get("connected") == JsonPrimitive(false) ->
                        Reason.Offline
                    code == 1002 -> Reason.TimedOut
                    code == wrongHandyMode -> Reason.DeviceBusy
                    else -> Reason.ServiceUnavailable
                }
            throw cloudFailure(
                reason,
                deviceCode = code?.takeIf { it in setOf(1001, 1002, wrongHandyMode) },
            )
        }
        if (obj["connected"] == JsonPrimitive(false)) throw cloudFailure(Reason.Offline)
        if (listOf("ok", "available", "hsp_available").any { obj[it] == JsonPrimitive(false) })
            throw cloudFailure(Reason.ServiceUnavailable)
    }
    return reply
}

internal fun JsonObject.cloudResult(): JsonObject? =
    if ("result" in this) this["result"] as? JsonObject else this

internal fun JsonObject.explicitAck(): Boolean =
    this["result"] == JsonPrimitive("ok") || this["ok"] == JsonPrimitive(true)

/** Known, consistent state is required. An uninitialized HSP session is not a Stop receipt. */
internal fun JsonObject.cloudPlayback(): HandyPlayback? {
    val candidates = mutableListOf(this)
    var level = listOf(this)
    repeat(3) {
        level = level.flatMap { obj ->
            listOf(
                    "result",
                    "data",
                    "state",
                    "hsp_state",
                    "hspState",
                    "responseHspStop",
                    "responseHspStateGet",
                )
                .mapNotNull { obj[it] as? JsonObject }
        }
        candidates.addAll(level)
    }
    var state: HandyPlayback? = null
    for (obj in candidates) {
        if (
            listOf("ok", "available", "hsp_available", "connected").any {
                obj[it] == JsonPrimitive(false)
            }
        )
            return null
        if (obj["error"]?.let { it != JsonNull && it != JsonPrimitive(false) } == true) return null
        for (key in listOf("playback_state", "play_state", "playState")) {
            val raw = obj[key] as? JsonPrimitive ?: continue
            val next =
                when (raw.content.trim().lowercase()) {
                    "0",
                    "not_initialized" -> HandyPlayback.Uninitialized
                    "1",
                    "playing" -> HandyPlayback.Playing
                    "2",
                    "stopped" -> HandyPlayback.Stopped
                    "3",
                    "paused" -> HandyPlayback.Paused
                    "4",
                    "starving" -> HandyPlayback.Starving
                    else -> return null
                }
            if (state != null && state != next) return null
            state = next
        }
    }
    return state
}

internal fun JsonObject.acknowledges(command: HandyCommand): Boolean {
    val state = cloudPlayback()
    if (state?.rejects(command) == true) return false
    if (explicitAck()) return true
    if (command !is HandyCommand.Stroke) return state != null
    val result = cloudResult() ?: return false
    val min = (result["min"] as? JsonPrimitive)?.doubleOrNull ?: return false
    val max = (result["max"] as? JsonPrimitive)?.doubleOrNull ?: return false
    return min in 0.0..1.0 && max in min..1.0
}
