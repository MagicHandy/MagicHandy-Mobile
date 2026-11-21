package com.magichandy.mobile.core.chat

import com.magichandy.mobile.core.api.AppState
import com.magichandy.mobile.core.api.CapabilityLevel
import com.magichandy.mobile.core.api.DeviceFamily
import com.magichandy.mobile.core.api.DeviceSettings
import com.magichandy.mobile.core.api.RunMembership
import com.magichandy.mobile.core.score.TempoScore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal fun AppState.valveAllowed(): Boolean {
    val rows =
        devices.devices.filter {
            it.family == DeviceFamily.VacuGlide && it.membership is RunMembership.InRun
        }
    return rows.isNotEmpty() && rows.all { (it.settings as DeviceSettings.VacuGlide).mayStepValve }
}

internal fun tempoProposal(reply: JsonObject, c: TurnContext): Pair<TempoScore?, Int> {
    val raw = reply["tempo"] as? JsonObject
    val steps = (reply["air_steps"] as? JsonPrimitive)?.intOrNull ?: 0
    require(!c.paused || raw == null && steps == 0)
    require(
        steps in -2..2 &&
            (steps == 0 ||
                c.airAllowed &&
                    c.running &&
                    c.level != CapabilityLevel.Simple &&
                    c.airBalance + steps in -3..3)
    )
    val score = raw?.let {
        if (it.word("mode") == "resume") requireNotNull(c.previousTempo?.takeUnless { c.running })
        else TempoScore.read(it)
    }
    return score to steps
}
