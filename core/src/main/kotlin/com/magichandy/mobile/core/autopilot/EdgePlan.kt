// Ported from internal/chat/edge.go and internal/modes/edge.go at 1b80d72e.
package com.magichandy.mobile.core.autopilot

import com.magichandy.mobile.core.chat.accepts
import com.magichandy.mobile.core.chat.modelObject
import com.magichandy.mobile.core.chat.word
import com.magichandy.mobile.core.ports.ModelRequest
import com.magichandy.mobile.core.ports.PlanningProvider
import com.magichandy.mobile.core.ports.TextMessage
import com.magichandy.mobile.core.ports.TextResult
import com.magichandy.mobile.core.ports.TextRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class EdgePlan(val seconds: Int, val speed: Int, val gentle: Boolean)

/** The technical button action has no conversation or persona, and never retries a refusal. */
internal suspend fun edgePlan(
    provider: PlanningProvider,
    previous: Int,
    holding: Int,
    maximum: Int,
    tempo: Boolean,
): EdgePlan? {
    val minimum = if (tempo) 1 else minOf(10, maximum)
    if (maximum < 1) return null
    val schema =
        Json.parseToJsonElement(
                """{"type":"object","additionalProperties":false,"required":["hold_seconds","resume_speed_percent","resume_style"],"properties":{"hold_seconds":{"type":"integer","minimum":8,"maximum":180},"resume_speed_percent":{"type":"integer","minimum":$minimum,"maximum":$maximum},"resume_style":{"type":"string","enum":["previous","gentle"]}}}"""
            )
            .jsonObject
    val purpose =
        if (tempo)
            "The VacuGlide is holding a low motor tempo. Previous restores the saved tempo; gentle uses the requested steady tempo."
        else
            "A gentle holding pattern is already running. Previous returns to the prior geometry at the chosen pace; gentle retains the holding geometry."
    val facts = buildJsonObject {
        put("previous_speed_percent", previous)
        put("holding_speed_percent", holding)
        put("speed_min_percent", minimum)
        put("resume_speed_max_percent", maximum)
        put("tempo_only", tempo)
    }
    val request =
        ModelRequest(
            listOf(
                TextMessage(
                    TextRole.Developer,
                    purpose +
                        " Choose a measured return within the supplied bounds. Return only the JSON holding plan.",
                ),
                TextMessage(
                    TextRole.User,
                    "The person pressed Edge. Current mechanical state: " + facts,
                ),
            ),
            schema,
            "edge_plan",
        )
    val response = provider.plan(request) as? TextResult.Completed ?: return null
    return try {
        val value = modelObject(response.text)
        if (!schema.accepts(value)) null
        else
            EdgePlan(
                value.getValue("hold_seconds").jsonPrimitive.int,
                value.getValue("resume_speed_percent").jsonPrimitive.int,
                value.word("resume_style") == "gentle",
            )
    } catch (_: IllegalArgumentException) {
        null
    }
}
