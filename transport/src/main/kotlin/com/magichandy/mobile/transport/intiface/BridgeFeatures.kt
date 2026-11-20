// Ported from internal/transport/intiface_{types,devices,protocol}.go at 1b80d72e.
package com.magichandy.mobile.transport.intiface

import com.magichandy.mobile.core.api.*
import com.magichandy.mobile.core.ports.*
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal data class Motor(val index: Int, val steps: Int)

internal data class Feature(
    val choice: BridgeTarget,
    val gap: Int,
    val steps: Long,
    val motors: List<Motor> = emptyList(),
    val preferredMillis: Long = 0,
) {
    val index
        get() = choice.deviceIndex
}

internal suspend fun ButtplugSocket.identify(): Long {
    val info =
        request(
            "RequestServerInfo",
            "ServerInfo",
            buildJsonObject {
                put("ClientName", "MagicHandy")
                put("MessageVersion", 3)
            },
        )
    if (info["MessageVersion"] != JsonPrimitive(3)) throw IOException("Unsupported bridge version")
    return (info["MaxPingTime"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..0xffffffffL }
        ?: 0L
}

internal suspend fun ButtplugSocket.features(): List<Feature> {
    val peers =
        request("RequestDeviceList", "DeviceList")["Devices"] as? JsonArray
            ?: throw IOException("Invalid bridge device list")
    if (peers.size > 128) throw IOException("Too many bridge devices")
    return peers.flatMap {
        features(it as? JsonObject ?: throw IOException("Invalid bridge device"))
    }
}

private fun features(device: JsonObject): List<Feature> {
    val index =
        (device["DeviceIndex"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
            ?: return emptyList()
    val protocolName =
        (device["DeviceName"] as? JsonPrimitive)?.contentOrNull?.takeIf {
            it.isNotBlank() && it.length <= 256
        } ?: return emptyList()
    val name =
        (device["DeviceDisplayName"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: protocolName
    val display = name.filterNot(Char::isISOControl).take(120)
    val commands = device["DeviceMessages"] as? JsonObject ?: return emptyList()
    val gap = (device["DeviceMessageTimingGap"] as? JsonPrimitive)?.intOrNull ?: 0
    if (gap !in 0..5000) return emptyList()
    // Hash the immutable protocol description. Server indices and the app's editable name are
    // transient.
    val description = buildJsonArray {
        add(protocolName)
        add(name)
        add(commands.canonical())
    }
    val fingerprint =
        MessageDigest.getInstance("SHA-256")
            .digest(description.toString().encodeToByteArray())
            .toHexString()
    fun identity(kind: String, feature: Int) = "$fingerprint/$kind/$feature"
    val linear = commands["LinearCmd"] as? JsonArray
    if (linear != null)
        return linear.mapIndexedNotNull { axis, item ->
            val f = item as? JsonObject ?: return@mapIndexedNotNull null
            if (f.containsKey("ActuatorType") && f["ActuatorType"] != JsonPrimitive("Position"))
                return@mapIndexedNotNull null
            val steps =
                (f["StepCount"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..0xffffffffL }
                    ?: return@mapIndexedNotNull null
            val descriptor =
                (f["FeatureDescriptor"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(120)
            val family =
                if (protocolName == "The Handy" || protocolName.startsWith("The Handy ("))
                    DeviceFamily.Handy
                else DeviceFamily.LinearActuator
            Feature(
                BridgeTarget(index, display, identity("linear", axis), family, axis, descriptor),
                gap,
                steps,
                preferredMillis = if (protocolName == "The Handy (FW4+)") 180 else 0,
            )
        }
    val motors =
        (commands["ScalarCmd"] as? JsonArray).orEmpty().mapIndexedNotNull { axis, item ->
            val f = item as? JsonObject ?: return@mapIndexedNotNull null
            val steps = (f["StepCount"] as? JsonPrimitive)?.intOrNull ?: 0
            if (f["ActuatorType"] == JsonPrimitive("Vibrate") && steps in 1..65535)
                Motor(axis, steps)
            else null
        }
    return if (motors.isEmpty() || protocolName.contains("Handy", true)) emptyList()
    else
        listOf(
            Feature(
                BridgeTarget(index, display, identity("vibration", 0), DeviceFamily.Vibrator),
                gap,
                0,
                motors,
            )
        )
}

private fun JsonElement.canonical(): JsonElement =
    when (this) {
        is JsonObject -> JsonObject(toSortedMap().mapValues { it.value.canonical() })
        is JsonArray -> JsonArray(map { it.canonical() })
        else -> this
    }
