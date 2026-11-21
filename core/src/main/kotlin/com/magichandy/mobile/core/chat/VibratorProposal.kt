package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.DeviceStatus
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.score.VibrationEnvelope
import com.magichandy.mobile.core.score.VibrationMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun AppState.vibratorContext(): String {
    val rows =
        devices.devices
            .filter { it.membership is RunMembership.InRun }
            .ifEmpty {
                devices.devices.filter {
                    it.status == DeviceStatus.Connected && it.membership == RunMembership.NextRun
                }
            }
    val hasVibration = rows.any {
        (it.settings as? DeviceSettings.Vibrator)?.maxLevel?.value?.let { it > 0 } == true
    }
    if (!hasVibration) return ""
    return if (
        rows.any {
            it.family == DeviceFamily.Handy ||
                it.family == DeviceFamily.AutoblowUltra ||
                it.family == DeviceFamily.LinearActuator
        }
    )
        "/vibrator"
    else "/vibrator-only"
}

internal fun JsonObject.vibration(): VibrationEnvelope? {
    val e = this["vibration"] as? JsonObject
    if (e != null)
        return VibrationEnvelope(
            VibrationMode.entries.single { it.name.equals(e.word("mode"), true) },
            e.getValue("min_percent").jsonPrimitive.int,
            e.getValue("max_percent").jsonPrimitive.int,
            e.getValue("period_seconds").jsonPrimitive.double,
        )
    val level = (this["vibration_level_percent"] as? JsonPrimitive)?.intOrNull ?: return null
    return VibrationEnvelope(VibrationMode.Follow, 0, level, 0.0)
}
